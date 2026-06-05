# Photographica — チュートリアル / Tutorial

リアルなカメラ操作を Minecraft に持ち込む Mod です。フィルムカメラ・デジタルカメラで撮影し、暗室で現像・引き伸ばしして写真を世界に飾ることができます。

A Minecraft mod that brings realistic camera simulation to the game. Shoot with film or digital cameras, develop negatives in a darkroom, print them in the enlarger, and display your photos in the world.

---

## 目次 / Contents

1. [導入要件 / Requirements](#1-導入要件--requirements)
2. [初期設定 / Keybindings](#2-初期設定--keybindings)
3. [アイテム一覧 / Item Reference](#3-アイテム一覧--item-reference)
4. [ファインダーの使い方 / Viewfinder Controls](#4-ファインダーの使い方--viewfinder-controls)
5. [露出設定 / Exposure Settings](#5-露出設定--exposure-settings)
6. [デジタルカメラ / Digital Cameras](#6-デジタルカメラ--digital-cameras)
7. [フィルムカメラ / Film Camera](#7-フィルムカメラ--film-camera)
8. [三脚撮影 / Tripod Shooting](#8-三脚撮影--tripod-shooting)
9. [ビデオカメラ / Video Camera](#9-ビデオカメラ--video-camera)
10. [写真の飾り方 / Displaying Photos](#10-写真の飾り方--displaying-photos)
11. [撮影エフェクト / Photo Effects](#11-撮影エフェクト--photo-effects)
12. [FAQ](#12-faq)

---

## 1. 導入要件 / Requirements

| | 日本語 | English |
|---|---|---|
| Minecraft | 1.21.1 / 1.21.4 / 1.21.11 / 26.1 | 1.21.1 / 1.21.4 / 1.21.11 / 26.1 |
| Mod ローダー | Fabric Loader ≥ 0.16.0 | Fabric Loader ≥ 0.16.0 |
| 必須依存 | [Fabric API](https://modrinth.com/mod/fabric-api) | [Fabric API](https://modrinth.com/mod/fabric-api) |
| インストール | クライアント・サーバー両方 | Both client and server |

> **シェーダー対応** — Iris + Photon での動作確認済み。シェーダー使用時もエフェクトが正しく合成されます。  
> **Shader support** — Tested with Iris + Photon. Effects are correctly composited in the captured PNG even with shaders active.

---

## 2. 初期設定 / Keybindings

**オプション → 操作設定 → Photographica** からキーを割り当ててください。  
Go to **Options → Controls → Photographica** to assign the following keys.

| 操作 / Action | 用途 / Notes |
|---|---|
| カメラ設定を開く / Open camera settings | 全カメラ必須 / Required for all cameras |
| フィルム巻き上げ / Wind film | フィルムカメラのみ / Film camera only |
| SDカード装填 / Load SD card | デジタルのみ / Digital cameras only |
| SDカード取り出し / Unload SD card | デジタルのみ / Digital cameras only |

---

## 3. アイテム一覧 / Item Reference

### カメラ / Cameras

| アイテム / Item | 日本語 | English |
|---|---|---|
| **デジタルSLR** | デジタル撮影。SDカード保存。光学ファインダー（EVFなし） | Digital shooting, SD card storage. Optical viewfinder (no EVF) |
| **ミラーレスカメラ** | デジタル撮影。SDカード保存。EVFでリアルタイム露出・ボケプレビュー | Digital shooting, SD card storage. EVF with live exposure and DoF preview |
| **フィルムカメラ** | フィルムを装填して撮影。暗室で現像が必要 | Film rolls required. Development needed before viewing |
| **ビデオカメラ** | 動画撮影（FFmpeg 必要） | Video recording (requires FFmpeg) |

> **デジタルSLR vs ミラーレス** — 操作は同じ。EVF（リアルタイム露出・DoFプレビュー）はミラーレスのみ。  
> **Digital SLR vs Mirrorless** — Identical controls. Only the Mirrorless shows a live EVF preview of exposure and depth-of-field.

### レンズ / Lenses

| レンズ / Lens | 焦点距離 / FL | 日本語 | English |
|---|---|---|---|
| 単焦点 14mm | 14mm | 超広角。建築・風景 | Ultra-wide. Architecture, landscapes |
| 単焦点 35mm | 35mm | 広角スナップ | Wide-angle snapshots |
| 単焦点 50mm | 50mm | 標準。自然な画角 | Standard. Most natural field of view |
| 単焦点 85mm | 85mm | ポートレート。背景ボケ美しい | Portraits. Beautiful background blur |
| マクロ 100mm | 100mm | 近距離撮影特化 | Close-up / detail shots |
| ズーム 24-70mm | 24〜70mm | 汎用ズーム | General-purpose zoom |
| ズーム 70-200mm | 70〜200mm | 望遠。遠景・野生動物 | Telephoto. Distant subjects, wildlife |

> 14mm ≈ 104°広角 → 50mm ≈ 27°標準 → 200mm ≈ 6.9°望遠 (35mm フルサイズ換算 / 35mm full-frame equivalent)

### フィルム / Film

| フィルム / Film | ISO | コマ数 / Frames | 日本語 | English |
|---|---|---|---|---|
| カラー 400 | 400 | 36 | 汎用。暖かみのある発色 | All-purpose. Warm tones |
| カラー 100 | 100 | 36 | 低感度・低ノイズ。澄んだ色調 | Fine grain, low noise. Clean colour |
| カラー 1600 | 1600 | 36 | 高感度。粒状感が強い | High grain. Push-processed look |
| モノクロ 400 | 400 | 36 | 白黒。コントラスト強め | Monochrome. High contrast |
| カラー 400 (24枚) | 400 | 24 | コマ数少なめ版 | Shorter roll |

### その他アイテム・ブロック / Other Items & Blocks

| 名称 / Name | 日本語 | English |
|---|---|---|
| **SDカード** | デジタルカメラ用ストレージ。デフォルト **64枚** まで | Storage for digital cameras — **64 photos** default |
| **現像液タンク** | 暗室で使用。32本分 | Darkroom chemistry. Develops 32 rolls |
| **写真用紙** | 引き伸ばし機でプリントに必要 | Required for printing in the enlarger |
| **写真** | 完成プリント。フレーム・立てに飾れる | Finished print — displayable in frames/stands |
| **露出済みフィルム** | 撮影済み・未現像のフィルム | Shot but undeveloped film |
| **現像済みネガ** | 現像後のネガ。引き伸ばし機でプリントへ | After darkroom — ready to print |
| **暗室** (ブロック) | フィルムを現像する | Develops exposed film |
| **引き伸ばし機** (ブロック) | ネガからプリント作成 | Prints negatives onto photo paper |
| **写真フレーム** (ブロック) | 壁に設置して飾る | Wall-mounted photo display |
| **写真立て** (ブロック) | 床に置いて飾る | Floor-standing photo display |
| **プリンター** (ブロック) | SDカードから直接プリント | Prints directly from an SD card |

---

## 4. ファインダーの使い方 / Viewfinder Controls

カメラを持った状態で **Shift（スニーク）を押し続ける**とファインダーモードになります。  
**Hold Shift (sneak)** while holding a camera to enter viewfinder mode.

```
┌─────────────────────────────────┐
│ 35mm          EVF MAIN          │
│ Av | AF                         │
│                                 │
│          [  ＋  ]               │   ← フォーカスレティクル / Focus reticle
│                                 │
│ F5.6 · 1/60 · ISO400 · 50mm     │
│         ─┬─┬─┬─│─┬─┬─┬─        │   ← 露出計 / Exposure meter
│          -3  -1  0  +1  +3      │
└─────────────────────────────────┘
```

### スクロール操作 / Scroll Controls

| キー / Key | 操作 / Action |
|---|---|
| スクロール / Scroll | ズーム *(ズームレンズのみ / zoom lenses only)* |
| Ctrl + スクロール | 絞り（F値）/ Aperture |
| Alt + スクロール | シャッタースピード / Shutter speed |
| Ctrl + Alt + スクロール | MF ピント距離 / Manual focus distance |

### 露出計 / Exposure Meter

- **中央（0）/ Centre** — 基準露出（F5.6·1/60·ISO400）でニュートラル / Neutral at reference exposure
- **右（＋）/ Right** — 露出オーバー / Overexposed
- **左（−）/ Left** — 露出アンダー / Underexposed
- 🟢 **緑 / Green** — ±2 EV 以内（良好 / good）
- 🔴 **赤 / Red** — ±2 EV 超（大幅ずれ / significant deviation）

Av/Tv/P モードでは正しく収束すると中央付近になります。  
In Av/Tv/P mode the needle centres when auto-exposure has converged.

### フォーカスレティクル / Focus Reticle

| 色 / Colour | 意味 / Meaning |
|---|---|
| 緑 / Green | 合焦 / In focus |
| 黄 / Yellow | やや外れ / Slightly off |
| 赤 / Red | 大幅ずれ / Out of focus |
| 白 / White | レンズ未装着 / No lens attached |

---

## 5. 露出設定 / Exposure Settings

### 露出モード / Exposure Modes

| モード | 日本語 | English |
|---|---|---|
| **M** | 絞り・SS すべて手動 | Fully manual |
| **Av** | 絞り固定、SS をオート | Lock aperture, auto shutter |
| **Tv** | SS 固定、絞りをオート | Lock shutter, auto aperture |
| **P** | 絞り・SS 両方オート（F5.6基準） | Full auto. Defaults to F5.6 |

### フォーカスモード / Focus Modes

| モード | 日本語 | English |
|---|---|---|
| **MF** | Ctrl+Alt+スクロールで手動 | Manual — Ctrl+Alt+Scroll |
| **AF** | 画面中央の距離に自動スナップ | Snaps to scene depth at frame centre |
| **MOB** | 前方5°コーン内の最近エンティティに追尾 | Tracks nearest entity in a 5° forward cone |

### 絞りと被写界深度 / Aperture and Depth of Field

```
f/1.4  ←  背景ボケ強い / Strong bokeh (shallow DoF)
f/5.6  ←  バランス点 / Balanced
f/16   ←  回折ソフトネス / Diffraction softening begins
f/22   ←  全体にピント / Deep DoF (everything sharp)
```

> DoF ブラーは **F5.6 以下**でのみ発生。F8 以上では出ません。  
> Depth-of-field blur only fires at **f/5.6 or wider**. No bokeh at f/8+.

### シャッタースピードとブレ / Shutter Speed and Motion Blur

| SS | 日本語 | English |
|---|---|---|
| 1/250 以上 | 動体も止まる | Freezes motion |
| 1/60 | 基準。手ブレしない | Reference. Safe handheld |
| 1/30 以下 | 手ブレシミュレーション発生 | Hand-shake blur simulation |
| 1s 以上 | 長時間露光。光跡が写る | Long-exposure blend. Light trails visible |

> 三脚（防具立て）撮影では長時間露光でも手ブレは発生しません。  
> Tripod (armor stand) shots are always shake-free, even at slow shutters.

---

## 6. デジタルカメラ / Digital Cameras

### 撮影手順 / Shooting Workflow

```
カメラを持つ / Hold camera
  ↓
設定キー → レンズ装着・露出設定 / Settings key → attach lens, set exposure
  ↓
SDカード装填 / Load SD card
  ↓
Shift長押し → ファインダーモード / Hold Shift → viewfinder
  ↓
右クリック → 撮影 / Right-click → shoot
  ↓
.minecraft/photographica/photos/ に PNG 保存 / PNG saved
```

### SDカードブラウザ / SD Card Browser

設定画面の **SDカード（n枚）** ボタンで開きます。  
Press **SD Card (n)** in the settings screen.

- サムネイル表示・全画面表示 / Thumbnail and full-screen view
- 撮影メタデータ確認（F値・SS・ISO・焦点距離・座標）/ Metadata display
- 1枚ずつ削除（ディスクのファイルも削除）/ Per-photo deletion (removes PNG from disk)

### SDカード容量 / SD Card Capacity

デフォルト **64枚** まで保存できます。満杯になると撮影できなくなります。  
Holds up to **64 photos** by default. Shooting is blocked when full.  
こまめにプリントするか不要な写真を削除してください。  
Print or delete photos regularly to free space.

---

## 7. フィルムカメラ / Film Camera

### 撮影〜プリントまでの流れ / Complete Workflow

```
フィルム装填 / Load film
  ↓
撮影 → 巻き上げ → … × 36コマ / Shoot → Wind → repeat × 36
  ↓
フィルム取り出し（露出済みフィルム）/ Unload (Exposed Film item)
  ↓
暗室 + 現像液タンク / Darkroom block + Developer Tank
  ↓
現像済みネガ / Developed Negative  ← 右クリックでプレビュー / right-click to preview
  ↓
引き伸ばし機 + 写真用紙 / Enlarger + Photo Paper
  ↓
写真アイテム（飾れる）/ Photo item (displayable)
```

### フィルム装填 / Loading Film

設定画面の **フィルム装填** ボタンでインベントリ内のフィルムを選択します。  
Open the settings screen and click **Load Film** to select a roll from your inventory.

### 巻き上げ / Winding

シャッターを切った後は **フィルム巻き上げキー** を押さないと次のコマが撮れません。  
After each shot, press the **Wind Film** key before you can shoot again.

### ネガのプレビュー / Negative Preview

現像済みネガを右クリックすると NEGATIVE 画面で各コマを確認できます。  
Right-click a developed negative to browse frames as inverted thumbnails.

### ⚠ 光被り / Light Fogging

フィルムの**取り出し・装填操作**を**明るさ8以上**の場所で行うと、撮影済みコマすべてが白く飛びます。  
Loading or unloading film while standing in **light level 8 or above** fogs every exposed frame white.

フィルムの着脱は必ず **明るさ7以下の暗所** か **ポータブル暗室** 内で行ってください。インベントリや箱の中に入れるだけでは不十分です。  
Always load/unload film at **light level ≤ 7** or inside a **portable darkroom**. Storing film in your inventory or a chest while standing in daylight is **not safe**.

---

## 8. 三脚撮影 / Tripod Shooting

**防具立て（アーマースタンド）にカメラを持たせ**、右クリックすることで三脚撮影ができます。  
Place a camera in an **armor stand's hand** and right-click it to shoot from its perspective.

### 手順 / Steps

```
1. 防具立てを設置・向きを決める / Place armor stand, aim it at your subject
2. カメラ（＋レンズ）を防具立てに持たせる / Give camera + lens to the armor stand
3. 防具立てを右クリック → 設定画面 / Right-click armor stand → settings open
4. 露出・フォーカス設定 → シャッター / Set exposure & focus → press shutter
5. 防具立ての視点から撮影される / Photo taken from the stand's exact position
```

### 特徴 / Features

| | 日本語 | English |
|---|---|---|
| 視点 | プレイヤーではなく防具立ての位置・向き | Stand's exact position and orientation |
| 手ブレ | なし（どんな SS でも） | None — stable at any shutter speed |
| 長時間露光 | 夜景・光跡撮影に最適 | Ideal for night scenes and light trails |
| AF | 防具立ての向きから自動でピント合わせ | Auto-focuses from the stand's eye position |

---

## 9. ビデオカメラ / Video Camera

**FFmpeg** がシステム PATH にインストールされている必要があります。  
**FFmpeg** must be installed and available on your system PATH.

### 操作 / Controls

| キー / Key | 操作 / Action |
|---|---|
| 右クリック / Right-click | 録画開始・停止 / Start / stop recording |
| スクロール / Scroll | ズーム / Zoom |

録画中は画面右上に赤い録画インジケーターが表示されます。  
A red recording indicator appears in the top-right corner while recording.

保存先 / Saved to: `.minecraft/photographica/videos/`

---

## 10. 写真の飾り方 / Displaying Photos

### 写真フレーム（壁掛け）/ Photo Frame (wall)

壁ブロックに向かって設置。写真アイテムを右クリックで入れると画像が表示されます。  
Place against a wall, then right-click with a Photo item to display it.

### 写真立て（床置き）/ Photo Stand (floor)

床に向かって設置。右クリックで写真を入れます。  
Place on the floor, right-click with a Photo item.

### 出し入れ / Inserting and Removing

右クリック → 写真を設置 / スニーク + 右クリック → 取り出し  
Right-click to insert · Sneak + right-click to remove

> 写真は PNG ファイルと紐付いています。ファイルを削除すると額縁の表示も消えます。マルチプレイでは各プレイヤーの `photographica/photos/` フォルダに PNG が必要です。  
> Photos are linked to PNG files. Deleting the file blanks the frame. In multiplayer each player needs the PNG in their own `photographica/photos/` folder.

---

## 11. 撮影エフェクト / Photo Effects

以下のエフェクトは**撮影時に PNG に焼き込まれます**（リアルタイム表示とは独立）。  
All effects are **baked into the PNG at capture time**, independent of real-time rendering.

| エフェクト / Effect | 条件 / Condition | 内容 / Description |
|---|---|---|
| 露出スケーリング / Exposure | 常時 / Always | F値・SS・ISO から明るさ計算。ハイライトロールオフ付き / Brightness from f/SS/ISO. Highlight roll-off |
| ビネット / Vignetting | 常時 / Always | 絞りに応じて周辺減光。F1.4 で最大 / Corner darkening. Strongest at f/1.4 |
| ISO ノイズ / ISO Noise | 常時 / Always | 輝度ノイズ + 高ISO時カラーノイズ / Luminance + chroma noise at high ISO |
| フィルムグレイン / Film Grain | フィルム時 / Film only | フィルム種別ごとの粒状感 / Per-stock grain texture |
| フィルムトーン / Film Tone | フィルム時 / Film only | カラー暖色・カラー100クール・モノクロ高コントラスト / Warm/cool/high-contrast per stock |
| 被写界深度ブラー / DoF Blur | F5.6 以下 & レンズあり | 深度バッファ使用。境界ブリードなし / Depth-buffer bokeh, no colour bleed |
| 手ブレブラー / Shake Blur | SS ≥ 1/30 & 手持ち / handheld | カメラシェイク。三脚撮影では無効 / Camera shake. Disabled for tripod shots |
| 長時間露光合成 / Long Exposure | SS > 1/30 | 複数フレーム加算平均。光跡 / Multi-frame blend. Light trails |
| 回折ソフトネス / Diffraction | F16 以上 / f/16+ | 全体に軽いボックスブラー / Gentle box blur across the frame |
| 相反則不軌 / Reciprocity Failure | フィルム & SS ≥ 1s | 長時間露光での感度低下 / Film sensitivity loss at long exposures |
| 光被り / Light Fogging | 明るさ≥8 の場所でフィルム着脱 / Film loaded/unloaded at light ≥ 8 | 全コマ白く飛ぶ / All frames washed out |

---

## 12. FAQ

**Q: 写真が黒すぎる / 白すぎる — Photo too dark or bright**  
→ 露出計を確認。Av/Tv/P ならファインダーを開いたまま少し待つとオート収束します。M モードは Ctrl/Alt スクロールで調整。  
→ Check the exposure meter. In Av/Tv/P, open the viewfinder and wait a moment for auto-exposure to converge. In M, use Ctrl/Alt+Scroll.

**Q: 被写界深度ブラーが出ない — No depth-of-field blur**  
→ DoF ブラーは F5.6 以下でのみ発生します。F8 以上には出ません。  
→ DoF blur only fires at f/5.6 or wider. No bokeh at f/8+.

**Q: 「ファイルが見つかりません」と表示される — "Photo file not found"**  
→ `.minecraft/photographica/photos/` を確認。マルチプレイでは各自のフォルダに PNG が必要です。  
→ Check `.minecraft/photographica/photos/`. In multiplayer each player needs their own copy of the PNG.

**Q: 現像したのに写真が全部白い — Developed film all white**  
→ フィルムの取り出し・装填を**明るさ8以上**の場所で行うと光被りが発生します。**明るさ7以下の暗所**か**ポータブル暗室**内で着脱してください。  
→ Fogging occurs when loading/unloading film at **light level ≥ 8**. Always do film changes at **light level ≤ 7** or inside a **portable darkroom**.

**Q: ネガプレビューが「NO FILE」 — Negative shows "NO FILE"**  
→ `photographica/photos/` に PNG があるか確認。現像直後は 2 秒ほど待つと自動で再読み込みされます。  
→ Check the PNG exists in `photographica/photos/`. After developing, wait ~2 seconds — the cache retries automatically.

**Q: 動画撮影で「FFmpeg が見つかりません」 — "FFmpeg not found"**  
→ [https://ffmpeg.org/](https://ffmpeg.org/) からダウンロードしてシステムの PATH に追加してください。  
→ Download from [https://ffmpeg.org/](https://ffmpeg.org/) and add it to your system PATH.

---

## ファイル保存場所 / File Locations

```
.minecraft/
└── photographica/
    ├── photos/     ← 写真 PNG / Photo PNGs  (yyyy-MM-dd_HH-mm-ss_<uuid>.png)
    └── videos/     ← 動画 mp4 / Video MP4s
```

---

*Photographica v0.2.1 — Fabric / Minecraft 1.21.1, 1.21.4, 1.21.11, 26.1*
