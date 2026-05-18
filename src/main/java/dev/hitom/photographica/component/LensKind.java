package dev.hitom.photographica.component;

/**
 * Lens type IDs stored in {@link CameraSettings#lensType()}.
 */
public final class LensKind {
	public static final int NONE = 0;
	public static final int PRIME_50MM = 1;
	public static final int ZOOM_24_70 = 2;

	public static final int COUNT = 3;

	private LensKind() {}

	public static boolean canZoom(int lensType) {
		return lensType == ZOOM_24_70;
	}

	public static String displayName(int lensType) {
		return switch (lensType) {
			case PRIME_50MM -> "50mm 単焦点";
			case ZOOM_24_70 -> "24-70mm ズーム";
			default -> "レンズなし";
		};
	}
}
