# Snapmatica

A client-only photography mod. Bind a key, press it, and Minecraft saves a
screenshot that has been through a simulated camera: depth-of-field blur,
ISO grain, lens vignetting, a tone curve and highlight rolloff, all driven by
aperture, shutter speed and ISO that you set yourself.

Snapmatica is the stripped-down sibling of [Photographica](../README.md) in
the same repository. There are no items, no blocks and no server side — just
the camera, the viewfinder and the photos.

## Requirements

- Minecraft **1.21.1**, **1.21.4**, **1.21.11** or **26.1.2**
- Fabric Loader and [Fabric API](https://modrinth.com/mod/fabric-api)
- Java 21 (Java 25 for the 26.1.2 build)

Client-only: it does nothing on a server and does not need to be installed
on one.

## Setup

Open **Options → Controls → Snapmatica** and bind:

| Action | Default |
|--------|---------|
| Take Photo / Shutter | `P` |
| Open Camera Settings | *(unbound)* |
| Toggle Viewfinder (Sneak) | *(unbound)* |

## Shooting

Sneak to raise the viewfinder. While it is up:

| Input | Adjusts |
|-------|---------|
| Scroll | Focal length (zoom lenses only) |
| Ctrl + Scroll | Aperture |
| Alt + Scroll | Shutter speed |
| Ctrl + Alt + Scroll | Focus distance (manual focus only) |

The overlay shows the current exposure, the lens, the exposure and focus
modes, an exposure meter, and a focus reticle that turns green when the
subject at the centre is in focus. A blur warning appears when the shutter
speed is too slow for the focal length.

Exposure modes are M, Av, Tv and P; focus modes are MF, AF and MOB
(nearest living entity in a narrow forward cone).

Photos are written to `.minecraft/snapmatica/photos/` as timestamped PNGs.

## Building

```bash
./gradlew ":1.21.1:build"      # or 1.21.4, 1.21.11, 26.1.2
```

Jars land in `versions/<minecraft-version>/build/libs/`.

The four Minecraft versions are built from this one source tree with
[Stonecutter](https://stonecutter.kikugie.dev/). Where the game's API
differs, the alternatives sit side by side behind `//? if` comments; only
the branch matching the version being built is compiled. 26 moved from yarn
to Mojang's own mappings, so its branches use different class names for the
same things.

To point your IDE at a different version, run the Stonecutter task for it and
the source tree is rewritten in place:

```bash
./gradlew "Set active project to 26.1.2"
```

## License

[MIT](../LICENSE) — © 2024 shunti
