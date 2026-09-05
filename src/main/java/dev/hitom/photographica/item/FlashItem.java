package dev.hitom.photographica.item;

import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.tooltip.TooltipType;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;

import java.util.List;

/**
 * A hot-shoe flash. Until now a dark scene could only be photographed by opening up, slowing the
 * shutter or cranking ISO — all of which the exposure model punishes with blur and grain, and
 * none of which actually put light on the subject. This does.
 *
 * <p>Modelled as a guide number, the way real flashes are rated: it fully lights a subject out
 * to {@link #guideNumber} blocks and falls off past that, so a bigger unit is reach rather than
 * raw brightness. Firing costs battery ({@link #shotCost}) — a flash is the single greediest
 * thing you can bolt to a camera, which is exactly why it belongs on the same cell as the body.
 */
public class FlashItem extends Item {

    /** Effective range in blocks — the distance the unit can still fully light a subject at. */
    public final int guideNumber;
    /** Battery units consumed per firing, on top of the camera body's own per-shot cost. */
    public final int shotCost;

    public FlashItem(Settings settings, int guideNumber, int shotCost) {
        super(settings.maxCount(1));
        this.guideNumber = guideNumber;
        this.shotCost = shotCost;
    }

    public static int guideNumberOf(ItemStack stack) {
        return stack.getItem() instanceof FlashItem flash ? flash.guideNumber : 0;
    }

    public static int shotCostOf(ItemStack stack) {
        return stack.getItem() instanceof FlashItem flash ? flash.shotCost : 0;
    }

    /**
     * How much light this unit lands on a subject {@code distance} blocks away, 0.0 - 1.0.
     * Full power out to the guide number, then an inverse-square falloff — the reason real
     * flash photos have a bright subject and a black background.
     */
    public static float illuminationAt(ItemStack stack, float distance) {
        int gn = guideNumberOf(stack);
        if (gn <= 0) return 0f;
        if (distance <= gn) return 1f;
        float ratio = gn / distance;
        return Math.max(0f, Math.min(1f, ratio * ratio));
    }

    //? if >=1.21.11 {
    /*@Override
    public void appendTooltip(ItemStack stack, TooltipContext context,
                              net.minecraft.component.type.TooltipDisplayComponent tooltipDisplay,
                              java.util.function.Consumer<Text> tooltipSink, TooltipType type) {
        tooltipSink.accept(Text.literal("到達距離: " + guideNumber + "m").formatted(Formatting.GRAY));
        tooltipSink.accept(Text.literal("消費電力: " + shotCost + " / 回").formatted(Formatting.DARK_GRAY));
    }*/
    //?} else {
    @Override
    public void appendTooltip(ItemStack stack, TooltipContext context, List<Text> tooltip, TooltipType type) {
        tooltip.add(Text.literal("到達距離: " + guideNumber + "m").formatted(Formatting.GRAY));
        tooltip.add(Text.literal("消費電力: " + shotCost + " / 回").formatted(Formatting.DARK_GRAY));
    }
    //?}
}
