package dev.hitom.photographica.item;

import dev.hitom.photographica.component.CameraSettings;
import dev.hitom.photographica.component.FilmKind;
import dev.hitom.photographica.component.FilmRollData;
import dev.hitom.photographica.component.LensKind;
import dev.hitom.photographica.component.ModDataComponents;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.network.chat.Component;
import net.minecraft.ChatFormatting;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.level.Level;

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

	public FilmCameraItem(Properties settings) {
		super(settings.stacksTo(1));
	}

	public static CameraSettings getSettings(ItemStack stack) {
		CameraSettings s = stack.get(ModDataComponents.CAMERA_SETTINGS);
		// Film cameras default to filmType = COLOR_400 so the rendering pipeline
		// applies film grading even before a roll is loaded for the first time.
		return s != null ? s : new CameraSettings(
				5.6f, 10, FilmKind.isoOf(FilmKind.COLOR_400),
				5.0f, 50, LensKind.NONE, FilmKind.COLOR_400, 0,
				CameraSettings.EXP_M, CameraSettings.FOCUS_MF, false, 0, false);
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
	public void appendHoverText(ItemStack stack, Item.TooltipContext context,
	                            net.minecraft.world.item.component.TooltipDisplay tooltipDisplay,
	                            Consumer<Component> tooltipSink, TooltipFlag type) {
		CameraSettings s = getSettings(stack);
		tooltipSink.accept(Component.literal("レンズ: " + LensKind.displayName(s.lensType())).withStyle(ChatFormatting.GRAY));
		FilmRollData film = getFilm(stack);
		if (film.totalExposures() == 0) {
			tooltipSink.accept(Component.literal("フィルム: 未装填").withStyle(ChatFormatting.RED));
		} else if (film.isExposed()) {
			tooltipSink.accept(Component.literal("フィルム: 撮影済 " + film.usedExposures() + "/" + film.totalExposures() + "枚").withStyle(ChatFormatting.YELLOW));
		} else {
			String wind = film.wound() ? "§a巻上済§r" : "§c要巻上§r";
			tooltipSink.accept(Component.literal("フィルム: " + FilmKind.displayName(film.filmType())
					+ "  " + film.usedExposures() + "/" + film.totalExposures() + "枚  " + wind));
		}
		String[] expLabels = {"M", "Av", "Tv", "P"};
		tooltipSink.accept(Component.literal("露出: " + expLabels[Math.max(0, Math.min(3, s.exposureMode()))]).withStyle(ChatFormatting.DARK_GRAY));
	}

	@Override
	public InteractionResult use(Level world, Player user, InteractionHand hand) {
		ItemStack stack = user.getItemInHand(hand);
		if (world.isClientSide()) clientTakePhoto.accept(stack);
		return InteractionResult.SUCCESS;
	}
}
