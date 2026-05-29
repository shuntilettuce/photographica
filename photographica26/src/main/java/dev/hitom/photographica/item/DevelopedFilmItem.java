package dev.hitom.photographica.item;

import dev.hitom.photographica.component.FilmRollData;
import dev.hitom.photographica.component.ModDataComponents;
import dev.hitom.photographica.component.PhotoData;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.network.chat.Component;
import net.minecraft.ChatFormatting;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.level.Level;

import java.util.function.Consumer;

public class DevelopedFilmItem extends Item {
    /** Wired by client init to open the film strip preview screen. */
    public static Consumer<ItemStack> clientOpenFilmStrip = stack -> {};

    public DevelopedFilmItem(Properties settings) {
        super(settings.stacksTo(1));
    }

    @Override
    public InteractionResult use(Level world, Player user, InteractionHand hand) {
        ItemStack stack = user.getItemInHand(hand);
        if (world.isClientSide()) {
            clientOpenFilmStrip.accept(stack);
        }
        return InteractionResult.SUCCESS;
    }

    @Override
    public Component getName(ItemStack stack) {
        return Component.literal("現像済フィルム").withStyle(ChatFormatting.GOLD);
    }

    @Override
    public void appendHoverText(ItemStack stack, Item.TooltipContext context,
                                net.minecraft.world.item.component.TooltipDisplay tooltipDisplay,
                                Consumer<Component> tooltipSink, TooltipFlag type) {
        FilmRollData film = stack.get(ModDataComponents.FILM_ROLL);
        if (film == null) {
            tooltipSink.accept(Component.literal("§c(空)"));
            return;
        }
        tooltipSink.accept(Component.literal("§e現像済ネガ: " + film.exposures().size() + "枚"));
        int max = Math.min(3, film.exposures().size());
        for (int i = 0; i < max; i++) {
            PhotoData p = film.exposures().get(i);
            String fogInfo = p.fogged() ? " §c[感光]§8" : "";
            tooltipSink.accept(Component.literal("§8  " + (i + 1) + ". " + p.id().toString().substring(0, 8) + fogInfo));
        }
        if (film.exposures().size() > max) {
            tooltipSink.accept(Component.literal("§8  ...他" + (film.exposures().size() - max) + "枚"));
        }
    }
}
