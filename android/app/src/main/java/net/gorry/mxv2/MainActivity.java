package net.gorry.mxv2;

import org.libsdl.app.SDLActivity;

/**
 * mxv2 の Activity。
 *
 * SDLActivity は getLibraries() の**最後の名前**の共有ライブラリを dlopen し、
 * その中の SDL_main を呼ぶ。既定は "main"（libmain.so）だが、CMake の
 * ターゲット名を Windows と揃えたいので "mxv2"（libmxv2.so）にしている。
 */
public class MainActivity extends SDLActivity {
	/** POST_NOTIFICATIONS を求めるときの requestCode。SDL のものと重ならない値。 */
	private static final int REQUEST_POST_NOTIFICATIONS = 0x4E4F;

	@Override
	protected void onCreate(android.os.Bundle savedInstanceState) {
		// SAF の窓口に Activity を渡す。ネイティブ側（src/safaccess.cpp）は
		// ここから先、この Activity 越しに端末のフォルダを読む。
		SafBridge.setActivity(this);
		// 演奏状態の通知の窓口（src/nowplaying.cpp）。同じく Activity が要る。
		PlaybackBridge.setActivity(this);
		// 端末の向きの固定の窓口（src/orientlock.cpp）。
		OrientationBridge.setActivity(this);
		// ファイルマネージャなどから MDX を渡されたときの窓口
		// （src/openintent.cpp）。
		OpenIntentBridge.setActivity(this);
		// 小窓（ピクチャー・イン・ピクチャー）の窓口（src/pip.cpp）。
		PipBridge.setActivity(this);
		// 出力先の Bluetooth 機器の名前の窓口（src/outputlatency.cpp）。
		AudioRouteBridge.setActivity(this);
		super.onCreate(savedInstanceState);
		requestNotificationPermission();
	}

	/**
	 * Android 13 以降は通知を出すのに許可が要る。断られても演奏は続くので、
	 * 一度尋ねるだけで、結果は見ない（通知が出ないだけ）。
	 */
	private void requestNotificationPermission() {
		if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.TIRAMISU) return;
		if (checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) ==
		    android.content.pm.PackageManager.PERMISSION_GRANTED) {
			return;
		}
		requestPermissions(new String[] { android.Manifest.permission.POST_NOTIFICATIONS },
		                   REQUEST_POST_NOTIFICATIONS);
	}

	// -------------------------------------------------------------------
	// 小窓（ピクチャー・イン・ピクチャー）。判断は PipBridge、ここは
	// Activity の出来事を渡すのと、画面の差し替えだけ。memo/pip.md。
	// -------------------------------------------------------------------

	/** 小窓の中身。初めて小窓に入ったときに作る。 */
	private PipView mPipView;

	@Override
	protected void onUserLeaveHint() {
		super.onUserLeaveHint();
		PipBridge.onUserLeaveHint();
	}

	@Override
	protected void onPause() {
		super.onPause();
		PipBridge.onPause();
	}

	@Override
	protected void onResume() {
		super.onResume();
		PipBridge.onResume();
	}

	@Override
	public void onPictureInPictureModeChanged(boolean inPip,
	                                          android.content.res.Configuration newConfig) {
		super.onPictureInPictureModeChanged(inPip, newConfig);
		PipBridge.onModeChanged(inPip);
	}

	/**
	 * 自分で別の画面（SAF のフォルダ選択、SDL_OpenURL のブラウザなど）を
	 * 開くときも onUserLeaveHint が呼ばれる。startActivity もここを通るので、
	 * 印を立てて小窓に入らないようにする。
	 */
	@Override
	public void startActivityForResult(android.content.Intent intent, int requestCode,
	                                   android.os.Bundle options) {
		PipBridge.onLaunchingOwn();
		super.startActivityForResult(intent, requestCode, options);
	}

	/**
	 * 小窓の中身と SDL の面を入れ替える。**面を隠すと SDL は PAUSED に移り**、
	 * ネイティブは今のバックグラウンドの経路（描かない・演奏は続ける）に入る。
	 * 面を戻すと RESUMED に戻り、ネイティブが画面を描き直す。
	 */
	void showPipContent(boolean on) {
		if (mLayout == null) return;
		if (on) {
			if (mPipView == null) {
				mPipView = new PipView(this);
				mLayout.addView(mPipView, new android.view.ViewGroup.LayoutParams(
				                              android.view.ViewGroup.LayoutParams.MATCH_PARENT,
				                              android.view.ViewGroup.LayoutParams.MATCH_PARENT));
			}
			mPipView.setVisibility(android.view.View.VISIBLE);
			mPipView.start();
			if (mSurface != null) mSurface.setVisibility(android.view.View.GONE);
		} else {
			if (mSurface != null) mSurface.setVisibility(android.view.View.VISIBLE);
			if (mPipView != null) {
				mPipView.stop();
				mPipView.setVisibility(android.view.View.GONE);
			}
		}
	}

	/** 次のレイアウトのあとで r を走らせる。 */
	void postToLayout(Runnable r) {
		if (mLayout != null) mLayout.post(r);
	}

	void postToLayoutDelayed(Runnable r, long delayMs) {
		if (mLayout != null) mLayout.postDelayed(r, delayMs);
	}

	@Override
	protected void onDestroy() {
		PlaybackBridge.release();
		super.onDestroy();
	}

	@Override
	protected void onActivityResult(int request, int result, android.content.Intent data) {
		super.onActivityResult(request, result, data);
		SafBridge.onActivityResult(request, result, data);
	}

	/**
	 * 動いている最中に MDX を渡されたとき（ファイルマネージャなどからの
	 * ACTION_VIEW）。launchMode は singleInstance なので、2 つめのプロセスは
	 * 作られず、代わりにここへ届く。
	 *
	 * **開くのはネイティブのメインループ**（src/openintent.cpp の Poll）。
	 * ここでは覚えるだけにして、UI スレッドを待たせない。
	 */
	@Override
	protected void onNewIntent(android.content.Intent intent) {
		super.onNewIntent(intent);
		// 以後の getIntent() が新しいほうを返すようにしておく
		// （画面が作り直されたときに古い曲を開き直さないため）。
		setIntent(intent);
		OpenIntentBridge.onNewIntent(intent);
	}

	/**
	 * mxv2 のコマンドライン引数。端末には「コマンドライン」が無いので、
	 * インテントの extra "args" から受け取れるようにしてある。
	 *
	 *   adb shell am start -n net.gorry.mxv2/.MainActivity --esa args "-skin,Default"
	 *
	 * ACTION_VIEW で開かれたとき（*.mdx を叩いたとき）は、その URI が
	 * **最後の引数**として足される（OpenIntentBridge.argumentsFor）。
	 * ふつうに起動したときは何も渡らない（引数無しで起動したのと同じ）。
	 */
	@Override
	protected String[] getArguments() {
		return OpenIntentBridge.argumentsFor(getIntent());
	}

	@Override
	protected String[] getLibraries() {
		return new String[] {
			"SDL2",
			"mxv2",
		};
	}
}
