# Bluetooth オーディオへの対応（計画、2026-09-26）

Bluetooth のイヤホン・ヘッドホン・スピーカー・車載機器で聴くときに困らないようにする。
対象は「聴く」側（A2DP / LE Audio）。マイクや、コーデック（LDAC など）の選択は OS の
仕事なので対象外。

## いまの状態（調べた結果）

| 項目 | 状態 |
|---|---|
| 鳴らす | OS に任せている。Android は AAudio（SDL2）、Windows は WASAPI（SDL2）。既定の出力へ出すので、Bluetooth が既定になっていればそこで鳴るはず |
| 外れたとき | Android は `ACTION_AUDIO_BECOMING_NOISY`（PlaybackService）で一時停止する。Bluetooth が切れたときもこの知らせが来るはず（未確認） |
| リモコン（再生・一時停止・次・前） | Android は MediaSession（`FLAG_HANDLES_MEDIA_BUTTONS`、onPlay / onPause / onSkipToNext / onSkipToPrevious）がある。ヘッドセットのボタンもここへ来るはず（未確認） |
| 曲名の表示（車載機器など、AVRCP） | MediaSession に題名・作者・長さを出しているので、出るはず（未確認） |
| **遅れ** | **合わせていない。** 表示の遅らせの「自動」は、装置のバッファの長さ（Android 2048 フレーム＝43ms、Windows 512 フレーム＝5ms）だけ。Bluetooth は符号化・送信・受け側のバッファで 150〜300ms ほど遅れるので、**鍵盤などの表示が音より先に動く** |
| 出力の切り替え（途中でつなぐ・外す） | SDL2 の AAudio は、ストリームが切れたときの知らせ（error callback）をログに出すだけで、開き直さない。mxv2 は `SDL_AUDIODEVICEREMOVED` を扱っていない。Windows の WASAPI は、既定の装置が替わったら追いかける作りが SDL2 にある。**どうなるかは実機で見る必要がある** |

## 進め方

### 1. 実機で今の動きを確かめる（コードは変えない）

Bluetooth の機器（イヤホンかスピーカー）と、Pixel 7a / Windows の PC で試す。ユーザーに
機器をつないでもらい、Claude がログ（Android は logcat）を読む。

- 鳴るか。音の途切れ（アンダーラン）が増えないか。
- **再生中に Bluetooth をつなぐ / 外す**: 音が移るか・止まるか・無音のまま固まるか。
  外したときに一時停止するか（既存の BECOMING_NOISY）。SDL の `aaudio_errorCallback` の
  ログ、`SDL_AUDIODEVICEREMOVED` が来るか。
- **遅れ**: 鍵盤と音のずれを目と耳で見る（手元の画面で）。あわせて、測れるかを試す
  （下の 3 の「測る」）。
- ヘッドセットのボタン（再生・一時停止・次・前）が効くか。
- 車載機器やイヤホンのアプリに曲名が出るか（あれば）。
- Windows: 既定の出力を Bluetooth に替えたとき（再生中・停止中）に追いかけるか。遅れ。

ここで分かったことで、2 と 3 の中身を決める。

### 2. 出力の切り替えに強くする（1 で問題があれば）

- 切れたことを知る: `SDL_AUDIODEVICEREMOVED`、AAudio の書き込みの詰まり
  （SDL の `aaudio_DetectBrokenPlayState` と同じ見方）、Android の `AudioDeviceCallback`
  （Java。出力の機器が増えた・減ったを知らせる）。
- 知ったら、演奏位置を保ったまま音の装置を開き直す（`Player` に開き直しを足す。
  一時停止していた状態も保つ）。
- 外れたら一時停止（Android は既存）。Windows でも、既定の出力が替わった
  （＝ヘッドホンを外した）ときに一時停止するかは 1 の結果を見て決める。

### 3. 遅れを合わせる（本命）

表示の遅らせ（`Player::displayLatencyFrames`）に「出力先の遅れ」を足す。

