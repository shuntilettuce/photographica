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
- Film-stock tone curves (Kodacolor 400, Kodacolor 100, Kodacolor 1600, B&W 400)
- Highlight rolloff and film reciprocity failure

### Film Workflow
- Five film stocks: Colour 400, Colour 100, Colour 1600, B&W 400, Colour 400 (24 exp.)
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

*日本語*

フィルムカメラ・ミラーレスデジタルカメラを使って撮影できるModです。絞り・シャッタースピード・ISO・焦点距離・フォーカスモードなどリアルカメラの操作感を再現。フィルムカメラは現像・引き伸ばしワークフローに対応。デジタルはSDカード方式でブラウザから写真管理が可能です。シェーダー（Iris + Photon動作確認済み）にも対応しています。
