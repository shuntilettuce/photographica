package dev.hitom.photographica.item;

import dev.hitom.photographica.component.FilmKind;
import dev.hitom.photographica.component.FilmRollData;
import dev.hitom.photographica.component.ModDataComponents;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.network.chat.Component;

import java.util.function.Consumer;

/**
 * Exposed (latent) film — undeveloped. Carries a {@link FilmRollData} with
 * one or more PhotoData entries; right-clicking does nothing. The frames
 * are turned into viewable Photo items only by the developer tank, and
 * only in pitch-dark surroundings (light level 0).
 */
public class ExposedFilmItem extends Item {
	public ExposedFilmItem(Properties settings) {
		super(settings.stacksTo(16));
	}

	@Override
	public void appendHoverText(ItemStack stack, Item.TooltipContext context,
	                            net.minecraft.world.item.component.TooltipDisplay tooltipDisplay,
	                            Consumer<Component> tooltipSink, TooltipFlag type) {
		FilmRollData f = stack.get(ModDataComponents.FILM_ROLL);
		if (f == null) {
			tooltipSink.accept(Component.literal("§c(空のフィルム)"));
			return;
		}
		tooltipSink.accept(Component.literal("§7" + FilmKind.displayName(f.filmType())));
		tooltipSink.accept(Component.literal("§e撮影済 " + f.usedExposures() + "/" + f.totalExposures() + " 枚"));
		tooltipSink.accept(Component.literal("§8暗室で現像してください"));
	}
}
