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
 * Fresh film roll. On creation it carries a {@link FilmRollData} whose
 * filmType identifies the emulsion and whose usedExposures is zero.
 *
 * Loading into a {@link FilmCameraItem} consumes one of these and copies its
 * data onto the camera; an empty roll is never produced by gameplay paths
 * (an exhausted roll becomes an ExposedFilm item instead).
 */
public class FilmRollItem extends Item {
	private final int filmType;

	public FilmRollItem(Settings settings, int filmType) {
		super(settings.maxCount(16));
		this.filmType = filmType;
	}

	public int filmType() { return filmType; }

	/** New roll factory — used when crafting / giving via /give. */
	public static ItemStack stackOf(Item item, int filmType) {
		ItemStack s = new ItemStack(item);
		s.set(ModDataComponents.FILM_ROLL, FilmRollData.freshRoll(filmType));
		return s;
	}

	@Override
	public void appendTooltip(ItemStack stack, TooltipContext context, List<Text> tooltip, TooltipType type) {
		FilmRollData f = stack.get(ModDataComponents.FILM_ROLL);
		int ft = f != null ? f.filmType() : filmType;
		tooltip.add(Text.literal("§7" + FilmKind.displayName(ft)));
		tooltip.add(Text.literal("§8新品 (未装填)"));
	}
}