- **出力先を知る**:
  - Android: `AudioManager.getDevices(GET_DEVICES_OUTPUTS)` と `AudioDeviceCallback` で、
    いま鳴っている先が Bluetooth（`TYPE_BLUETOOTH_A2DP` / `TYPE_BLE_HEADSET` /
    `TYPE_BLE_SPEAKER` / `TYPE_HEARING_AID`）かを見て、JNI で知らせる。機器の名前も取れる。
  - Windows: `IMMNotificationClient` で既定の出力の変化を知り、その装置が Bluetooth か
    （列挙元が `BTHENUM` / `BTHLEDevice`）を見る。
- **遅れの量**:
  - 測る（Android、試す価値あり）: 手元で AAudio のストリームを短く開き、
    `AAudioStream_getTimestamp`（いま鳴っているフレームとその時刻）と書いたフレーム数の
    差から出力の遅れを出す。Bluetooth のぶんが入るかは OS の版と機種しだいなので、1 で
    確かめる。SDL が開いているストリームには手が届かない（SDL の中にある）ので、測る
    ための別のストリームになる。
  - 手で: 設定に「Bluetooth のときに足す遅らせ」（0〜500ms、既定 200ms ほど）。測れない
    とき（Windows・古い Android）はこれを使う。機器ごとに覚えるかは、使ってみてから。
  - 既定: 測れたらその値、測れなければ手の値。
- **キャストとの関係に注意**: Chromecast へ送る絵の時刻も `visualFrame`（表示の遅らせを
  引いた位置）から出している。Bluetooth のぶんを表示の遅らせに足すと、TV へ送る絵の
  時刻までずれる（送っている間は手元の音を消すので、TV とは関係ない量）。→ キャスト用の
  時刻は、Bluetooth のぶんを足さない位置から出すよう分ける。

### 4. 画面と文言

- [音の設定]（表示の遅らせの近く）に「Bluetooth のときに足す遅らせ」と、いまの出力先の表示
  （例:「出力: Bluetooth（WH-1000XM4）+200ms」）。
- 文言は message.ini（日本語・英語）。README に Bluetooth の節（遅れと合わせ方）。

## 決めたこと（仮定。違えば直す）

- 最初にやるのは 1（実機の確認）。遅れの合わせ方は、測れるかどうかを見てから決める。
- 対象は Android と Windows。「聴く」だけ。
- 手で合わせる値の既定は 200ms（A2DP の一般的な遅れ。1 で実測して直す）。

## 手順 1 の結果

### Windows（2026-09-27、BT525 FM）

BT525 FM は車用の FM トランスミッター（A2DP / HFP / AVRCP）。Windows には
「ヘッドホン (BT525 FM)」（A2DP）と「ヘッドセット (BT525 FM[ Hands-Free])」（HFP）が
出る。試すのは A2DP のほう。

- mxv2 の出力: WASAPI・96000Hz・buffer 512・自動の遅らせ 5.3ms。
- 鳴る。途切れの報告なし。
- **遅れ: 音が 60fps 換算で 7〜8 フレーム（約 120〜130ms）遅れる**（ユーザーの目視）。
  自動の遅らせ 5.3ms では表示が 115〜125ms 先に動く。
- スピーカー側から音量変更・切断・再接続: mxv2 は落ちずに演奏を続けた。
  **切断で自動的に有線の出力へ、再接続で BT へ戻った**（SDL2 WASAPI の既定装置追従）。
  → Windows では手順 2（出力の切り替え）は要らない。
- 残り: 遅れの補い（WASAPI が BT の遅れを報告するかは未確認）。

### Android（2026-09-27、Pixel 7a + BT525 FM）

- mxv2 の出力: AAudio（Legacy＝AudioTrack 経由、MMAP なし）・48000Hz・buffer 2048・自動の遅らせ 42.7ms。
- `dumpsys media.audio_flinger`: 出力スレッドは AUDIO_DEVICE_OUT_BLUETOOTH_A2DP、
  HAL の latency=250ms。mxv2 のトラックの Latency 欄は **365〜385ms**（末尾 `t`＝時刻から求めた値）。
- ユーザーの目視: 音が 60fps で 10〜15 フレーム以上遅れる（大きくて目では測りにくい）。
- スピーカー側から切断: **ノイズなく一時停止**（BECOMING_NOISY が効いている、ユーザー確認）。
  AAudio は Legacy（AudioTrack）経路なので、ストリームは切れずに行き先だけ替わる
  （logcat `onAudioDeviceUpdate() devices 8952 => 3`（スピーカー）→ 再接続で `3 => 8974`）。
  SDL の error callback は出ていない。→ この経路では手順 2 の開き直しは要らない見込み
  （MMAP / 低遅延の経路になる端末では別）。
