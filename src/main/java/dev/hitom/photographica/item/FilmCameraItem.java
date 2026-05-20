package dev.hitom.photographica.item;

import dev.hitom.photographica.component.CameraSettings;
import dev.hitom.photographica.component.FilmKind;
import dev.hitom.photographica.component.FilmRollData;
import dev.hitom.photographica.component.LensKind;
import dev.hitom.photographica.component.ModDataComponents;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.tooltip.TooltipType;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.Hand;
import net.minecraft.util.TypedActionResult;
import net.minecraft.world.World;

import java.util.List;
import java.util.function.Consumer;

/**
 * Mechanical SLR film camera. Differs from {@link CameraItem}:
 *   - Holds a {@link FilmRollData} component while a film is loaded.
 *   - Each shot consumes one frame and unwinds the camera; another shot is
 *     blocked until the wind action has been performed.
 *   - ISO is fixed by the loaded film (CameraSettings.iso is mirrored from
 *     the film's baked sensitivity on load).
 *   - When the roll is full or manually rewound, the film state is moved
 *     onto a dedicated ExposedFilm item to be developed elsewhere.
 */
public class FilmCameraItem extends Item {
	public static Consumer<ItemStack> clientOpenScreen   = stack -> {};
	public static Consumer<ItemStack> clientTakePhoto    = stack -> {};

	public FilmCameraItem(Settings settings) {
		super(settings.maxCount(1));
	}

	public static CameraSettings getSettings(ItemStack stack) {
		CameraSettings s = stack.get(ModDataComponents.CAMERA_SETTINGS);
		// Film cameras default to filmType = COLOR_400 so the rendering pipeline
		// applies film grading even before a roll is loaded for the first time.
		return s != null ? s : new CameraSettings(
				5.6f, 10, FilmKind.isoOf(FilmKind.COLOR_400),
				5.0f, 50, LensKind.NONE, FilmKind.COLOR_400, 0,
				CameraSettings.EXP_M, CameraSettings.FOCUS_MF, false);
	}

	public static void setSettings(ItemStack stack, CameraSettings settings) {
		stack.set(ModDataComponents.CAMERA_SETTINGS, settings);
	}

	public static FilmRollData getFilm(ItemStack stack) {
		FilmRollData f = stack.get(ModDataComponents.FILM_ROLL);
		return f != null ? f : new FilmRollData(FilmKind.COLOR_400, 0, 0, false, List.of());
	}

	public static void setFilm(ItemStack stack, FilmRollData film) {
		stack.set(ModDataComponents.FILM_ROLL, film);
	}

	public static boolean hasFilm(ItemStack stack) {
		FilmRollData f = stack.get(ModDataComponents.FILM_ROLL);
		return f != null && f.totalExposures() > 0;
	}

	@Override
	public void appendTooltip(ItemStack stack, TooltipContext context, List<Text> tooltip, TooltipType type) {
		CameraSettings s = getSettings(stack);
		tooltip.add(Text.literal("レンズ: " + LensKind.displayName(s.lensType())).formatted(Formatting.GRAY));
		FilmRollData film = getFilm(stack);
		if (film.totalExposures() == 0) {
			tooltip.add(Text.literal("フィルム: 未装填").formatted(Formatting.RED));
		} else if (film.isExposed()) {
			tooltip.add(Text.literal("フィルム: 撮影済 " + film.usedExposures() + "/" + film.totalExposures() + "枚").formatted(Formatting.YELLOW));
		} else {
			String wind = film.wound() ? "§a巻上済§r" : "§c要巻上§r";
			tooltip.add(Text.of("フィルム: " + FilmKind.displayName(film.filmType())
					+ "  " + film.usedExposures() + "/" + film.totalExposures() + "枚  " + wind));
		}
		String[] expLabels = {"M", "Av", "Tv", "P"};
		tooltip.add(Text.literal("露出: " + expLabels[Math.max(0, Math.min(3, s.exposureMode()))]).formatted(Formatting.DARK_GRAY));
	}

	@Override
	public TypedActionResult<ItemStack> use(World world, PlayerEntity user, Hand hand) {
		ItemStack stack = user.getStackInHand(hand);
		if (world.isClient) clientTakePhoto.accept(stack);
		return TypedActionResult.success(stack, world.isClient);
	}
}
