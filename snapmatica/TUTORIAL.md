# Snapmatica チュートリアル / Tutorial

**[日本語](#日本語) ・ [English](#english)**

カメラが初めてなら、先に[はじめてガイド](BEGINNERS_GUIDE.md)をどうぞ。
New to cameras? Start with the [beginner's guide](BEGINNERS_GUIDE.md).

---

## 日本語

Snapmatica は、Minecraft の中で「本物のカメラで撮る」ためのクライアント MOD です。
ファインダーを覗き、ズームして、ピントを合わせ、絞りとシャッターを決めて撮る。ボケも、
ブレも、光の量も、実際のレンズと同じ式から計算されます。

### 目次
1. [必要なもの](#1-必要なもの)
2. [キー操作](#2-キー操作)
3. [最初の一枚](#3-最初の一枚)
4. [ファインダーの見方](#4-ファインダーの見方)
5. [カメラ設定（G キー）](#5-カメラ設定g-キー)
6. [ピント合わせ](#6-ピント合わせ)
7. [露出](#7-露出)
8. [スケール ― ボケの大きさを決めるもの](#8-スケール--ボケの大きさを決めるもの)
9. [絞り積分 ― いちばん綺麗な写真](#9-絞り積分--いちばん綺麗な写真)
10. [常時被写界深度](#10-常時被写界深度)
11. [フリーカム・ドローン・カメラパス](#11-フリーカムドローンカメラパス)
12. [動画](#12-動画)
13. [アルバムと保存先](#13-アルバムと保存先)
14. [困ったとき](#14-困ったとき)

---

### 1. 必要なもの

- **Fabric Loader** と **Fabric API**（必須）
- 対応バージョン: 1.20.1 / 1.21–1.21.1 / 1.21.2–1.21.3 / 1.21.4 / 1.21.10 / 1.21.11 / 26.1.2 / 26.3
- **クライアント専用**です。サーバーに入れる必要はありません。
- Iris のシェーダーパックと一緒に使えます。
- 26.3 では、ビデオ設定の描画 API を **OpenGL** にしてください。ボケやピーキングは
  OpenGL で描いているので、Vulkan では動きません。
- 動画を MP4 で保存するには **ffmpeg** が必要です（PATH が通っていること）。無ければ
  連番 PNG で保存されます。

### 2. キー操作

キーはすべて「設定 → 操作設定 → Snapmatica」で変更できます。

| キー | 動作 |
|---|---|
| **スニーク（しゃがむ）** | ファインダーを覗く（しゃがんでいる間だけ） |
| **Enter** | シャッター |
| **G** | カメラ設定を開く |
| **V** | 縦位置 ／ 横位置 の切り替え |
| **R** | 動画設定を開く（録画中は停止） |
| **,（カンマ）** | 「しゃがむとファインダー」の ON/OFF |
| **Z** | カメラパスのメニュー（フリーカム中のみ） |
| **X** | ドローンモード：オービットの中心を置く ／ 外す |
| **C** | フリーカム：カメラ固定 ／ プレイヤー操作の切り替え |

ファインダーを覗いている間は、マウスホイールがレンズの操作になります。

| 操作 | 動作 |
|---|---|
| ホイール | ズーム（8mm〜800mm） |
| **Ctrl** + ホイール | 絞り（F1.4〜F22） |
| **Alt** + ホイール | シャッター速度（M / Tv のとき） |
| **Ctrl + Alt** + ホイール | ピント距離（MF のとき） |
| **矢印キー** | AF 点を動かす |

### 3. 最初の一枚

1. **しゃがむ**とファインダーが開きます。枠の中が写真に写る範囲です（3:2）。
2. **ホイール**でズーム。望遠にするほど背景がボケやすくなります。
3. **G** でカメラ設定を開き、フォーカスモードを **AF** にします（初期値は MF）。
   中央の十字に重なったものに自動でピントが合います。
4. **Enter** で撮影。写真は保存され、同時にクリップボードにもコピーされます（DNG は
   除く）。そのまま Discord などに貼り付けられます。

初期状態は露出モード M（マニュアル）、F値・シャッター・ISO すべて手動です。まずは
露出モードを **P**（全自動）か **Av**（絞り優先）にすると楽です。

### 4. ファインダーの見方

- **左上** ― レンズの焦点距離の範囲と、露出モード ・ フォーカスモード ・ 縦横
  （例 `Av | AF | 3:2 H`）。
- **右上** ― 今ピントが合っている距離です（例 `46.2blk (46m)`、無限遠は `inf`）。
  AF なら被写体までの距離になります。括弧の中はスケール（→ 8章）で換算した実寸。
- **中央** ― AF 点。三分割のガイド線も出ます。AF 点を動かすと、元の位置に薄い灰色の
  目印が出ます。
- **下** ― F値、シャッター速度、ISO、焦点距離。その下の目盛りが**露出計**で、中央から
  ずれているほど明るすぎ／暗すぎです。

### 5. カメラ設定（G キー）

左列が「写真」（露出とレンズ）、右列が「カメラ」（画づくりと機能）です。
左右の矢印で値を変えます。下のボタンから**アルバム**と**フリーカム**に行けます。

**露出**
- **絞り / シャッター / ISO** ― 露出の三要素。自動になっている項目は `AUTO` と出ます。
- **露出モード** ― M ／ Av ／ Tv ／ P（→ 7章）
- **ND フィルタ** ― 光を減らすフィルタ（ND2〜ND1000）。晴れた日にシャッターを遅くしたい
  ときに使います。
- **ホワイトバランス** ― AWB（自動）か、色温度（2500K〜12000K）を指定。

**レンズ**
- **フォーカス** ― MF のときのピント距離。
- **フォーカスモード** ― MF ／ AF ／ MOB（→ 6章）
- **AF 速度** ― ピントが移動する速さ（SLOW ／ NORMAL ／ FAST）。
- **AF 点（矢印キー）** ― AF 点の位置。この項目を押すと中央に戻ります。
- **AF エリア** ― SPOT（AF 点ちょうど）／ ZONE（AF 点のまわりの広い範囲）。
- **センサーサイズ** ― MEDIUM（中判）／ FULL FRAME ／ APS-C ／ M4/3 ／ 1 INCH。
  小さいセンサーほど、同じ焦点距離で画角が狭くなります（同じ画角で比べると、ボケは
  小さくなります）。

**画づくり**
- **倍率色収差** ― 画面の端に出る色のにじみ。
- **フォーカスブリージング** ― ピントを動かすと画角がわずかに変わる、レンズの癖。
- **ダイナミックレンジ再現 / 幅** ― センサーが記録できる明暗の幅を再現します。
  幅を狭くするほど、白飛び・黒つぶれしやすくなります。
- **スケール** ― 1ブロックを何 cm とみなすか（→ 8章）。

**ファインダー**
- **フォーカスピーキング** ― ピントが合っている輪郭に色を付けます。ファインダーだけの
  表示で、写真には写りません。

**出力**
- **写真フォーマット** ― PNG ／ JPG ／ DNG（RAW）。どの形式にも撮影設定（EXIF）が記録
  されます。DNG は Lightroom などの現像ソフトで開けます。

右列の残り（フリーカム、絞り積分、常時被写界深度）は 9〜11章で説明します。

### 6. ピント合わせ

- **MF**（マニュアル）― **Ctrl + Alt + ホイール**で距離を合わせます。一番奥まで回すと
  無限遠。
- **AF** ― AF 点に重なっているものに合わせます。ガラスは透かして、その向こうに
  合わせます。
- **MOB** ― 画面中央の方向（5°以内）にいる一番近い生き物に合わせ続けます。動物や
  プレイヤーを追うとき用。

**AF 点**はファインダー中に**矢印キー**で動かせます。中央の近くまで戻すと吸い付きます。
構図の端にいる被写体にピントを置きたいときに使います。

ピントが合っているかは、**右上の距離計**と**フォーカスピーキング**で確かめられます。

### 7. 露出

| モード | 自分で決める | カメラが決める |
|---|---|---|
| **M** | 絞り・シャッター・ISO | なし |
| **Av**（絞り優先）| 絞り・ISO | シャッター |
| **Tv**（シャッター優先）| シャッター・ISO | 絞り |
| **P**（プログラム）| ISO | 絞り・シャッター |

- **絞り**（F値）― 小さい数字ほど明るく、ボケが大きくなります。大きくすると全体に
  ピントが合いますが、F16 を超えると回折で少しずつ眠くなります。
- **シャッター速度** ― 遅くすると明るくなり、動いているものがブレます。
- **ISO** ― 上げると明るくなり、ノイズが増えます。

露出計の針が中央に来るように合わせるのが基本です。

### 8. スケール ― ボケの大きさを決めるもの

Minecraft の1ブロックが何 cm かは決まっていません。スケールは「このワールドを何分の1で
撮るか」の設定です（1cm〜2m、初期値は **1ブロック = 37.5cm**）。

- **小さくする**（例 1〜5cm）― ジオラマを撮っているのと同じになり、ボケがとても大きく
  なります。建築を「ミニチュア写真」風に撮りたいとき。
- **大きくする**（例 1m）― 1ブロックを1mとした、人間の目線のスケールです。風景向け。

距離計の括弧の中の実寸も、このスケールで換算されます。

### 9. 絞り積分 ― いちばん綺麗な写真

右列の「絞り積分」を ON にすると、写真の撮り方が変わります。

ふつうの撮影は、1枚の画像をあとからぼかしてボケを作ります。絞り積分は、本物のレンズと
同じことをします。レンズの口径のあちこちから世界を何十回も描き直し、それを足し合わせます。
その結果、次のようになります。

- ぼけた前景の**裏側がちゃんと写ります**（柵や枝ごしの背景も、本物のボケ方をします）。
- ボケの縁のざらつきがありません。
- **シャッター速度の分だけ時間も積分されます**。遅いシャッターでは、雲や水や生き物が
  本当に流れます。

**使い方と注意**
- 写真撮影のときだけ働きます。ファインダーの表示はふつうのままです。
- シャッターを押すと、数秒かけて撮影します（64サンプルでおよそ2〜3秒）。その間は
  ファインダーが暗くなります。
- **瞳サンプル数**（8〜256、初期値 64）― 増やすほど滑らかになり、時間もかかります。
- 遅いシャッターでは、まず**シャッター速度と同じ時間だけ露光を記録**します（1秒なら1秒）。
  その間にカメラを動かすと、そのとおりにブレます。流し撮りもできます。

### 10. 常時被写界深度

カメラを構えていないふだんのプレイにも、ボケをかけられます。右列の「常時被写界深度」で
設定します。

- **常時DoF** ― ON/OFF
- **絞り / スケール** ― カメラとは別の値を持っています。カメラの設定を変えても、
  ふだんの見え方は変わりません。
- **品質** ― PERF ／ BALANCED ／ HIGH。重いと感じたら下げてください。

画面中央のものにピントが合います。背の高い草や花、ガラスは透かします。

### 11. フリーカム・ドローン・カメラパス

**フリーカム**は、体から離れて飛べるカメラです。G の設定画面の「フリーカム」ボタンで
入ります（同じボタンで終了）。フリーカム中は、常にファインダー表示になります。

- **移動** ― WASD、Space で上昇、左 Shift で下降、左 Ctrl で速く。
- **C** ― カメラをその場に固定し、プレイヤーを操作できるようにします。自分を写したい
  ときの三脚です。もう一度押すとカメラ操作に戻ります。
- **プレイヤー非表示**（設定）― 自分の体を写さないようにします。

**ドローンモード**（設定で ON）にすると、慣性のある空撮向けの操作になります。
**X** で、十字の先にあるものを中心に置くと、WASD でその周りを回れます（オービット）。

**カメラパス**は、キーフレームを置いて、その間をなめらかに飛ぶ機能です。

1. フリーカムで飛びながら、**マウス中ボタン**でその場にキーフレームを置きます。
   キーフレームを狙って中ボタンで削除、線の途中を狙うと間に挿入します。
2. **左ドラッグ**でキーフレームを動かします。ドラッグ中のホイールで奥行き、
   **左＋右ドラッグ**で向きを変えます。
3. **Z** でメニューを開き、「再生」、または**「再生+録画」**で動画にします。
   パス全体の時間、録画 FPS、再生中にピントを固定するかも、ここで設定します。

キーフレームごとの焦点距離も記録されるので、ドリーズームもできます。

### 12. 動画

**R** で動画設定を開き、「● REC」で録画を始めます。止めるときはもう一度 **R**。

- **絞り / 画角 / FPS**（24・30・60）**/ 解像度**（720p・1080p・1440p）**/
  モーションブラー**（オフ・弱・強）
- 録画中は、どんな姿勢でもホイールでズームや絞りを変えられます。
- 1本の上限は **2分**。残り1分でお知らせが出ます。
- 止めるとエンコードが始まり、終わると MP4 が保存されてクリップボードにコピーされます。
  ffmpeg が無い場合は連番 PNG で保存されます。

### 13. アルバムと保存先

G の設定画面の「**アルバム**」から、撮った写真と動画を一覧できます。

- 一覧 ― クリックで開く、ホイールでスクロール、**Ctrl + ホイール**で1行の枚数を変更。
- 表示中 ― **← / →** で送る、**Esc** で一覧へ。左側に、その写真を撮ったときの
  シャッター・F値・ISO・レンズ・モード・日時が出ます。
- ボタン ― コピー、保存先を開く、削除（確認あり）、動画は再生。

保存先（ゲームフォルダの中）:
- 写真 ― `snapmatica/photos/`
- 動画 ― `snapmatica/videos/`

ファイル名は撮影日時です。同じ秒に2枚撮ると、2枚目以降は `_2`、`_3` が付きます。

### 14. 困ったとき

- **しゃがんでもファインダーが出ない** ― **,（カンマ）**で「しゃがむとファインダー」が
  OFF になっていないか確認してください。
- **ホイールでズームできない** ― ファインダーを覗いているとき（または録画中）だけ
  効きます。
- **ピントが合わない** ― フォーカスモードが MF のままになっていないか確認してください。
- **写真が暗い／明るい** ― 露出計を見て、露出モードを P か Av にしてみてください。
- **動画が PNG の束になった** ― ffmpeg をインストールして PATH を通してください。
- **重い** ― 常時DoF の品質を下げるか OFF に。絞り積分のサンプル数を減らす。

---

## English

Snapmatica is a client-side mod for taking photographs in Minecraft the way a real camera
takes them. Look through the viewfinder, zoom, focus, set the aperture and shutter, and
shoot. Defocus, motion blur and exposure are all computed from the same formulas a real
lens follows.

### Contents
1. [Requirements](#1-requirements)
2. [Controls](#2-controls)
3. [Your first shot](#3-your-first-shot)
4. [Reading the viewfinder](#4-reading-the-viewfinder)
5. [Camera settings (G)](#5-camera-settings-g)
6. [Focusing](#6-focusing)
7. [Exposure](#7-exposure)
8. [Scale: what decides how much blurs](#8-scale-what-decides-how-much-blurs)
9. [Aperture integration: the best photos](#9-aperture-integration-the-best-photos)
10. [Ambient depth of field](#10-ambient-depth-of-field)
11. [Freecam, drone and camera paths](#11-freecam-drone-and-camera-paths)
12. [Video](#12-video)
13. [Album and where files go](#13-album-and-where-files-go)
14. [Troubleshooting](#14-troubleshooting)

---

### 1. Requirements

- **Fabric Loader** and **Fabric API** (required)
- Minecraft 1.20.1 / 1.21–1.21.1 / 1.21.2–1.21.3 / 1.21.4 / 1.21.10 / 1.21.11 / 26.1.2 / 26.3
- **Client-only.** Nothing to install on the server.
- Works together with Iris shader packs.
- On 26.3, set the graphics API in Video Settings to **OpenGL**. The blur and focus
  peaking are drawn with OpenGL and do not work under Vulkan.
- Saving video as MP4 needs **ffmpeg** on your PATH. Without it, videos are saved as
  numbered PNG frames.

### 2. Controls

Every key can be rebound under Options → Controls → Snapmatica.

| Key | Action |
|---|---|
| **Sneak** | Look through the viewfinder (while sneaking) |
| **Enter** | Shutter |
| **G** | Open camera settings |
| **V** | Switch portrait / landscape |
| **R** | Open video settings (stops recording while recording) |
| **, (comma)** | Turn "sneak to open the viewfinder" on or off |
| **Z** | Camera path menu (freecam only) |
| **X** | Drone mode: drop / lift the orbit pin |
| **C** | Freecam: hold the camera / hand control back to the player |

While you look through the viewfinder, the mouse wheel works the lens.

| Input | Action |
|---|---|
| Wheel | Zoom (8 mm – 800 mm) |
| **Ctrl** + wheel | Aperture (f/1.4 – f/22) |
| **Alt** + wheel | Shutter speed (in M / Tv) |
| **Ctrl + Alt** + wheel | Focus distance (in MF) |
| **Arrow keys** | Move the AF point |

### 3. Your first shot

1. **Sneak** to open the viewfinder. What is inside the frame is what the photo gets (3:2).
2. **Scroll** to zoom. The longer the lens, the easier the background goes soft.
3. Press **G** and set Focus Mode to **AF** (it starts on MF). Whatever sits under the
   centre cross is brought into focus.
4. Press **Enter**. The photo is saved and copied to the clipboard (except DNG), ready to
   paste into Discord or anywhere else.

The camera starts in M (manual) with aperture, shutter and ISO all manual. Switching the
exposure mode to **P** (fully automatic) or **Av** (aperture priority) is the easy start.

### 4. Reading the viewfinder

- **Top left**: the lens range, then exposure mode, focus mode and orientation
  (e.g. `Av | AF | 3:2 H`).
- **Top right**: the distance the lens is focused at (e.g. `46.2blk (46m)`, or `inf` at
  infinity). In AF that is the distance to the subject. The figure in brackets is
  converted with the scale (see 8).
- **Centre**: the AF point, over rule-of-thirds guides. When the AF point is moved, a faint
  grey mark stays where it started.
- **Bottom**: f-number, shutter speed, ISO, focal length. The scale under them is the
  **exposure meter**: the further the needle is from the centre, the brighter or darker
  than correct the shot will be.

### 5. Camera settings (G)

The left column is PHOTO (exposure and lens), the right column is CAMERA (the look and the
tools). Change values with the arrows. The buttons at the bottom lead to the **Album** and
**Freecam**.

**Exposure**
- **Aperture / Shutter / ISO**: the exposure triangle. Values the camera is setting show
  `AUTO`.
- **Exposure Mode**: M / Av / Tv / P (see 7).
- **ND Filter**: cuts the light (ND2 – ND1000). For slow shutters on a bright day.
- **White Balance**: AWB, or a colour temperature from 2500 K to 12000 K.

**Lens**
- **Focus**: the focus distance in MF.
- **Focus Mode**: MF / AF / MOB (see 6).
- **AF Speed**: how fast the focus travels (SLOW / NORMAL / FAST).
- **AF Point (arrow keys)**: where the AF point is. Press it to recentre.
- **AF Area**: SPOT (exactly the AF point) / ZONE (a wider area around it).
- **Sensor Size**: MEDIUM (medium format) / FULL FRAME / APS-C / M4/3 / 1 INCH. A smaller
  sensor gives a narrower view at the same focal length (and less background blur when
  compared at the same view).

**Image**
- **Chromatic Aberration**: colour fringes towards the edges of the frame.
- **Focus Breathing**: the slight change in framing a real lens shows as it focuses.
- **Dynamic Range / Range width**: reproduces how much light-to-dark a sensor can hold.
  A narrower range clips highlights and shadows sooner.
- **Scale**: how many centimetres one block is (see 8).

**Viewfinder**
- **Focus Peaking**: colours the edges that are in focus. Shown in the viewfinder only,
  never in the photo.

**Output**
- **Photo Format**: PNG / JPG / DNG (raw). Every format records the shot settings (EXIF).
  DNG opens in raw developers such as Lightroom.

The rest of the right column (freecam, aperture integration, ambient depth of field) is
covered in 9 – 11.

### 6. Focusing

- **MF** (manual): set the distance with **Ctrl + Alt + wheel**. Past the far end is
  infinity.
- **AF**: focuses on what is under the AF point. It looks through glass to what is behind
  it.
- **MOB**: keeps focus on the nearest living thing within 5° of the centre of the view.
  For animals and players.

Move the **AF point** with the **arrow keys** while in the viewfinder. It snaps back when
brought near the centre. Use it for a subject near the edge of the frame.

Check focus with the **rangefinder** (top right) and with **focus peaking**.

### 7. Exposure

| Mode | You set | The camera sets |
|---|---|---|
| **M** | aperture, shutter, ISO | nothing |
| **Av** (aperture priority) | aperture, ISO | shutter |
| **Tv** (shutter priority) | shutter, ISO | aperture |
| **P** (program) | ISO | aperture, shutter |

- **Aperture** (f-number): a smaller number is brighter and blurs more. A larger number
  brings more into focus, but past about f/16 diffraction slowly softens everything.
- **Shutter speed**: slower is brighter, and anything moving smears.
- **ISO**: higher is brighter and noisier.

Aim for the meter needle in the centre.

### 8. Scale: what decides how much blurs

Minecraft never says how big a block is. The scale says what you are photographing it as
(1 cm – 2 m per block; the default is **1 block = 37.5 cm**).

- **Smaller** (say 1 – 5 cm): the same as photographing a diorama. The blur becomes very
  large. Use it for "miniature" shots of builds.
- **Larger** (say 1 m): one block is a metre, the scale a person sees the world at. Good
  for landscapes.

The rangefinder's figure in brackets uses this scale too.

### 9. Aperture integration: the best photos

Turning on Aperture Integration in the right column changes how a photo is made.

A normal shot takes one picture and blurs it afterwards. Aperture integration does what a
real lens does: it draws the world again from many points across the lens opening and
adds them up. As a result:

- What sits **behind** a blurred foreground is really there, so a background seen through
  bars or branches blurs the way it would through a real lens.
- There is no grain at the edges of the blur.
- **Time is integrated over the shutter speed too.** On a slow shutter, clouds, water and
  mobs really move.

**How to use it**
- It only affects photos. The viewfinder looks the same as usual.
- After you press the shutter, the camera takes a few seconds (about 2 – 3 s at 64
  samples), and the viewfinder goes dark meanwhile.
- **Pupil samples** (8 – 256, default 64): more is smoother and slower.
- On a slow shutter, the camera first **records the exposure for as long as the shutter
  speed** (one second for 1 s). Moving the camera during that time blurs the shot the same
  way, so panning with a subject works.

### 10. Ambient depth of field

You can also have depth of field while simply playing, without the camera up. Set it under
Ambient Depth of Field in the right column.

- **Ambient DoF**: on / off.
- **Aperture / Scale**: separate from the camera's, so changing the camera does not change
  how the game normally looks.
- **Quality**: PERF / BALANCED / HIGH. Lower it if it feels heavy.

It focuses on what is in the centre of the screen, looking through tall grass, flowers and
glass.

### 11. Freecam, drone and camera paths

**Freecam** is a camera that flies free of your body. Enter it with the Freecam button in
the G settings screen (the same button leaves it). Freecam always shows the viewfinder.

- **Move**: WASD, Space up, left Shift down, left Ctrl faster.
- **C**: holds the camera where it is and hands control back to the player, like a tripod,
  so you can walk into your own shot. Press again to fly the camera.
- **Hide Player** (setting): keeps your own body out of the picture.

**Drone Mode** (a setting) makes the flight feel like an aerial drone, with inertia. Press
**X** to pin whatever is under the cross, and WASD orbits around it.

**Camera paths** fly smoothly through keyframes you place.

1. While flying in freecam, **middle-click** to drop a keyframe where you are. Middle-click
   on a keyframe to delete it, or on the line between two to insert one.
2. **Left-drag** a keyframe to move it. Scroll while dragging to push it nearer or further;
   **left + right drag** to re-aim it.
3. Press **Z** for the menu and choose Play, or **Play + Record** to make a video. The
   path's total time, the recording FPS and whether focus stays fixed during playback are
   set here too.

Each keyframe also records its focal length, so a dolly zoom is possible.

### 12. Video

Press **R** for the video settings and start with "● REC". Press **R** again to stop.

- **Aperture / Field of view / FPS** (24, 30, 60) **/ Resolution** (720p, 1080p, 1440p)
  **/ Motion blur** (off, light, strong)
- While recording you can zoom and change the aperture with the wheel, in any pose.
- One recording is at most **2 minutes**. A notice appears with one minute left.
- Stopping starts the encode. When it finishes, the MP4 is saved and copied to the
  clipboard. Without ffmpeg, numbered PNG frames are saved instead.

### 13. Album and where files go

The **Album** button in the G settings screen lists every photo and video you have taken.

- Grid: click to open, scroll to move, **Ctrl + wheel** to change how many per row.
- Viewer: **← / →** to step through, **Esc** back to the grid. On the left: the shutter,
  aperture, ISO, lens, mode and time the photo was taken with.
- Buttons: copy, open the folder, delete (asks first), and play for videos.

Where files go (inside the game folder):
- Photos: `snapmatica/photos/`
- Videos: `snapmatica/videos/`

Files are named by the time they were taken. A second shot in the same second gets `_2`,
then `_3`, and so on.

### 14. Troubleshooting

- **Sneaking does not open the viewfinder**: check that **, (comma)** has not turned
  "sneak to open the viewfinder" off.
- **The wheel does not zoom**: it only works while in the viewfinder (or recording).
- **Nothing comes into focus**: check that Focus Mode is not still on MF.
- **Photos are too dark or too bright**: watch the meter, and try exposure mode P or Av.
- **The video came out as a folder of PNGs**: install ffmpeg and put it on your PATH.
- **It feels heavy**: lower Ambient DoF quality or turn it off, and use fewer pupil
  samples for aperture integration.