- 再接続は自動。一時停止はそのまま（自動では再開しない）で、解除すると BT で鳴る。Android の作法どおりなので手を入れない。
- **手で合わせた値: 画面の遅れ 400〜410ms で合って見える**（ユーザー）。
  報告値 365〜385ms（AudioFlinger のトラックの Latency）＋ SDL 側のバッファ 2048 フレーム（42.7ms）
  ≒ 410〜430ms と近い。→ **自動＝SDL のバッファ長＋AAudio の時刻から求めた遅れ**で合わせられる見込み。

## 手順 3 の実装（2026-09-27）

- `src/outputlatency.*`: SDL と同じ条件で**無音を流す別のストリーム**を開き、OS の
  「いま鳴っているフレームと時刻」と書いたフレーム数から、書いた端が鳴るまでの時間を測る。
  1 秒待ってから 500ms ごとに中央値。しくじったら 1 秒後に開き直す。
  - Android: AAudio を dlopen（API 26 未満は測らない）。`getTimestamp(CLOCK_MONOTONIC)` と
    `getFramesWritten`。Legacy 経路なので出力先が替わっても同じストリームで値が替わる。
  - Windows: WASAPI 共有・イベント駆動・バッファ既定（SDL_wasapi.c と同じ）、装置の形式のまま。
    `IAudioClock::GetPosition`。既定の出力が替わったら（1 秒ごとに ID を見る）開き直す。
- `Player::SetOutputLatency`: 自動のときだけ「SDL のバッファ長＋測った値」にする。
- `playctl.cpp` の `PollOutputLatency`（前面・背面の両ループ）: 演奏中・自動・キャストしていない
  ときだけ測る。5ms 以上変わったら反映、20ms 以上でログ（`Log.AudioOutputLatency`）。
  **キャスト中は足さない**（TV へ送る絵が音より遅れて届き捨てられるため）。
- 設定ウィンドウ: 「うち出力先の遅れ {0} ms（測った値。Bluetooth など）」。

確認:
- Pixel 7a + BT525: 測った値 349ms → 表示の遅らせ 391.7ms。ユーザーが「問題ない」と確認。
- Windows 有線（既定の出力）: 53ms（headless で測定）→ 表示の遅らせ 63.7ms（48kHz、buffer 512）。
  見た目での確認はまだ。**Windows + Bluetooth で約 120ms が測れるかは未確認**。

## BT 側からの操作（メインの目的。2026-09-27）

目的の優先度（ユーザー）: **メインはカーオーディオにつないだときにハンドルリモコンなどで
操作できること**（AVRCP）。画面と音の同期はサブ。BT525 FM にはボタンが無く、代わりに
想定していた TX-NR676E のリモコンは TX-NR676E がつながらないので使えない。

AVRCP のボタン（passthrough）は Android の中で KeyEvent になり、MediaSessionService が
メディアボタンのセッションへ配る。`adb shell cmd media_session dispatch <key>` はその
配り口から入れるので、アプリから見ると同じ。これで試した結果（Pixel 7a）:

| キー | 結果 |
|---|---|
| play-pause | 効く（一時停止・再開） |
| next / previous | 効く（曲が替わる） |
| fast-forward / rewind | **何も起きない**（PlaybackState の actions に無く、Callback も無い） |
| stop | 止まり、**PlaybackService ごと MediaSession が消える**（"Media button session is changed to null"） |
| stop のあとの play | **届かない**（受け手のセッションが無い） |

ほかに気付いたこと:
- 起動して一度も演奏していない間はサービスが無く、セッションも無い → 車の再生ボタンで始められない。
- MediaMetadata: TITLE に MDX のタイトル行が丸ごと（版や (c) まで）、ARTIST に状態の文字
  （「演奏中 CONT REPEAT」）が入っている。車の画面にはこれがそのまま出る。

TX-NR676E（Onkyo）は PC からはつながらなかったが、**Pixel 7a からはつながった**（2026-09-27 22:10）。アンプのリモコンで実物の AVRCP を試せる。

