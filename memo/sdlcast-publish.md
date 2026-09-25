# sdlcast を単体で（sdlcastg として） GitHub に公開する計画（2026-09-25）

mxv2/sdlcast/ にある「SDL2 の画面と音を Chromecast へ送るライブラリ」を、
mxv2 とは別のリポジトリとして公開する。作り・経緯は memo/cast.md。

## 今の状態（調べた結果）

- 大きさ: ソース 5,900 行ほど（src 11 本 + include 2 本 + tools 3 本）。protobuf や
  JSON のライブラリは使わず自前（castmsg.cpp / json.cpp）。
- 依存: mbedTLS 3.6.7（ソースから一緒にビルド）、libvpx / opus / libyuv（vcpkg で
  ビルドしたもの）、SDL2（sdlcast_sdl と sdlcastdemo だけ）。Android は Java 側の
  コード不要（MulticastLock は JNI で直に呼ぶ）。
- **単独ビルドはすでにできる**（CMakeLists.txt の冒頭の手順）。ただし依存の既定の
  場所が `../third_party/…`（＝mxv2 の中にあること前提）。
- mxv2 に寄った記述: CMakeLists の説明、encoder.cpp・encodetest.cpp のコメント、
  triplets の「mxv2 の minSdk」「mxv2 と同じ c++_static」。
- ~~仮の試験フック（TEMPTEST）~~ … 回転の不具合を直して取り除いた（2026-09-25）。
- まだ **git に一度も入っていない**（mxv2 でも untracked）。履歴の切り出しは不要。
- 確かめた環境: Windows x64、Android arm64 / armv7（Xperia・Pixel 7a・AQUOS 603SH・
  XS17）。Linux / macOS は未確認（net.cpp は POSIX で書いてあり、MSG_NOSIGNAL は
  #ifdef 付き。macOS では SIGPIPE 対策が別に要る）。
- mxv2 の作業ツリーの直下に空のファイル `C` ができている（17:05。何かのコマンドの
  書き損じ）。公開とは無関係だが消してよいはず。

## 決めること（推奨案で仮置き。違えば直す）

| 項目 | 推奨案 | 理由 |
|---|---|---|
| 名前 | **sdlcastg**（リポジトリ `gorry/sdlcastg`）。**決定済み**（2026-09-25、ユーザー） | — |
| 名前を変える範囲 | リポジトリ・フォルダ・CMake の project とターゲットと変数（`SDLCASTG_*`）・ヘッダーのファイル名（`sdlcastg.h` / `sdlcastg_sdl.h`）・ログの頭（`sdlcastg :`）・C++ の名前空間・**C の関数と型 `SDLCastG_*`、定数 `SDLCASTG_*`**。**決定済み**（2026-09-25、ユーザー） | 最初は関数名を据え置いたが、C には名前空間が無く、名前の重なりで困るのはむしろリンク時の関数名。公開前の今しか安く変えられない、としてユーザーが決めた |
| ライセンス | Apache 2.0。**決定済み**（2026-09-25、ユーザー） | ソースに Copyright ヘッダを書かない流儀も mxv2 のまま |
| mxv2 との関係 | **sdlcastg を本家にし、mxv2 は `git subtree` で取り込む** | mxv2 を clone した人の手間が増えない（submodule だと `--recursive` が要る）。直すときは mxv2 側で直して `subtree push` も可 |
| 依存の入れ方 | 今のまま「置き場所を CMake 変数で渡す」。既定をリポジトリ内の `third_party/` に変える | mxv2 は同じ変数に自分の third_party を渡すだけで済む。vcpkg のマニフェストモードは、triplet が 1 つしか残らない件（cast.md 手順 4）で見送った |
| 文書の言葉 | README は英語（README.md）と日本語（README.ja.md）。ソースのコメントは日本語のまま | 見つけてもらうのは英語。コメントまで訳すと mxv2 と二重管理になる |
| 対応環境の表明 | Windows x64 / Android（arm64・armv7）を「確認済み」、Linux は「たぶん動く（未確認）」 | 確かめていないものを対応とは書かない |
| 版 | **mxv2 と同じ流儀（`2026.0925.1` の形）。決定済み**（2026-09-25、ユーザー）。値は sdlcastg/Profile.ini（著作者専用）の `[Version] Text` / `Number`。タグも mxv2 と同じく `v` を付けない（`2026.MMDD.N`）。API はまだ変わりうると README に書く | — |
| バイナリの配布 | しない（ソースだけ） | ライブラリなので。castplay の exe は将来の検討 |
| Chromecast の名前 | 「Google Cast 対応機器へ送る」と書き、Google と無関係である旨を一文入れる | 商標。非公式の送信側であること・Default Media Receiver（CC1AD845）を使うことも書く |

