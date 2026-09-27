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

// 測った値に Bluetooth のぶんが入っているか。Android は入る（AAudio の時刻は
// 受け側の遅れまで含む）。**Windows は入らない**（WASAPI は BT525 FM でも
// 有線とほぼ同じ 42ms を返した。実際は 120ms 余り。memo/bluetooth.md）。
bool MeasuresBluetooth();

// いま測っている出力先が Bluetooth か（Windows だけ。ほかは常に false）。
bool OutputIsBluetooth();

// 終わるとき。作業スレッドを止める。
void Shutdown();

}  // namespace outputlatency
}  // namespace mxv2

#endif
