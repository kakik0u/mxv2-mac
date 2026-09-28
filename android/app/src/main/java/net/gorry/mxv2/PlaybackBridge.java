package net.gorry.mxv2;

import android.app.Activity;
import android.content.Intent;
import android.media.MediaMetadata;
import android.media.session.MediaSession;
import android.media.session.PlaybackState;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;

import java.util.ArrayDeque;

/**
 * 演奏状態の通知の窓口。
 *
 * ネイティブ側（src/nowplaying.cpp）から JNI で呼ばれる。通知そのものを出すのは
 * 前面サービス {@link PlaybackService} で、ここは
 *
 *   ・ネイティブ → サービス … 出す内容（曲名・状態・位置）を預かって出し直させる
 *   ・サービス → ネイティブ … 通知のボタンや他アプリの都合で起きたことを
 *                              待ち行列に積む（ネイティブが takeRequest で取る）
 *
 * という受け渡しだけを持つ。**文言は message.ini からネイティブ経由で渡ってくる**
 * ので、ここにも strings.xml にも日本語は置かない。
 *
 * **MediaSession（ヘッドセット・車・AV アンプなど Bluetooth 側のボタンの受け口）は
 * ここが持つ。** アプリが動いている間ずっと置いておき、止めている間も演奏前も
 * 再生ボタンを受ける（カーオーディオでエンジンを掛けたときにハンドルの再生
 * ボタンで始められるように。memo/bluetooth.md）。通知のサービスは演奏している
 * 間だけ立ち、このセッションの token を借りる。以前はサービスが持っていたので、
 * 停止でサービスごと消え、以後の再生ボタンが届かなかった。
 *
 * **呼ばれるスレッドが違う**（ネイティブは SDL のメインスレッド、サービスは
 * UI スレッド）ので、状態を触るところは synchronized にしてある。
 */
public class PlaybackBridge {
	private static final String TAG = "mxv2";

	// ネイティブの mxv2::nowplaying::Request と同じ並び。
	public static final int REQ_NONE = 0;
	public static final int REQ_PLAY = 1;
	public static final int REQ_PAUSE = 2;
	public static final int REQ_PREV = 3;
	public static final int REQ_NEXT = 4;
	public static final int REQ_STOP = 5;
	public static final int REQ_FOCUS_LOST = 6;
	public static final int REQ_FOCUS_GAINED = 7;
	public static final int REQ_SEEK_FORWARD = 8;
	public static final int REQ_SEEK_BACK = 9;
	/** 位置は takeSeekMs で取る。 */
	public static final int REQ_SEEK_TO = 10;
	/** 出力先が外れた (BECOMING_NOISY)。 */
	public static final int REQ_ROUTE_LOST = 11;
	/** Bluetooth の出力機器がつながった。 */
	public static final int REQ_BT_CONNECTED = 12;

	private static Activity sActivity;

	/** Bluetooth 側などのボタンの受け口。setActivity で作り、release で捨てる。 */
	private static MediaSession sSession;
	private static Handler sHandler;
	/** REQ_SEEK_TO の行き先 (ms)。続けて来たら最後のものだけ使う。 */
	private static long sSeekToMs;

	/** 起きているサービス。onCreate/onDestroy で自分を入れ替える。 */
	private static PlaybackService sService;

	/** startService まで済んでいるか。 */
	private static boolean sStarted;

	// 通知に出す文言（message.ini 由来）。
	private static String sChannelName = "Playback";
	private static String sChannelDesc = "";
	private static String sPrevLabel = "Prev";
	private static String sPlayLabel = "Play";
	private static String sPauseLabel = "Pause";
	private static String sNextLabel = "Next";
	private static String sStopLabel = "Stop";