## 手順

### 1. mxv2 の中で整える（コードの変更）

0. 名前を sdlcastg に変える（上の表の範囲）。mxv2 側は `sdlcast/` → `sdlcastg/`、
   CMakeLists.txt・BUILD.md・NOTICE・README・src/cast.cpp の include とターゲット名を追従
1. CMake の既定の置き場所を sdlcastg/ の中にする
   - `SDLCASTG_MBEDTLS_DIR` → `${CMAKE_CURRENT_SOURCE_DIR}/third_party/mbedtls-3.6.7`
   - `SDLCASTG_DEPS_DIR` → `…/third_party/vcpkg_installed/<triplet>`
   - `SDLCASTG_SDL2_ROOT`（Windows）も同様
   - mxv2 の CMakeLists.txt は `add_subdirectory(sdlcastg)` の前に、この 3 つへ
     mxv2 の third_party を渡す（今の動きは変わらない）
2. SDL2 を Windows 以外でも探す: 親に `sdl2` ターゲットが無く VC 用パッケージも
   無ければ `find_package(SDL2 CONFIG)` を試す（Linux で sdlcastdemo を作れるように）
3. mxv2 に寄った記述を一般的な言い方に直す（「アプリ側」「呼び出し側」など）。
   triplets の説明は「minSdk 21 に合わせる」と理由だけ書く
4. ~~TEMPTEST のフック~~ … 回転の不具合を先に直して消す、とユーザーが決定。
   **2026-09-25 に済み**
5. 版: mxv2 と同じく **sdlcastg/Profile.ini を唯一の置き場所**にし（値は著作者が書く。
   Claude は作らない・変えない）、CMake が読んで `sdlcastg_version.h`（生成物、ビルド先の
   generated/）に `SDLCASTG_VERSION "2026.0925.1"` と `SDLCASTG_VERSION_NUMBER 202609251`
   を書く。実行時に聞ける `SDLCastG_GetVersion()` も足す。
   注意: `0925` をそのまま数値のマクロにすると C では 8 進数の誤りになるので、
   分けた数値は作らない（要るなら先頭の 0 を落とす。mxv2 の VERSIONINFO と同じ）
6. 公開 API を見直す（下の「API の見直し」）
7. `project(sdlcastg …)` には VERSION を書かない（CMake の版は数値の並びなので形が合わない）。`install()` と `sdlcastgConfig.cmake` は
   今回は入れない（add_subdirectory で使う前提と README に書く）

### 2. 文書を書く（sdlcastg/ の中）

- `LICENSE` … Apache 2.0 の全文（mxv2 と同じく無改変）
- `NOTICE` … mbedTLS / libvpx / Opus / libyuv の表示（mxv2 の NOTICE から写す）と、
  **`ports/libvpx/` は vcpkg（MIT）の移植の写しを直したもの**である旨と MIT の表示
- `README.md` / `README.ja.md`
  - 何ができるか（画面と音をその場で VP8 + Opus の WebM にして送る。受信側では
    4 秒ほど遅れる。OS の画面ミラーと違い、アプリの画面だけが出る）
  - 最小の使い方（sdlcast_sdl.h の Begin/EndRendererFrame・SubmitAudioSDL）
  - 制限: 同時 1 台、受信側の証明書は検証しない（自己署名のため）、HTTP の待ち受けを
    開く（Windows のファイアウォールの確認が出る）
  - Android の注意: 権限（INTERNET・ACCESS_WIFI_STATE・CHANGE_WIFI_MULTICAST_STATE）、
    getrandom が止まる古い端末への対処が入っていること
- `BUILD.md` … mxv2 の BUILD.md のキャストの節を移す（vcpkg クラシックモードの
  コマンド、自前の triplets と ports、Android の単体ビルド、MSYS_NO_PATHCONV）
- `docs/design.md` … memo/cast.md から公開してよい部分を抜き出して整理
  （時刻の揃え方・溜め直しと SEEK・受信側の状態の読み方・踏んだ罠）。
  memo/cast.md そのものは作業記録なので出さない
- `CHANGELOG.md` … 初版だけ
- `.gitignore` … `third_party/` `build/`

### 3. 公開前の確かめ

1. 公開される中身だけを取り出してビルド（mxv2 のときと同じく、取り出したフォルダに
   third_party をジャンクションで用意。**終わったらリンクを外す**）
   - Windows: castplay・sdlcast_encodetest・sdlcastdemo
   - Android arm64 / armv7: castplay（adb で `--status` と `--list`）
