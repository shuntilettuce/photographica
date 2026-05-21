# Photographica

A photography mod that brings realistic camera simulation to Minecraft. Take photos with film or digital cameras, develop your negatives in a darkroom, and print photographs to display in your world.

---

## Features

### Camera Types
- **Film SLR** — Load film rolls, wind between shots, develop negatives in the darkroom, then print in the enlarger
- **Mirrorless Digital** — Insert an SD card and shoot immediately; browse and delete photos from the in-game SD card viewer

### Optics & Exposure
- **Interchangeable lenses** — Prime and zoom lenses with real focal lengths (24 mm – 200 mm)
- **Full exposure control** — Aperture (f/1.4–f/22), shutter speed (30 s – 1/4000 s), ISO
- **Exposure modes** — Manual (M), Aperture priority (Av), Shutter priority (Tv), Program (P)
- **Focus modes** — Manual focus, Auto focus, Moving-object tracking (MOB)
- **Viewfinder** — Sneak to look through the camera with correct field-of-view

### Photographic Effects (applied at capture time)
- Depth-of-field blur (depth-aware, no colour bleed at boundaries)
- Motion blur on slow shutter speeds
- ISO grain and chroma noise
- Lens vignetting by aperture
- Diffraction softening at f/16+
- Film-stock tone curves (Colour ISO 400, Colour ISO 100, Colour ISO 1600, B&W ISO 400)
- Highlight rolloff and film reciprocity failure

### Film Workflow
- Five film stocks: Colour ISO 400 (36 exp.), Colour ISO 100 (36 exp.), Colour ISO 1600 (36 exp.), B&W ISO 400 (36 exp.), Colour ISO 400 (24 exp.)
- Develop exposed rolls in the Darkroom block
- Print negatives in the Enlarger block to get displayable Photo items
- Preview developed negatives as inverted thumbnails by right-clicking the film roll

### Digital Workflow
- Photos saved as PNG files in `.minecraft/photographica/photos/`
- SD card browser with thumbnail preview, metadata display, and per-photo deletion
- Shader-compatible capture (tested with Iris + Photon)

---

## Requirements

- Minecraft **1.21.1**
- Fabric Loader **≥ 0.16.0**
- Fabric API

Both client and server must have the mod installed for multiplayer.

---

## Notes

Photos are stored as PNG files on the **client** machine. Deleting a photo in-game also removes the file from disk.

---

## How to Use

### First-time Setup

Open **Options → Controls → Photographica** and bind the following keys:

| Action | Default | Notes |
|--------|---------|-------|
| Open camera settings | *(unbound)* | Required |
| Wind film | *(unbound)* | Film camera only |
| Load SD card | *(unbound)* | Digital camera only |
| Unload SD card | *(unbound)* | Digital camera only |

---

### Mirrorless Digital Camera

1. **Prepare** — Hold the camera and press the settings key to open the settings screen. Attach a lens and adjust aperture, shutter speed, and ISO.
2. **Load SD card** — Press the load SD card key while holding the camera (or manage it from the settings screen).
3. **Compose** — Hold **Shift** to enter viewfinder mode. The field of view changes with the attached lens.
   - **Scroll** — Zoom (zoom lenses only)
   - **Ctrl + Scroll** — Aperture
   - **Alt + Scroll** — Shutter speed
   - **Ctrl + Alt + Scroll** — Manual focus distance
4. **Shoot** — **Right-click** to take a photo. The image is saved as a PNG to `.minecraft/photographica/photos/`.
5. **Browse photos** — Open the settings screen and click **SD Card (n)** to view thumbnails, check metadata, and delete photos.
6. **Print** — Place an **SD Printer** block. Insert the SD card and paper, then activate the block to produce displayable Photo items.

---

### Film SLR Camera

