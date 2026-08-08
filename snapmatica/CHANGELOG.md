# Changelog

## 1.0.1

### Fixed

- **Foreground defocus dissolves.** Foliage or a fence held close to a fast lens now washes
  together into a haze with no findable edge, instead of softening while keeping its
  outline and the gaps between its leaves readable. A leaf a metre from the lens spreads
  over a disc hundreds of times its own area, so it covers only a few per cent of the
  aperture and belongs on screen at a few per cent — the defocus was computing that
  coverage correctly all along and then dividing it back out of the result, which restored
  the foreground to full opacity everywhere its own silhouette sat over a sharp background.
  Backgrounds, bokeh and anything in focus are untouched, to the pixel.
- **Very heavy foreground defocus no longer keeps the shape of what made it.** The blur
  radius was capped at 120 px whatever the optics asked for, and once every foreground pixel
  is pinned to the same radius the result is just its silhouette dilated by that radius —
  a scaled copy of the shape, still opaque through the middle of anything wider. Leaves
  right at the lens with the focus 20 blocks out came through seven times too dense and
  stopped dead at a hard edge. The ceiling is now three quarters of the frame height, so
  the lens decides, and it is a fraction of the frame rather than a pixel count because a
  circle of confusion is a fraction of the sensor — the old ceiling bit harder the higher
  the resolution.
- **No more dark fringe clinging to a foreground held against the sky.** The atmospheric
  haze floor on distant geometry was applied to a pixel when it was the one being drawn but
  not when it was somebody's neighbour, so a hazed sky reached nothing — not even itself —
  and the defocus renormalised against a background it had counted as barely there. A dark
  post against bright sky came out over twice as opaque as the lens gives, twelve pixels
  outside its own edge.
- **The defocus spends its samples where the blur actually is.** The tap count now follows
  the area being gathered instead of being fixed, which more than pays for the fix above: a
  landscape frame, where the haze floor puts a small disc on nearly every pixel, is now
  faster than it was in 1.0.0.
- **The residue left by a wide gather is smoothed at the scale it actually has.** It was
  sized off the pixel's own defocus, which in a foreground's haze is not the blur doing the
  damage — the wall behind a fence is barely soft itself, so 3 px of smoothing was being
  asked for against blotches ten times that. It now follows the blur blooming over the
  pixel, and reaches three times as far for a ninth of the taps.

## 1.0.0

Snapmatica's first stable release. The lens is now built on real optics rather than
approximations of them, and there's a camera roll to look at what you shot.

Supports **1.21.1, 1.21.4, 1.21.11 and 26.1.2**.

### The lens is a lens now

- **Aperture is a physical opening.** The ring sets the diameter of the blades, not the
  f-number. Since N = f/D, zooming while the blades stay put moves the f-number on its
  own — exactly why a kit zoom is "f/3.5-5.6". Clamped to what the barrel can reach.
- **Diffraction.** Stopping down past about f/11 softens the whole frame, from the Airy
  disc for the current f-number, added in quadrature with the circle of confusion.
- **Distortion.** Wide angles barrel, long ones pincushion, and 50mm is neutral.
- **Long exposure.** Shutter speeds slower than 1/30 accumulate multiple frames into one
  photograph — light trails, smoothed water, the lot. ISO now goes down to 25 for it.
- **Motion blur** from camera movement during the exposure, as a continuous smear rather
  than a stack of ghosts.
- **Focus racks smoothly**, in dioptres, so pulling from 5m to infinity is one continuous
  move instead of a snap. Manual focus uses the same rack as autofocus.
- **Focus reaches through glass.** Point at a window and the camera focuses on what's
  beyond it, and the view through it stays sharp.

### Camera roll

Press **G**, then **Camera Roll** — a phone-style grid of every photo and clip, newest
first. Click to view full screen, arrow keys to move through. Copy to clipboard, show in
folder, or open in your desktop viewer. Videos show a poster frame and play externally.

### Fixed

- Infinity focus really is infinity; distant terrain no longer blurs.
- Viewfinder framing matches the photograph. It used to show less than it recorded, which
  also meant the focal length displayed a narrower angle than it delivered.
- Video no longer drops frames, and records at the frame rate you selected.
- Your hand no longer appears in footage when shaders are installed.
- Every message has an English translation.

### Known limitations

- **A dissolving foreground softens the background behind its own silhouette.** One
  rendered frame holds no record of what a leaf was covering, so where the leaf used to be
  the scene is reconstructed from what surrounds it. Its colour and brightness match to
  within half a level out of 255 — but its fine detail cannot be recovered, so a sharp
  backdrop reads a little soft in a band the width of the foreground itself. A backdrop
  that is itself defocused shows nothing at all.
- Terrain drawn by LOD mods (Voxy, Distant Horizons) leaves no depth for the camera to
  read, so autofocus cannot lock onto it. Focus falls back to infinity, which renders
  distant terrain sharp — the right answer in practice.
- Longitudinal chromatic aberration and signal-dependent noise are not implemented yet.