2. mxv2 側も通しでビルド（Windows・Android）し、TV へ送れることを一度見る
   （TV へ流すのはユーザーの了解を取ってから）
3. 秘密や個人の情報が入っていないか: IP アドレス（192.168.2.x）・機器名（mytv）・
   端末のシリアルがコメントや文書に残っていないか grep

### 4. リポジトリを作って公開（git はユーザーが行う）

```
gh repo create gorry/sdlcastg --public --description "Send SDL2 video and audio to Google Cast devices"
（sdlcastg の中身を新しいフォルダへ写して git init、commit、push）
git tag 2026.MMDD.N && git push --tags   （Profile.ini の [Version] Text と同じ値）
```

### 5. mxv2 を本家から取り込む形へ切り替える（git はユーザーが行う）

```
（mxv2 の作業ツリーの sdlcastg/ を退けてから）
git subtree add --prefix=sdlcastg https://github.com/gorry/sdlcastg.git main --squash
```

- mxv2 の BUILD.md のキャストの節は、sdlcastg の BUILD.md を指す形に縮める
- mxv2 の NOTICE はそのまま（バイナリに入るものの表示なので要る）

### 6. あとで（今回はやらない）

- GitHub Actions で Windows のビルド（libvpx のビルドが重いので vcpkg のキャッシュ込み）
- Linux / macOS の確認（macOS は SO_NOSIGPIPE）
- SDL3 の層
- `install()` と CMake の package config

## API の見直し（公開前に決めておきたいもの）

- `SDLCastG_SeekToLive` / `SDLCastG_ReloadStream` は受信側の溜め直しから戻すための
  もので、使い方が分かりにくい。mxv2 の cast.cpp の Poll にある「受信側の状態から
  一時停止・再開・終了・立て直しを判断する」部分をライブラリ側へ移して、
  イベント（PAUSED / RESUMED / ENDED）で返す形にすると使う側が楽になる。
  **ただし初版は今の形のまま出し、README に mxv2 の cast.cpp を実例として
  示す**のを推奨（移すと mxv2 側の確認をやり直すことになる）
- `SDLCastG_StreamConfig` の既定値（854x480 / 1280x720、30fps、ビットレート）を
  README に表で出す

## 進み具合（2026-09-25 夜）

**手順 1〜3 は済み。手順 4（リポジトリを作って公開）からはユーザーの作業。**

- 手順 1: 名前を sdlcastg に（フォルダ・ヘッダー `sdlcastg.h` / `sdlcastg_sdl.h`・ターゲット
  `sdlcastg` / `sdlcastg_sdl`・見本 `sdlcastgdemo` / `sdlcastg_encodetest`・CMake の変数
  `SDLCASTG_*`・名前空間・ログの頭 `sdlcastg :`・中の二重読み込み防止）。**C の関数・型・定数
  は、あとで `SDLCastG_*` / `SDLCASTG_*` に変えた（下の追記）。** 依存の既定は `sdlcastg/third_party/`、
  `SDLCASTG_MBEDTLS_DIR` / `SDLCASTG_VCPKG_INSTALLED_DIR` / `SDLCASTG_DEPS_DIR` /
  `SDLCASTG_SDL2_ROOT` で差し替え（取り込む側が普通の変数で決めてもよい。`if(NOT DEFINED)`
  で守っている）。mxv2 は mxv2 の third_party を渡す。見本は単独のときだけ既定で作る。
  SDL2 は Windows の VC 用パッケージの次に `find_package(SDL2 CONFIG)`。
  版は `sdlcastg/Profile.ini` の [Version] Text / Number →
  `<ビルド先>/generated/sdlcastg_version.h`、`SDLCastG_GetVersion()`。**Profile.ini は
  まだ無い（著作者が書く）**。無ければ警告を出して "unknown"。
  mxv2 に寄った記述・`memo/cast.md` への参照は一般的な言い方と `docs/design.md` に直した。
- 手順 2: `LICENSE`（mxv2 と同じ全文）、`NOTICE`（英語。Mbed TLS / libvpx / Opus / libyuv、
  ports/libvpx の vcpkg の MIT 表示、商標の一文）、`README.md` / `README.ja.md`、
  `BUILD.md` / `BUILD.ja.md`、`docs/design.md`（日本語。memo/cast.md から公開してよいものを
  整理。TV の名前・IP・機種名は外した）、`CHANGELOG.md`、`.gitignore`。