1. **Prepare** — Press the settings key to open the settings screen. Attach a lens and adjust exposure. Click **Load Film** to load a film roll from your inventory.
2. **Compose** — Hold **Shift** to enter viewfinder mode. The frame counter and winding indicator are shown in the top-right corner.
   - Same scroll controls as the digital camera apply.
3. **Shoot** — **Right-click** to release the shutter.
4. **Wind** — Press the **Wind Film** key after each shot before you can shoot again.
5. **Repeat** steps 3–4 until the roll is fully exposed.
6. **Unload** — Open the settings screen and click **Unload Film**.
7. **Develop** — Place a **Darkroom** block and insert the exposed roll to produce a developed negative.
   - Right-click a developed negative to preview the shots as inverted thumbnails.
8. **Print** — Place an **Enlarger** block and insert the negative to produce displayable Photo items.

---

## 使い方

### 初期設定

**オプション → 操作設定 → Photographica** から以下のキーを割り当ててください。

| 操作 | デフォルト | 備考 |
|------|-----------|------|
| カメラ設定を開く | *(未割り当て)* | 必須 |
| フィルム巻き上げ | *(未割り当て)* | フィルムカメラのみ |
| SDカード装填 | *(未割り当て)* | デジタルカメラのみ |
| SDカード取り出し | *(未割り当て)* | デジタルカメラのみ |

---

### ミラーレスデジタルカメラ

1. **準備** — カメラを持ち、設定キーで設定画面を開く。レンズを装着し、絞り・シャッタースピード・ISOを設定。
2. **SDカード装填** — SDカード装填キーを押す（または設定画面から操作）。
3. **構図を決める** — **Shift長押し**でファインダーモードへ。装着レンズに応じて画角が変わる。
   - **スクロール** — ズーム（ズームレンズのみ）
   - **Ctrl + スクロール** — 絞り
   - **Alt + スクロール** — シャッタースピード
   - **Ctrl + Alt + スクロール** — MFピント距離
4. **撮影** — **右クリック**でシャッターを切る。`.minecraft/photographica/photos/` にPNGで保存される。
5. **写真の確認** — 設定画面の **SDカード（n枚）** ボタンからサムネイル・メタデータの確認・削除ができる。
6. **プリント** — **SDプリンター**ブロックにSDカードと紙をセットして起動すると、飾れるフォトアイテムが作成される。

---

### フィルムSLRカメラ

1. **準備** — 設定キーで設定画面を開く。レンズを装着し、露出を設定。**フィルム装填**ボタンでインベントリからフィルムを装填。
2. **構図を決める** — **Shift長押し**でファインダーモードへ。右上にコマ数・巻き上げ状態が表示される。
   - スクロール操作はデジタルカメラと同じ。
3. **撮影** — **右クリック**でシャッターを切る。
4. **巻き上げ** — 撮影後に**フィルム巻き上げキー**を押す。次のコマを撮るために必要。
5. フィルムがなくなるまで 3〜4 を繰り返す。
6. **フィルム取り出し** — 設定画面で**フィルム取り出し**を選ぶ。
7. **現像** — **暗室**ブロックに撮影済みフィルムを入れると現像済みネガになる。
   - 現像済みネガを右クリックすると反転サムネイルでプレビューできる。
8. **引き伸ばし** — **引き伸ばし機**ブロックにネガを入れると飾れるフォトアイテムが作成される。

---

## Development

This mod was developed with the assistance of [Claude Code](https://claude.ai/code) (Anthropic's AI coding assistant). The game design, creative direction, and all final decisions were made by the author.

---

*日本語*

フィルムカメラ・ミラーレスデジタルカメラを使って撮影できるModです。絞り・シャッタースピード・ISO・焦点距離・フォーカスモードなどリアルカメラの操作感を再現。フィルムカメラは現像・引き伸ばしワークフローに対応。デジタルはSDカード方式でブラウザから写真管理が可能です。シェーダー（Iris + Photon動作確認済み）にも対応しています。
