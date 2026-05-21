# Photographica

A photography mod that brings realistic camera simulation to Minecraft. Take photos with film or digital cameras, develop your negatives in a darkroom, and print photographs to display in your world.

## Requirements

- Minecraft **1.21.1**
- Fabric Loader **≥ 0.16.0**
- [Fabric API](https://modrinth.com/mod/fabric-api)

Both client and server must have the mod installed for multiplayer.

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

### Photographic Effects
- Depth-of-field blur (depth-aware, no colour bleed at boundaries)
- Motion blur on slow shutter speeds
- ISO grain and chroma noise
- Lens vignetting by aperture
- Diffraction softening at f/16+
- Film-stock tone curves per film type
- Highlight rolloff and film reciprocity failure

### Film Workflow
- Five film stocks: Colour ISO 400 (36 exp.), Colour ISO 100 (36 exp.), Colour ISO 1600 (36 exp.), B&W ISO 400 (36 exp.), Colour ISO 400 (24 exp.)
- Develop exposed rolls in the Darkroom block
- Print negatives in the Enlarger block to get displayable Photo items
- Right-click a developed film roll to preview negatives as inverted thumbnails

### Digital Workflow
- Photos saved as PNG files in `.minecraft/photographica/photos/`
- SD card browser with thumbnail preview, metadata display, and per-photo deletion
- Shader-compatible capture (tested with Iris + Photon)

## Building

```bash
./gradlew build
```

The output JAR will be at `build/libs/photographica-<version>.jar`.

## Development

This mod was developed with the assistance of [Claude Code](https://claude.ai/code) (Anthropic's AI coding assistant). The game design, creative direction, and all final decisions were made by the author.

## License

[MIT](LICENSE) — © 2024 hitom
