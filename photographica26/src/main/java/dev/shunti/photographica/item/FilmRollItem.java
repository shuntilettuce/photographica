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
 * Fresh film roll. On creation it carries a {@link FilmRollData} whose
 * filmType identifies the emulsion and whose usedExposures is zero.
 */
public class FilmRollItem extends Item {
	private final int filmType;

	public FilmRollItem(Properties settings, int filmType) {
		super(settings.stacksTo(16));
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
	public void appendHoverText(ItemStack stack, Item.TooltipContext context,
	                            net.minecraft.world.item.component.TooltipDisplay tooltipDisplay,
	                            Consumer<Component> tooltipSink, TooltipFlag type) {
		FilmRollData f = stack.get(ModDataComponents.FILM_ROLL);
		int ft = f != null ? f.filmType() : filmType;
		tooltipSink.accept(Component.literal("§7" + FilmKind.displayName(ft)));
		tooltipSink.accept(Component.literal("§8新品 (未装填)"));
	}
}
