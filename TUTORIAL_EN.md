# Photographica Tutorial

A photography mod for Minecraft. Shoot with film or digital cameras, develop negatives in a darkroom, print them in the enlarger, and display your photos in the world.

---

## Table of Contents

1. [Requirements](#1-requirements)
2. [First-time Setup — Keybindings](#2-first-time-setup--keybindings)
3. [Item Reference](#3-item-reference)
4. [Viewfinder Controls (All Cameras)](#4-viewfinder-controls-all-cameras)
5. [Exposure Settings in Detail](#5-exposure-settings-in-detail)
6. [Digital Cameras (SLR & Mirrorless)](#6-digital-cameras-slr--mirrorless)
7. [Film SLR Camera](#7-film-slr-camera)
8. [Tripod Shooting (Armor Stand)](#8-tripod-shooting-armor-stand)
9. [Video Camera](#9-video-camera)
10. [Displaying Photos](#10-displaying-photos)
11. [Photographic Effects Reference](#11-photographic-effects-reference)
12. [Tips & FAQ](#12-tips--faq)

---

## 1. Requirements

| | |
|---|---|
| Minecraft | 1.21.1 / 1.21.4 / 1.21.11 / 26.1 |
| Mod loader | Fabric Loader ≥ 0.16.0 |
| Required dependency | [Fabric API](https://modrinth.com/mod/fabric-api) |
| Installation | Install on **both client and server** for multiplayer |

> **Shader support** — Tested with Iris + Photon. All photographic effects are correctly composited in the captured PNG even when shaders are active.

---

## 2. First-time Setup — Keybindings

Go to **Options → Controls → Photographica** and assign the following keys.

| Action | Notes |
|--------|-------|
| Open camera settings | Required for all cameras |
| Wind film | Film camera only |
| Load SD card | Digital cameras only |
| Unload SD card | Digital cameras only |

---

## 3. Item Reference

### Cameras

| Item | Description |
|------|-------------|
| **Digital SLR** | Digital shooting, saves to SD card. Optical viewfinder (no EVF) |
| **Mirrorless Camera** | Digital shooting, saves to SD card. Electronic viewfinder with real-time exposure and DoF preview |
| **Film Camera** | Shoot with film rolls. Development required before viewing |
| **Video Camera** | Records video. Saved as MP4 (requires FFmpeg) |

> **Digital SLR vs Mirrorless** — Identical operation. Only the Mirrorless shows a live EVF preview of exposure and depth-of-field through the viewfinder.

### Lenses

| Lens | Focal length | Best for |
|------|-------------|----------|
| Prime 14mm | 14mm | Ultra-wide — architecture, landscapes |
| Prime 35mm | 35mm | Wide-angle snapshots |
| Prime 50mm | 50mm | Standard — most natural field of view |
| Prime 85mm | 85mm | Portraits — beautiful background blur |
| Macro 100mm | 100mm | Close-up / detail shots |
| Zoom 24–70mm | 24–70mm | General-purpose zoom |
| Zoom 70–200mm | 70–200mm | Telephoto — distant subjects, wildlife |

> **Field-of-view guide** (35mm full-frame equivalent)  
> 14mm ≈ 104° wide → 50mm ≈ 27° standard → 200mm ≈ 6.9° telephoto

### Film

| Film | ISO | Frames | Character |
|------|-----|--------|-----------|
| Colour 400 | 400 | 36 | All-purpose. Warm tones |
| Colour 100 | 100 | 36 | Fine grain, low noise. Clean colour |
| Colour 1600 | 1600 | 36 | High grain. Push-processed look |
| B&W 400 | 400 | 36 | Monochrome. High contrast |
| Colour 400 (24 exp.) | 400 | 24 | Shorter roll |

### Other Items & Blocks

| Name | Purpose |
|------|---------|
| **SD Card** | Storage for digital cameras — holds up to **64 photos** by default |
| **Developer Tank** | Used in the darkroom. Enough chemistry to develop 32 rolls |
| **Photo Paper** | Required for printing in the enlarger |
| **Photo** | Finished print — can be placed in frames or stands |
| **Exposed Film** | Shot but undeveloped film roll |
| **Developed Negative** | After darkroom processing — ready to print |
| **Darkroom** (block) | Develops exposed film rolls |
| **Enlarger** (block) | Prints negatives onto photo paper |
| **Photo Frame** (block) | Wall-mounted photo display |
| **Photo Stand** (block) | Floor-standing photo display |
| **Printer** (block) | Prints photos directly from an SD card |

---

## 4. Viewfinder Controls (All Cameras)

**Hold Shift (sneak)** while holding a camera to enter viewfinder mode.

```
┌─────────────────────────────────┐
│ 35mm          EVF MAIN          │
│ Av | AF                         │
│                                 │
│          [  ＋  ]               │   ← Focus reticle (green = in focus)
│                                 │
│                                 │
│ F5.6 · 1/60 · ISO400 · 50mm     │
│         ─┬─┬─┬─│─┬─┬─┬─        │   ← Exposure meter
│          -3  -1  0  +1  +3      │
└─────────────────────────────────┘
```

### Scroll Controls

| Key combination | Action |
|-----------------|--------|
| Scroll | Zoom *(zoom lenses only)* |
| Ctrl + Scroll | Change aperture (f-number) |
| Alt + Scroll | Change shutter speed |
| Ctrl + Alt + Scroll | Manual focus distance |

### Reading the Exposure Meter

The horizontal scale at the bottom of the viewfinder is the light meter.

- **Centre (0)** — Neutral relative to the reference exposure (F5.6 · 1/60 · ISO 400)
- **Right (+)** — Overexposed (too bright)
- **Left (−)** — Underexposed (too dark)
- **Green needle** — Within ±2 EV (good)
- **Red needle** — Beyond ±2 EV (significant deviation)

In Av / Tv / P modes the auto-exposure drives the needle toward centre.

### Focus Reticle Colours

| Colour | Meaning |
|--------|---------|
| Green | In focus (subject within depth of field) |
| Yellow | Slightly off |
| Red | Significantly out of focus |
| White | No lens attached |

---

## 5. Exposure Settings in Detail

### Exposure Modes

| Mode | Description |
|------|-------------|
| **M** (Manual) | You set both aperture and shutter speed |
| **Av** (Aperture priority) | Lock aperture; camera auto-sets shutter speed |
| **Tv** (Shutter priority) | Lock shutter speed; camera auto-sets aperture |
| **P** (Program) | Camera auto-sets both. Defaults to F5.6 |

### Focus Modes

| Mode | Description |
|------|-------------|
| **MF** (Manual Focus) | Use Ctrl+Alt+Scroll to set focus distance |
| **AF** (Auto Focus) | Snaps focus to the scene depth at the frame centre |
| **MOB** (Subject Tracking) | Locks focus on the nearest living entity within a 5° forward cone |

### Aperture (f-number) and Depth of Field

```
f/1.4  ←  strong background blur (shallow DoF)
f/2.0
f/2.8
f/4.0
f/5.6  ←  balanced blur and sharpness
f/8
f/11
f/16   ←  diffraction softening begins (slight overall softness)
f/22   ←  deep DoF (everything sharp)
```

> Depth-of-field blur only renders at **f/5.6 or wider**. f/8 and above produce effectively no bokeh.

### Shutter Speed and Motion Blur

| Shutter speed | Effect |
|---------------|--------|
| 1/250 or faster | Freezes motion |
| 1/60 | Reference. Safe for handheld shooting |
| 1/30 or slower | Hand-camera shake simulation begins |
| 1 s or longer | Long-exposure accumulation (multiple frames blended). Light trails visible |

> **Tripod shooting** (armor stand) — no hand-shake blur, even at slow shutters.

### ISO and Noise

| ISO | Character |
|-----|-----------|
| 100 | Low noise — bright conditions |
| 400 | All-purpose |
| 1600 | High grain — low light |

---

## 6. Digital Cameras (SLR & Mirrorless)

### Shooting Workflow

```
1. Hold the camera
2. Open settings key → attach lens → set exposure
3. Press Load SD Card key to insert an SD card
4. Hold Shift → viewfinder mode
5. Right-click → take photo
6. Saved as PNG to .minecraft/photographica/photos/
```

### SD Card Browser

Press the **SD Card (n)** button in the settings screen to open the browser:

- Thumbnail grid and full-screen view
- Metadata display (aperture, SS, ISO, focal length, coordinates)
- Per-photo deletion (also removes the PNG file from disk)

### SD Card Capacity

Each SD card holds up to **64 photos** by default. When full, shooting is blocked. Delete unwanted photos via the browser, or print and clear them to make room. Cards can be moved between cameras.

---

## 7. Film SLR Camera

### Complete Workflow

```
Load film → Shoot → Wind → ... → Roll exposed
      ↓
Unload film (gives Exposed Film item)
      ↓
Darkroom block + Developer Tank
      ↓
Developed Negative  ← (right-click to preview as inverted thumbnails)
      ↓
Enlarger block + Photo Paper
      ↓
Photo item (displayable)
```

### Loading Film

Open the settings screen and click **Load Film** to load a roll from your inventory. The film stock is locked at this point.

### Winding

After each shot, press the **Wind Film** key before you can shoot again. The winding indicator in the top-right of the viewfinder shows whether the camera is wound.

### Negative Preview

Right-click a developed negative to open the NEGATIVE screen and browse each frame as an inverted (negative) thumbnail.

> **Light fogging** — Loading or unloading film while standing in **light level 8 or above** fogs every exposed frame white. Always load/unload film in a **light level ≤ 7** location or inside a **portable darkroom**. Simply keeping film in your inventory is not enough if you are standing in a bright area.

---

## 8. Tripod Shooting (Armor Stand)

Place a camera (with lens) in an **armor stand's hand**, then right-click the armor stand to open the camera settings from its perspective.

### Steps

```
1. Place an armor stand and aim it at your subject
2. Give the armor stand a camera + lens
3. Right-click the armor stand → camera settings open
4. Set exposure / focus, then press the shutter button
5. Photo is taken from the armor stand's exact position and orientation
```

### Advantages Over Handheld Shooting

- **Fixed viewpoint** — shoot from any position, even places you can't stand
- **No shake blur** — long exposures stay sharp (no hand-camera simulation)
- **Long exposure** — great for night scenes, light trails, star trails
- **Auto-focus** — AF snaps from the armor stand's eye position automatically

---

## 9. Video Camera

### Requirements

**FFmpeg** must be installed and available on your system PATH (used for encoding).

### Recording

```
1. Hold the video camera
2. Right-click → start recording
3. Right-click again → stop recording
4. Saved as MP4 to .minecraft/photographica/videos/
```

| Key | Action |
|-----|--------|
| Scroll | Zoom in / out |
| Right-click | Start / stop recording |

A red recording indicator appears in the top-right of the screen while recording.

---

## 10. Displaying Photos

### Photo Frame (wall-mounted)

Place against a wall. Right-click with a Photo item to display it. The image renders on the front face of the block.

### Photo Stand (floor)

Place on the floor. Right-click with a Photo item to display it.

### Inserting and Removing Photos

- **Right-click** — insert photo
- **Sneak + Right-click** — remove photo

> **Photos are linked to PNG files.** If the file is deleted, the frame goes blank. In multiplayer, each player needs the PNG in their own `photographica/photos/` folder.

---

## 11. Photographic Effects Reference

All effects are **baked into the PNG at capture time** (independent of real-time rendering).

| Effect | Condition | Description |
|--------|-----------|-------------|
| Exposure scaling | Always | Brightness calculated from f-number, SS, ISO. Smooth highlight roll-off |
| Vignetting | Always | Corner darkening. Strongest at f/1.4 |
| ISO noise | Always | Luminance noise + chroma noise at high ISO |
| Film grain | Film only | Per-stock grain texture on top of ISO noise |
| Film tone | Film only | Colour: warm shift / Colour 100: cool & saturated / B&W: high-contrast |
| Depth-of-field blur | f/5.6 or wider & lens attached | Depth-buffer-based bokeh with no colour bleed at edges |
| Hand-shake blur | SS ≥ 1/30 & handheld | Camera-shake simulation. Disabled for tripod (armor stand) shots |
| Long-exposure blend | SS > 1/30 | Multiple frames averaged. Light trails and ghosting |
| Diffraction softening | f/16 or narrower | 3×3 box blur for gentle overall softness |
| Reciprocity failure | Film & SS ≥ 1 s | Film loses sensitivity at long exposures |
| Light fogging | Film loaded/unloaded at player position with light level ≥ 8 | All frames washed out white |

---

## 12. Tips & FAQ

**Q: My photos are too dark / too bright**  
→ Check the exposure meter. In Av/Tv/P mode, open the viewfinder and wait a moment for auto-exposure to converge. In M mode, use Ctrl / Alt + Scroll to adjust.

**Q: No depth-of-field blur**  
→ DoF blur only fires at f/5.6 or wider. Set the aperture to f/5.6 or below (f/4, f/2.8, etc.).

**Q: "Photo file not found" when viewing a photo**  
→ Check `.minecraft/photographica/photos/`. In multiplayer, every player needs their own copy of the PNG.

**Q: Developed film — all frames are white**  
→ The roll was fogged. Fogging occurs when you load or unload film while standing at **light level 8 or above**. Always do film changes at **light level ≤ 7** or inside a **portable darkroom** — keeping film in your inventory while standing in daylight is not safe.

**Q: Tripod perspective is stuck after shooting**  
→ Fixed in v0.2.0+. Update to the latest version.

**Q: Negative thumbnails show "NO FILE"**  
→ Check that the PNG exists in `photographica/photos/`. After a fresh shot, wait about 2 seconds — the cache retries automatically.

**Q: "FFmpeg not found" when recording video**  
→ Download FFmpeg from [https://ffmpeg.org/](https://ffmpeg.org/) and add it to your system PATH.

---

## File Locations

```
.minecraft/
└── photographica/
    ├── photos/     ← Photo PNGs  (yyyy-MM-dd_HH-mm-ss_<uuid>.png)
    └── videos/     ← Video MP4s
```

---

*Photographica v0.2.1 — Fabric / Minecraft 1.21.1, 1.21.4, 1.21.11, 26.1*
