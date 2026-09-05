package dev.hitom.photographica.component;

import java.util.List;

/**
 * Lens type IDs stored in {@link CameraSettings#lensType()} and the focal length
 * stops each lens supports.
 */
public final class LensKind {
	public static final int NONE = 0;
	public static final int PRIME_50MM = 1;
	public static final int ZOOM_24_70 = 2;
	public static final int PRIME_35MM   = 3;  // 35mm wide prime
	public static final int PRIME_85MM   = 4;  // 85mm portrait prime
	public static final int PRIME_14MM   = 5;  // 14mm ultra-wide prime
	public static final int ZOOM_70_200  = 6;  // 70-200mm telephoto zoom
	public static final int MACRO_100    = 7;  // 100mm macro
	/** Drone-mounted camera's built-in lens — a simplified 24-200mm zoom, fixed at f/2.8
	 *  wherever this lens is forced on (see DroneEntity#applyDroneCameraProfile), modeled as a
	 *  DUAL-camera hybrid zoom (see {@link #digitalZoomSoftenPx}) rather than one continuous
	 *  optical range: a wide sensor covers 24-70mm (with its OWN digital crop degrading as it
	 *  approaches 70), then the airframe switches to a tele sensor at 70mm (a hard reset back
	 *  to sharp — a real optical focal length for that second lens), which covers 70-200mm
	 *  (again degrading via digital crop as it approaches its own limit). Real dual/triple-lens
	 *  phone cameras behave exactly this way — sharp dips right before each camera switch, a
	 *  reset at the switch itself, sharp again just after. */
	public static final int DRONE_ZOOM   = 8;

	public static final int COUNT = 9;

	/** The wide sensor's native focal length — the only length it resolves at full detail. */
	public static final int DRONE_FOCAL_MIN = 24;
	/** The tele sensor's native focal length, and where the airframe switches over to it. */
	public static final int DRONE_FOCAL_OPTICAL_MAX = 70;
	public static final int DRONE_FOCAL_MAX = 200;

	/** Slight exaggeration of the physically-derived softness, so the quality difference reads
	 *  clearly on a Minecraft-resolution screen instead of being a subtlety you'd need to
	 *  pixel-peep for. Deliberately small — the whole point is that this looks like a real
	 *  soft upscale, not a mosaic filter. */
	private static final float SOFTEN_EXAGGERATION = 1.4f;

	/**
	 * How much detail the drone's digital zoom throws away at a given focal length, expressed
	 * as the size (in destination pixels) of one genuine source pixel.
	 *
	 * <p>Both sensors resolve full detail ONLY at their own native focal length; reaching any
	 * longer focal length means cropping into the sensor and upscaling, so N source pixels have
	 * to cover N×ratio destination pixels. That upscale is what actually degrades the image —
	 * it goes SOFT (a real low-resolution upscale), it does not turn into blocks, which is why
	 * this is reconstructed bilinearly on both the live ({@code digital_zoom.fsh}) and saved
	 * ({@code PhotoCapture#applyDigitalSoftening}) paths.
	 *
	 * <p>Ratios peak at ~2.9× just before each switch (70/24 and 200/70), giving ~4 destination
	 * pixels per source pixel at the roughest — visibly soft, still clearly an image.
	 *
	 * @return destination pixels per source pixel; {@code <= 1} means no degradation at all.
	 */
	/**
	 * Focal length to run DEPTH-OF-FIELD math at, which is not always the focal length the shot
	 * is actually framed at.
	 *
	 * <p>Circle of confusion goes with focal length SQUARED, so honest optics would make the
	 * drone's 200mm end blur ~69× harder than its 24mm end at the same f/2.8 — technically
	 * right, unusable in practice, since every telephoto drone shot would come back as a
	 * subject floating in mush. Pinning bokeh to the wide end keeps a consistent, gentle f/2.8
	 * separation across the whole zoom range. Real drones land in the same place for a different
	 * reason: their sensors are tiny, so they have deep depth of field regardless of framing.
	 *
	 * <p>Only DRONE_ZOOM is treated this way — the interchangeable lenses are the part of this
	 * mod where honest optics IS the feature, so they always report their true focal length.
	 */
	public static int bokehFocalLengthMm(int lensType, int focalMm) {
		return lensType == DRONE_ZOOM ? DRONE_FOCAL_MIN : focalMm;
	}

	public static float digitalZoomSoftenPx(int focalMm) {
		int nativeFocal = focalMm < DRONE_FOCAL_OPTICAL_MAX ? DRONE_FOCAL_MIN : DRONE_FOCAL_OPTICAL_MAX;
		float ratio = focalMm / (float) nativeFocal;
		if (ratio <= 1.0f) return 1.0f;
		return 1.0f + (ratio - 1.0f) * SOFTEN_EXAGGERATION;
	}

	private LensKind() {}

	public static boolean hasLens(int lensType) {
		return lensType != NONE;
	}

	public static boolean isZoom(int lensType) {
		return lensType == ZOOM_24_70 || lensType == ZOOM_70_200 || lensType == DRONE_ZOOM;
	}

	/** Discrete focal length stops the lens snaps to. Single element for prime lenses. */
	public static List<Integer> focalLengthStops(int lensType) {
		return switch (lensType) {
			case PRIME_50MM  -> List.of(50);
			case ZOOM_24_70  -> List.of(24, 28, 35, 50, 70);
			case PRIME_35MM  -> List.of(35);
			case PRIME_85MM  -> List.of(85);
			case PRIME_14MM  -> List.of(14);
			case ZOOM_70_200 -> List.of(70, 85, 100, 135, 200);
			case MACRO_100   -> List.of(100);
			case DRONE_ZOOM  -> List.of(24, 28, 35, 50, 70, 85, 100, 135, 200);
			default -> List.of(50);
		};
	}

	/** Default focal length when a lens is attached. */
	public static int defaultFocalLength(int lensType) {
		return switch (lensType) {
			case ZOOM_24_70  -> 35;
			case PRIME_50MM  -> 50;
			case PRIME_35MM  -> 35;
			case PRIME_85MM  -> 85;
			case PRIME_14MM  -> 14;
			case ZOOM_70_200 -> 135;
			case MACRO_100   -> 100;
			case DRONE_ZOOM  -> 24;
			default -> 50;
		};
	}

	/** Clamp a focal length to the nearest stop the given lens supports. */
	public static int clampFocalLength(int lensType, int focalMm) {
		List<Integer> stops = focalLengthStops(lensType);
		int best = stops.get(0);
		int bestDiff = Math.abs(focalMm - best);
		for (int i = 1; i < stops.size(); i++) {
			int d = Math.abs(focalMm - stops.get(i));
			if (d < bestDiff) {
				bestDiff = d;
				best = stops.get(i);
			}
		}
		return best;
	}

	public static String displayName(int lensType) {
		return switch (lensType) {
			case PRIME_50MM  -> "50mm 単焦点";
			case ZOOM_24_70  -> "24-70mm ズーム";
			case PRIME_35MM  -> "35mm 単焦点";
			case PRIME_85MM  -> "85mm 単焦点";
			case PRIME_14MM  -> "14mm 超広角";
			case ZOOM_70_200 -> "70-200mm ズーム";
			case MACRO_100   -> "100mm マクロ";
			case DRONE_ZOOM  -> "24/70mm ドローン内蔵";
			default -> "レンズなし";
		};
	}
}
