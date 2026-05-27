package dev.hitom.photographica.item;

import dev.hitom.photographica.component.FilmKind;
import dev.hitom.photographica.component.FilmRollData;
import dev.hitom.photographica.component.ModDataComponents;
import net.minecraft.item.tooltip.TooltipType;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.text.Text;

import java.util.List;

/**
 * Exposed (latent) film — undeveloped. Carries a {@link FilmRollData} with
 * one or more PhotoData entries; right-clicking does nothing. The frames
 * are turned into viewable Photo items only by the developer tank, and
 * only in pitch-dark surroundings (light level 0).
 */
public class ExposedFilmItem extends Item {
	public ExposedFilmItem(Settings settings) {
		super(settings.maxCount(16));
	}

	@Override
	public void appendTooltip(ItemStack stack, TooltipContext context, List<Text> tooltip, TooltipType type) {
		FilmRollData f = stack.get(ModDataComponents.FILM_ROLL);
		if (f == null) {
			tooltip.add(Text.literal("§c(空のフィルム)"));
			return;
		}
		tooltip.add(Text.literal("§7" + FilmKind.displayName(f.filmType())));
		tooltip.add(Text.literal("§e撮影済 " + f.usedExposures() + "/" + f.totalExposures() + " 枚"));
		tooltip.add(Text.literal("§8暗室で現像してください"));
	}
}
