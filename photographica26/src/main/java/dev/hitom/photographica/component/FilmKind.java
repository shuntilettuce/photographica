package dev.hitom.photographica.component;

public final class FilmKind {
	public static final int DIGITAL      = 0;
	public static final int COLOR_400    = 1;  // ISO 400 color 36exp (original)
	public static final int COLOR_100    = 2;  // ISO 100 color 36exp — vivid, very low grain
	public static final int COLOR_1600   = 3;  // ISO 1600 color 36exp — high grain, low-light
	public static final int BW_400       = 4;  // ISO 400 B&W 36exp
	public static final int COLOR_400_24 = 5;  // ISO 400 color 24exp

	private FilmKind() {}

	public static boolean isFilm(int filmType) {
		return filmType != DIGITAL;
	}

	public static boolean isBW(int filmType) {
		return filmType == BW_400;
	}

	public static int isoOf(int filmType) {
		return switch (filmType) {
			case COLOR_100    ->  100;
			case COLOR_400    ->  400;
			case COLOR_400_24 ->  400;
			case COLOR_1600   -> 1600;
			case BW_400       ->  400;
			default           ->  100;
		};
	}

	public static int defaultExposures(int filmType) {
		return switch (filmType) {
			case COLOR_400_24 -> 24;
			default           -> 36;
		};
	}

	public static String displayName(int filmType) {
		return switch (filmType) {
			case COLOR_400    -> "カラー ISO400 (36枚)";
			case COLOR_100    -> "カラー ISO100 (36枚)";
			case COLOR_1600   -> "カラー ISO1600 (36枚)";
			case BW_400       -> "モノクロ ISO400 (36枚)";
			case COLOR_400_24 -> "カラー ISO400 (24枚)";
			default           -> "デジタル";
		};
	}
}