### TX-NR676E のリモコン（実物の AVRCP、2026-09-27 22:16〜22:20）

- 左右キー → `KEYCODE_MEDIA_NEXT` / `KEYCODE_MEDIA_PREVIOUS` が届き、曲が替わる（ユーザー確認・ログ一致）。
- PAUSE ボタン → アンプが再生状態（AVRCP の play status）を見て `KEYCODE_MEDIA_PAUSE` と
  `KEYCODE_MEDIA_PLAY` を交互に送ってくる。どちらも効く（ユーザー確認）。
- 絶対音量（AVRCP absolute volume）は非対応の機器（"abs vol not supported"）。
- 停止・早送り・巻き戻しはこの試験では押していない。

### BT 側からの操作の修正（2026-09-27）

- **MediaSession を PlaybackService から PlaybackBridge へ移した**（setActivity で作り、
  MainActivity.onDestroy で release）。アプリが動いている間ずっと active。止めたら STOPPED に
  するだけで消さない。通知は `PlaybackBridge.sessionToken()` を借りる。
- 止まっているときの PLAY（native の kRequestPlay）: 最後の曲 → カーソルの MDX → 一覧の次の MDX
  の順で掛ける（画面の [▶] と違い、画面を見ずに押すボタンなので最後の曲を先に）。
- 早送り・巻き戻し = ±10 秒（kRemoteSeekStepMs）、SEEK_TO も受ける（TakeSeekMs）。
- メタデータ: TITLE = MDX のタイトル行、ARTIST = 曲のあるフォルダから 32 文字を超えない範囲で親を遡って "/" でつないだもの（曲のあるフォルダだけで超えるならそれだけ。ユーザーの指示。スキーム・ドライブ・SAF の URI 部分は含めない）。状態の文字は通知の本文だけ。
  同じ内容なら渡し直さない。
- 位置を 1 秒ごとに渡し直すのを試した（TX-NR676E の TV 出力で現在位置が "--:--:--"、logcat に
  "No update to play position"）。渡し直すと毎秒 SendMediaUpdate が出るようになったが、表示は
  "--:--:--" のまま。YouTube / YT Music でも同じなので**アンプ側の仕様**（ユーザー判断）。
  渡し直しは取り下げた（毎秒のやり取りを増やすだけなので）。

adb（`cmd media_session dispatch`）で確認: 演奏前の play で始まる / ff・rew で ±10 秒 /
stop で STOPPED、セッションはメディアボタンの受け手のまま / stop 後の play で再開 /
**stop → ホーム → 40 秒後の play でも始まる**。
- アーティスト欄の表示を TX-NR676E の TV 出力でユーザーが確認（2026-09-27）。

### Windows + BT525 の遅れ（2026-09-27）

- WASAPI の測定値は BT525 を既定の出力にしても **42ms**（有線 53ms とほぼ同じ）。**Windows は
  Bluetooth の遅れを報告しない**。目視の遅れ（約 120〜130ms）には届かない。
- 対処: 出力先の endpoint の親デバイスノード（cfgmgr32）が `BTH…`（BTHENUM / BTHHFENUM / BTHLE…）
  なら Bluetooth とみなし、設定 `[Play] BluetoothLatency`（既定 80ms、0〜500）を足す。
  Android は測れるので足さない（`outputlatency::MeasuresBluetooth()`）。設定ウィンドウでは
  Windows だけスライダーを出す。
- BT525 で: 42 + 80 = 122ms → 表示の遅らせ 132.7ms（48kHz）。目視の確認はこれから。
- 目視で遅れ OK（既定 80ms のまま、ユーザー確認）。文言は「Bluetooth 遅延量」（はみ出したため、ユーザー指示）。
- 設定欄の出力先の遅れは、Windows で Bluetooth のとき「出力先の遅れ 122 ms（計測値 42 ms ＋ 80 ms）」（Settings.LatencyOutputBluetooth。ユーザーの文言）。
- 設定欄の行は自動の入り切り・出力先で出し消ししない（使わないスライダーは淡色、注記は空行で高さを保つ。ユーザーの指示）。
  → ユーザーが画面で確認（2026-09-27）。
