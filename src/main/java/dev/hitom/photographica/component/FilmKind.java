package dev.hitom.photographica.component;

/**
 * Film type IDs stored in {@link CameraSettings#filmType()} and in
 * {@link FilmRollData}. Each film has a baked-in ISO and a characteristic
 * tonal / colour signature applied at capture time.
 *
 *   DIGITAL : ID 0 — used by the DSLR. No film grading.
 *   COLOR_400: ID 1 — neutral-warm colour negative, ISO 400, 36 exposures.
 */
public final class FilmKind {
	public static final int DIGITAL   = 0;
	public static final int COLOR_400 = 1;

	private FilmKind() {}

	public static boolean isFilm(int filmType) {
		return filmType != DIGITAL;
	}

	public static int isoOf(int filmType) {
		return switch (filmType) {
			case COLOR_400 -> 400;
			default -> 100;
		};
	}

	public static int defaultExposures(int filmType) {
		return switch (filmType) {
			case COLOR_400 -> 36;
			default -> 36;
		};
	}

	public static String displayName(int filmType) {
		return switch (filmType) {
			case COLOR_400 -> "カラー ISO400 (36枚)";
			default -> "デジタル";
		};
	}
}
