package dev.hitom.photographica.item;

import dev.hitom.photographica.component.FilmRollData;
import dev.hitom.photographica.component.ModDataComponents;
import dev.hitom.photographica.component.PhotoData;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.tooltip.TooltipType;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;

import java.util.List;

public class DevelopedFilmItem extends Item {
    public DevelopedFilmItem(Settings settings) {
        super(settings.maxCount(1));
    }

    @Override
    public Text getName(ItemStack stack) {
        return Text.literal("現像済フィルム").formatted(Formatting.GOLD);
    }

    @Override
    public void appendTooltip(ItemStack stack, TooltipContext context, List<Text> tooltip, TooltipType type) {
        FilmRollData film = stack.get(ModDataComponents.FILM_ROLL);
        if (film == null) {
            tooltip.add(Text.literal("§c(空)"));
            return;
        }
        tooltip.add(Text.literal("§e現像済ネガ: " + film.exposures().size() + "枚"));
        int max = Math.min(3, film.exposures().size());
        for (int i = 0; i < max; i++) {
            PhotoData p = film.exposures().get(i);
            String fogInfo = p.fogged() ? " §c[感光]§8" : "";
            tooltip.add(Text.literal("§8  " + (i + 1) + ". " + p.id().toString().substring(0, 8) + fogInfo));
        }
        if (film.exposures().size() > max) {
            tooltip.add(Text.literal("§8  ...他" + (film.exposures().size() - max) + "枚"));
        }
    }
}
