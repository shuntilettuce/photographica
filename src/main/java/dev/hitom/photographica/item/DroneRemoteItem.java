package dev.hitom.photographica.item;

import dev.hitom.photographica.component.ModDataComponents;
import dev.hitom.photographica.entity.DroneEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.tooltip.TooltipType;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.Hand;
import net.minecraft.world.World;
//? if >=1.21.4 {
/*import net.minecraft.util.ActionResult;*/
//?} else {
import net.minecraft.util.TypedActionResult;
//?}

import java.util.List;
import java.util.function.Consumer;

/**
 * A drone's RC handset. Not the thing that actually flies a drone (that logic all still lives
 * in {@code DronePilot}) — just the trigger for it, and the thing that makes touching a drone
 * with bare hands no longer fly it (see {@code DroneEntity#onInteract}). Two jobs:
 *
 * <p>Right-clicking a {@link DroneEntity} directly pairs this remote to that airframe's
 * channel (handled entirely in {@code DroneEntity} — it writes {@link ModDataComponents#DRONE_FREQUENCY}
 * onto the held stack, this class never touches that side).
 *
 * <p>Right-clicking anything else (or empty air) with a paired remote searches for a matching
 * drone the same way a real handset would — over the air, not by touch — via
 * {@link #clientTryPilot}, wired to a nearby-entity scan in {@code PhotographicaClient} rather
 * than a world-wide lookup: a radio has range, it doesn't reach across dimensions the way the
 * fax machine's landline-style number lookup does.
 */
public class DroneRemoteItem extends Item {
    /** Wired by client init. Searches for a drone matching this stack's tuned frequency
     *  within range and toggles piloting on it. */
    public static Consumer<ItemStack> clientTryPilot = stack -> {};

    public DroneRemoteItem(Settings settings) {
        super(settings.maxCount(1));
    }

    //? if >=1.21.11 {
    /*@Override
    public ActionResult use(World world, PlayerEntity user, Hand hand) {
        ItemStack stack = user.getStackInHand(hand);
        if (world.isClient()) clientTryPilot.accept(stack);
        return ActionResult.SUCCESS;
    }*/
    //?} else if >=1.21.4 {
    /*@Override
    public ActionResult use(World world, PlayerEntity user, Hand hand) {
        ItemStack stack = user.getStackInHand(hand);
        if (world.isClient) clientTryPilot.accept(stack);
        return ActionResult.SUCCESS;
    }*/
    //?} else {
    @Override
    public TypedActionResult<ItemStack> use(World world, PlayerEntity user, Hand hand) {
        ItemStack stack = user.getStackInHand(hand);
        if (world.isClient) clientTryPilot.accept(stack);
        return TypedActionResult.success(stack, world.isClient);
    }
    //?}

    //? if >=1.21.11 {
    /*@Override
    public void appendTooltip(ItemStack stack, TooltipContext context,
                              net.minecraft.component.type.TooltipDisplayComponent tooltipDisplay,
                              java.util.function.Consumer<Text> tooltipSink, TooltipType type) {
        tooltipSink.accept(frequencyLine(stack));
    }*/
    //?} else {
    @Override
    public void appendTooltip(ItemStack stack, TooltipContext context, List<Text> tooltip, TooltipType type) {
        tooltip.add(frequencyLine(stack));
    }
    //?}

    private static Text frequencyLine(ItemStack stack) {
        Integer freq = stack.get(ModDataComponents.DRONE_FREQUENCY);
        return freq != null
                ? Text.literal("📡 チャンネル " + freq).formatted(Formatting.GRAY)
                : Text.literal("📡 未ペアリング — ドローンにタッチして合わせてください").formatted(Formatting.DARK_GRAY);
    }
}
