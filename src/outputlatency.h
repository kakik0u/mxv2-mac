// mxv2 - 出力先の遅れを測る（Bluetooth など）
//
// 表示の遅らせの「自動」は、もとは SDL のバッファ長だけだった。Bluetooth の
// 機器では符号化・送信・受け側のバッファで 120〜400ms ほど音が遅れ、鍵盤が
// 音より先に動く（memo/bluetooth.md）。そのぶんを測って足すための部品。
//
// SDL が開いているストリームには手が届かない（SDL の中にある）ので、**同じ
// 条件で無音を流す別のストリーム**を開き、OS が返す「いま鳴っているフレームと
// その時刻」と書いたフレーム数から、書いた音が鳴るまでの時間を出す。
//   Android: AAudio（AAudioStream_getTimestamp）。API 26 未満は測らない。
//   Windows: WASAPI（IAudioClock::GetPosition）。
// 出力先が替わる（Bluetooth をつなぐ・外す）と、次の測り直しで値も替わる。
//
// 呼ぶのはメインスレッドから。測るのは作業スレッドで、値だけを渡す。

#ifndef MXV2_OUTPUTLATENCY_H
#define MXV2_OUTPUTLATENCY_H

#include <string>

namespace mxv2 {
namespace outputlatency {

// この環境で測れるか（Android 8.0 以降・Windows）。
bool Available();

// 測るかどうか。演奏中（一時停止していない）だけ true にする。sampleRate は
// SDL に開かせたのと同じ値（Android は変換の段が変わると遅れも変わるので）。
// false にしても、最後に測った値は残る。
void SetActive(bool active, int sampleRate);

// 最後に測った遅れ（ミリ秒）。まだ測れていなければ -1。
int LatencyMs();

// いまの出力先が Bluetooth か。**メインスレッドから呼ぶこと**（Android は JNI、
// Windows は COM）。どちらも 1 秒ごとに引き直す。演奏していなくても分かる。
//   Android: メディアの音の行き先（net.gorry.mxv2.AudioRouteBridge）。
//   Windows: 既定の出力の親が Bluetooth の機器か。
// どちらも、測った遅れには受け側の中の遅れ（AV アンプの処理など）が入らない。
// Windows は Bluetooth の遅れそのものも入らない（WASAPI は BT525 FM でも有線と
// ほぼ同じ 42ms を返した。memo/bluetooth.md）。そのぶんは機器ごとに手で足す
// （設定の [Bluetooth] の遅延時間。playctl.cpp の PollOutputLatency）。
bool OutputIsBluetooth();

// 出力先の Bluetooth 機器の名前（UTF-8）。Bluetooth でなければ空。
// 遅延時間を機器ごとに覚える鍵にする。メインスレッドから呼ぶこと。
std::string BluetoothName();

// 次の BluetoothName で必ず引き直させる（Bluetooth の機器がつながった直後など、
// 1 秒前の答えでは困るとき）。
void RefreshBluetoothName();

// 終わるとき。作業スレッドを止める。
void Shutdown();

}  // namespace outputlatency
}  // namespace mxv2

#endif
