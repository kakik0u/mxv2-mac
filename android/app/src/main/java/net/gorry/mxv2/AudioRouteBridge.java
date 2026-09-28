package net.gorry.mxv2;

import android.app.Activity;
import android.content.Context;
import android.media.AudioAttributes;
import android.media.AudioDeviceInfo;
import android.media.AudioManager;
import android.os.Build;
import android.util.Log;

import java.util.List;

/**
 * いま音が出ている先の Bluetooth 機器の名前。
 *
 * ネイティブ側（src/outputlatency.cpp）から JNI で呼ばれる。表示の遅らせに足す
 * 「遅延時間」を Bluetooth の機器ごとに覚えるための、機器の見分けに使う
 * （memo/bluetooth.md）。AV アンプのように受けたあとで音を処理する機器は、
 * Android が測れる遅れより実際の遅れが大きく、その差が機器ごとに違う。
 *
 * <p>Android 13 以降は、メディアの音の行き先そのもの
 * ({@link AudioManager#getAudioDevicesForAttributes}) を見る。それより前と、
 * そちらが使えないときは、つながっている出力の一覧から Bluetooth のものを探す
 * （A2DP がつながっていればメディアの音はそちらへ行く）。
 */
public class AudioRouteBridge {
	private static final String TAG = "mxv2";

	private static Activity sActivity;

	/** MainActivity.onCreate から渡してもらう。 */
	public static synchronized void setActivity(Activity activity) {
		sActivity = activity;
	}

	/** 出力先が Bluetooth の機器ならその名前、そうでなければ空の文字列。 */
	public static String bluetoothOutputName() {
		final Activity a;
		synchronized (AudioRouteBridge.class) {
			a = sActivity;
		}
		if (a == null || Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return "";
		final AudioManager am = (AudioManager)a.getSystemService(Context.AUDIO_SERVICE);
		if (am == null) return "";

		if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
			try {
				final AudioAttributes attr = new AudioAttributes.Builder()
				    .setUsage(AudioAttributes.USAGE_MEDIA)
				    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
				    .build();
				final List<AudioDeviceInfo> route = am.getAudioDevicesForAttributes(attr);
				for (AudioDeviceInfo d : route) {
					if (isBluetooth(d)) return nameOf(d);
				}
				return "";
			} catch (Exception e) {
				// 権限などで断られたら、下の一覧から探す。
				Log.w(TAG, "getAudioDevicesForAttributes failed", e);
			}
		}
		for (AudioDeviceInfo d : am.getDevices(AudioManager.GET_DEVICES_OUTPUTS)) {
			if (isBluetooth(d)) return nameOf(d);
		}
		return "";
	}

	static boolean isBluetooth(AudioDeviceInfo d) {
		switch (d.getType()) {
			case AudioDeviceInfo.TYPE_BLUETOOTH_A2DP:
			case AudioDeviceInfo.TYPE_BLE_HEADSET:
			case AudioDeviceInfo.TYPE_BLE_SPEAKER:
			case AudioDeviceInfo.TYPE_BLE_BROADCAST:
				return true;
			default:
				return false;
		}
	}

	/** 機器の名前。空なら種類で代える（空のままだと Bluetooth でないと読める）。 */
	private static String nameOf(AudioDeviceInfo d) {
		final CharSequence name = d.getProductName();
		if (name != null && name.length() > 0) return name.toString();
		return "Bluetooth";
	}
}