- 手順 3: mxv2 の外へ写して単独でビルド（Windows Release: castplay / sdlcastgdemo /
  sdlcastg_encodetest、警告なし。encodetest の WebM は ffprobe で vp8 / opus）。仮の
  Profile.ini（写しの中だけ）で版の生成も確認。Android arm64 / armv7 も警告なしで通り、
  Pixel 7a で `castplay --list`（3 台）と `--status`（0.24 秒）。mxv2 は Windows の 2 つの
  ビルドと Android のリリース版が通った。公開物の中の IP・機器名・端末のシリアル・手元の
  パスは grep で無いことを確かめた（例の NDK の場所は `C:/path/to/android-ndk`）。

ユーザーに残っていること:
1. ~~`sdlcastg/Profile.ini` を書く~~ … 2026-09-25 に用意された（2026.0925.1）。
2. NOTICE の権利表示 `Copyright (C) 2026 GORRY.` を確かめる（SkinEditor と同じ書き方にした）。
3. 手順 4・5（リポジトリの作成と公開、mxv2 の subtree への切り替え）。

### 追記: C の API の頭も変えた（2026-09-25 夜、ユーザーの決定）

`SDLCast_*` → `SDLCastG_*`、`SDLCAST_*` → `SDLCASTG_*`（372 か所。sdlcastg のソースと文書、
mxv2 の src/cast.* と AndroidManifest のコメント）。すでにあった `SDLCASTG_`（CMake の変数・
版のマクロ・二重読み込み防止）は正規表現の `SDLCAST_` に当たらないので巻き込んでいない。
名前が 1 文字延びたぶんの引数のそろえも直した。mxv2 の Windows 2 つ・Android、単独ビルド
（Windows）が通り、Pixel 7a で探索（lockTag=sdlcastg）と一覧を確かめた。memo/cast.md など
過去の記録の中の `SDLCast_` はそのまま。

## 手順 4・5 の具体的なコマンド（2026-09-25 夜。gorry/sdlcastg は private で作成済み・空）

公開する中身は `mxv2/sdlcastg/` の 50 ファイル（`.gitignore` で build/・third_party/・*.bak・
*.user を除く。改行はすべて LF）。git の操作はユーザーが行う。

### 4. sdlcastg のリポジトリへ最初の中身を入れる

```sh
# mxv2 の外に作業用の置き場を作る（例: H:/proj/sdlcastg）
mkdir /h/proj/sdlcastg && cd /h/proj/sdlcastg
git init -b main
cp -r /h/proj/mxv2/mxv2/sdlcastg/. .
git add .
git status            # 50 ファイル、*.bak が入っていないこと
git commit -m "First release 2026.0925.1"
git remote add origin https://github.com/gorry/sdlcastg.git
git push -u origin main
git tag 2026.0925.1 && git push origin 2026.0925.1
```

### 5. mxv2 を subtree で取り込む形にする

`git subtree add` は作業ツリーがきれいでないと動かない。mxv2 には未コミットの変更
（キャストの組み込み・回転の修正など）があるので、先にそれをコミットする。

```sh
cd /h/proj/mxv2/mxv2
# (1) sdlcastg/ 以外の変更をコミット（sdlcastg/ はまだ untracked のまま入れない）
# (2) 手元の sdlcastg/ を退ける（untracked なので git には影響しない）
mv sdlcastg ../sdlcastg.before-subtree
# (3) 取り込む
git subtree add --prefix=sdlcastg https://github.com/gorry/sdlcastg.git main --squash
# (4) 退けたものと同じか確かめる（*.bak だけが違うはず）
diff -r ../sdlcastg.before-subtree sdlcastg
```

以後、sdlcastg を mxv2 の中で直したら `git subtree push --prefix=sdlcastg <url> main`、
本家で直したものは `git subtree pull --prefix=sdlcastg <url> main --squash`。

注意: mxv2 を push すると main が sdlcastg（private のうちは見えない）を取り込んだ形になる。
sdlcastg を public にしてから mxv2 を push するのが順当。

### 2026-09-26 の状況と直し方

- 最初の push は済み（ff29418、タグ 2026.0925.1）。ただし**枝の名前が master**
  （計画と Profile.ini の [URL] Notice は main を前提にしている）。
- ユーザーの global の `core.autocrlf=true` で、クローンの作業ツリーが CRLF になる
  （リポジトリの中は LF）。mxv2 へ subtree で取り込んでも CRLF で出てくる
  → sdlcastg に `.gitattributes`（`* text=auto eol=lf`、`*.patch -text`）を足す。
- `git subtree add` の「working tree has modifications」は、mxv2 の未コミットの変更のため。
- 手元の写し: `H:/proj/sdlcastg/sdlcastg`（作業用のクローン）、`H:/proj/mxv2/sdlcastg_`
  （最初に push した置き場。同じコミット）、`H:/proj/mxv2/sdlcastg.begore-subtree`
  （mxv2 から退けたもの）。中身は改行と *.bak 以外同じ。