	// いま出す内容。
	/** 曲を持っている（update で立ち、shutdown で下りる）。小窓が見る。 */
	private static boolean sActive;
	private static String sTitle = "";
	private static String sText = "";
	/** セッション（車の画面など）に出すアーティスト欄とアルバム欄。
	 *  MDX には曲名しか無いので、アーティストはファイル名、アルバムはフォルダ名。 */
	private static String sArtist = "";
	private static String sAlbum = "";
	/** フォルダの中で何曲目か（1 から）と曲数。分からなければ 0。 */
	private static int sTrackNumber;
	private static int sTrackCount;
	private static boolean sPlaying;
	private static long sPosMs;
	private static long sDurMs;
	/** sPosMs を受け取った時刻 (SystemClock.elapsedRealtime)。小窓が位置を進めるのに使う。 */
	private static long sStampMs;

	private static final ArrayDeque<Integer> sRequests = new ArrayDeque<Integer>();

	public static void setActivity(Activity a) {
		sActivity = a;
		createSession(a);
	}

	/** Activity の onDestroy から。セッションを捨てる。 */
	public static void release() {
		if (sSession != null) {
			sSession.setActive(false);
			sSession.release();
			sSession = null;
		}
	}

	/** 通知の MediaStyle に渡す。まだ無ければ null。 */
	static MediaSession.Token sessionToken() {
		return (sSession != null) ? sSession.getSessionToken() : null;
	}

	public static boolean available() {
		return sActivity != null;
	}

	// -------------------------------------------------------------------
	// ネイティブから
	// -------------------------------------------------------------------

	public static synchronized void setLabels(String channel, String channelDesc, String prev,
	                                          String play, String pause, String next,
	                                          String stop) {
		sChannelName = channel;
		sChannelDesc = channelDesc;
		sPrevLabel = prev;
		sPlayLabel = play;
		sPauseLabel = pause;
		sNextLabel = next;
		sStopLabel = stop;
	}

