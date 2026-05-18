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

	public static final int COUNT = 3;

	private LensKind() {}

	public static boolean hasLens(int lensType) {
		return lensType != NONE;
	}

	public static boolean isZoom(int lensType) {
		return lensType == ZOOM_24_70;
	}

	/** Discrete focal length stops the lens snaps to. Single element for prime lenses. */
	public static List<Integer> focalLengthStops(int lensType) {
		return switch (lensType) {
			case PRIME_50MM -> List.of(50);
			case ZOOM_24_70 -> List.of(24, 28, 35, 50, 70);
			default -> List.of(50);
		};
	}

	/** Default focal length when a lens is attached. */
	public static int defaultFocalLength(int lensType) {
		return switch (lensType) {
			case ZOOM_24_70 -> 35;
			case PRIME_50MM -> 50;
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
			case PRIME_50MM -> "50mm 単焦点";
			case ZOOM_24_70 -> "24-70mm ズーム";
			default -> "レンズなし";
		};
	}
}