	/**
	 * 出す内容を差し替える。まだサービスが動いていなければ起こす。
	 *
	 * **前面サービスはアプリが前面にいるあいだしか起こせない**（Android 12 以降）。
	 * 演奏を始めるのは画面を見ているときなので、ふつうはここで起きる。曲が
	 * 変わるだけならサービスは動いたままなので、バックグラウンドでの自動送り
	 * (CONT/REPEAT) でも起こし直しは要らない。
	 */
	public static void update(String title, String text, String artist, String album,
	                          int trackNumber, int trackCount, boolean playing,
	                          long posMs, long durMs) {
		final Activity a = sActivity;
		if (a == null) return;

		synchronized (PlaybackBridge.class) {
			sActive = true;
			sStampMs = SystemClock.elapsedRealtime();
			sTitle = title;
			sText = text;
			sArtist = artist;
			sAlbum = album;
			sTrackNumber = trackNumber;
			sTrackCount = trackCount;
			sPlaying = playing;
			sPosMs = posMs;
			sDurMs = durMs;
			if (!sStarted) {
				sStarted = true;
				try {
					Intent i = new Intent(a, PlaybackService.class);
					if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
						a.startForegroundService(i);
					} else {
						a.startService(i);
					}
				} catch (Exception e) {
					// バックグラウンドからは起こせないことがある。演奏そのものは
					// 続くので、通知だけ諦める（次に前面へ戻ったときに出る）。
					Log.w(TAG, "cannot start the playback service", e);
					sStarted = false;
				}
				PipBridge.onStateChanged();
				postSessionUpdate();
				return;  // 起動時に onStartCommand が出す
			}
		}
		postSessionUpdate();
		refresh();
		// 小窓のボタン（一時停止 / 再開）と、自動で入るかどうか。
		PipBridge.onStateChanged();
	}

	/** 通知を消してサービスを止める。 */
	public static void shutdown() {
		final Activity a = sActivity;
		synchronized (PlaybackBridge.class) {
			sActive = false;
		}
		PipBridge.onStateChanged();
		// セッションは残して「止まっている」にする（再生ボタンを受け続ける）。
		postSessionUpdate();
		synchronized (PlaybackBridge.class) {
			if (!sStarted) return;
			sStarted = false;
		}
		if (a == null) return;
		try {
			a.stopService(new Intent(a, PlaybackService.class));
		} catch (Exception e) {
			Log.w(TAG, "cannot stop the playback service", e);
		}
	}

	public static synchronized int takeRequest() {
		if (sRequests.isEmpty()) return REQ_NONE;
		return sRequests.poll().intValue();
	}

	/** REQ_SEEK_TO の行き先 (ms)。 */
	public static synchronized long takeSeekMs() {
		return sSeekToMs;
	}

	// -------------------------------------------------------------------
	// MediaSession
	// -------------------------------------------------------------------

	private static void createSession(Activity a) {
		if (sSession != null) return;
		sHandler = new Handler(Looper.getMainLooper());
		// アプリの Context で作る（Activity が作り直されても使い続ける）。
		sSession = new MediaSession(a.getApplicationContext(), "mxv2");
		sSession.setFlags(MediaSession.FLAG_HANDLES_MEDIA_BUTTONS |
		                  MediaSession.FLAG_HANDLES_TRANSPORT_CONTROLS);
		sSession.setCallback(new MediaSession.Callback() {
			@Override
			public void onPlay() {
				postRequest(REQ_PLAY);
			}

			@Override
			public void onPause() {
				postRequest(REQ_PAUSE);
			}

			@Override
			public void onStop() {
				postRequest(REQ_STOP);
			}

			@Override
			public void onSkipToNext() {
				postRequest(REQ_NEXT);
			}

			@Override
			public void onSkipToPrevious() {
				postRequest(REQ_PREV);
			}

			// 早送り・巻き戻し（車のハンドルやリモコンの長押しで来ることが多い）。
			@Override
			public void onFastForward() {
				postRequest(REQ_SEEK_FORWARD);
			}

			@Override
			public void onRewind() {
				postRequest(REQ_SEEK_BACK);
			}

			// 車の画面・ロック画面の位置の棒から。
			@Override
			public void onSeekTo(long pos) {
				synchronized (PlaybackBridge.class) {
					sSeekToMs = pos;
				}
				postRequest(REQ_SEEK_TO);
			}
		});
		updateSession();
		sSession.setActive(true);
	}

	private static void postSessionUpdate() {
		final Handler h = sHandler;
		if (h == null) return;
		h.post(new Runnable() {
			public void run() {
				updateSession();
			}
		});
	}

	/** セッションへ最後に渡した曲の情報。同じなら渡し直さない（AVRCP の相手には
	 *  曲が替わったように見えることがある）。 */
	private static String sShownTitle;
	private static String sShownArtist;
	private static String sShownAlbum;
	private static int sShownTrackNumber = -1;
	private static int sShownTrackCount = -1;
	private static long sShownDurMs = -1;

	/** UI スレッドで。今の状態をセッションへ写す。 */
	private static void updateSession() {
		final MediaSession session = sSession;
		if (session == null) return;
		final Snapshot s = snapshot();

		// 止めている間も最後の曲の情報は残す（車の画面に何も出ないよりよい）。
		if (s.active && !(s.title.equals(sShownTitle) && s.artist.equals(sShownArtist) &&
		                  s.album.equals(sShownAlbum) && s.trackNumber == sShownTrackNumber &&
		                  s.trackCount == sShownTrackCount && s.durMs == sShownDurMs)) {
			sShownTitle = s.title;
			sShownArtist = s.artist;
			sShownAlbum = s.album;
			sShownTrackNumber = s.trackNumber;
			sShownTrackCount = s.trackCount;
			sShownDurMs = s.durMs;
			MediaMetadata.Builder md = new MediaMetadata.Builder();
			md.putString(MediaMetadata.METADATA_KEY_TITLE, s.title);
			// 状態の文字（演奏中・CONT など）は通知の本文だけに出す。
			// ここへ入れると車や AV アンプの画面にそのまま出る。
			md.putString(MediaMetadata.METADATA_KEY_ARTIST, s.artist);
			md.putString(MediaMetadata.METADATA_KEY_ALBUM, s.album);
			// 分からないときは入れない（0 を渡すと「0 曲目」と出す相手がいる）。
			if (s.trackNumber > 0 && s.trackCount > 0) {
				md.putLong(MediaMetadata.METADATA_KEY_TRACK_NUMBER, s.trackNumber);
				md.putLong(MediaMetadata.METADATA_KEY_NUM_TRACKS, s.trackCount);
			}
			md.putLong(MediaMetadata.METADATA_KEY_DURATION, s.durMs);
			session.setMetadata(md.build());
		}

		PlaybackState.Builder ps = new PlaybackState.Builder();
		ps.setActions(PlaybackState.ACTION_PLAY | PlaybackState.ACTION_PAUSE |
		              PlaybackState.ACTION_PLAY_PAUSE | PlaybackState.ACTION_STOP |
		              PlaybackState.ACTION_SKIP_TO_NEXT |
		              PlaybackState.ACTION_SKIP_TO_PREVIOUS |
		              PlaybackState.ACTION_FAST_FORWARD | PlaybackState.ACTION_REWIND |
		              PlaybackState.ACTION_SEEK_TO);
		if (!s.active) {
			ps.setState(PlaybackState.STATE_STOPPED, 0, 0.0f);
		} else {
			// 位置は受け取った時刻から進める（小窓と同じ）。
			long pos = s.posMs;
			final long now = SystemClock.elapsedRealtime();
			if (s.playing) {
				pos += now - s.stampMs;
				if (s.durMs > 0 && pos > s.durMs) pos = s.durMs;
			}
			ps.setState(s.playing ? PlaybackState.STATE_PLAYING : PlaybackState.STATE_PAUSED,
			            pos, s.playing ? 1.0f : 0.0f, now);
		}
		session.setPlaybackState(ps.build());
	}

	// -------------------------------------------------------------------
	// サービスから
	// -------------------------------------------------------------------

	static synchronized void setService(PlaybackService s) {
		sService = s;
	}

	/** サービスが止まった（自分から止まった場合も含む）。 */
	static synchronized void serviceGone(PlaybackService s) {
		if (sService == s) sService = null;
		sStarted = false;
	}

	/** 通知を出し直させる。UI スレッドへ渡すのはサービス側。 */
	static void refresh() {
		final PlaybackService s;
		synchronized (PlaybackBridge.class) {
			s = sService;
		}
		if (s != null) s.postRefresh();
	}

	static synchronized void postRequest(int req) {
		// 際限なく溜めない（ネイティブが取りに来ないことは無いはずだが、
		// バックグラウンドで詰まったときの保険）。
		if (sRequests.size() > 32) sRequests.clear();
		sRequests.add(Integer.valueOf(req));
	}

	// 通知を組み立てるための読み出し。まとめて 1 つの箱で渡す。
	static synchronized Snapshot snapshot() {
		Snapshot s = new Snapshot();
		s.channelName = sChannelName;
		s.channelDesc = sChannelDesc;
		s.prevLabel = sPrevLabel;
		s.playLabel = sPlayLabel;
		s.pauseLabel = sPauseLabel;
		s.nextLabel = sNextLabel;
		s.stopLabel = sStopLabel;
		s.active = sActive;
		s.title = sTitle;
		s.text = sText;
		s.artist = sArtist;
		s.album = sAlbum;
		s.trackNumber = sTrackNumber;
		s.trackCount = sTrackCount;
		s.playing = sPlaying;
		s.posMs = sPosMs;
		s.durMs = sDurMs;
		s.stampMs = sStampMs;
		return s;
	}

	static class Snapshot {
		String channelName;
		String channelDesc;
		String prevLabel;
		String playLabel;
		String pauseLabel;
		String nextLabel;
		String stopLabel;
		boolean active;
		String title;
		String text;
		String artist;
		String album;
		int trackNumber;
		int trackCount;
		boolean playing;
		long posMs;
		long durMs;
		long stampMs;
	}
}
