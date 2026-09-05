package dev.hitom.photographica.item;

import dev.hitom.photographica.component.ModDataComponents;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.tooltip.TooltipType;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;

import java.util.List;

/**
 * A rechargeable cell. Every powered device in this mod (camera bodies, the drone) runs off one
 * — the mod already makes you carry lenses, film and cards, and a camera that never needs power
 * was the one piece of kit that quietly worked forever.
 *
 * <p>Tiered the way vanilla tiers tools: same shape, more capacity. Charge is stored per-stack
 * ({@link ModDataComponents#BATTERY_CHARGE}) rather than on the device, so a half-drained cell
 * stays half-drained when you pull it out and pocket it, and swapping in a spare mid-shoot is
 * the natural move.
 */
public class BatteryItem extends Item {

    /** Charge units. One photo costs {@code CAMERA_COST}, one tick of drone flight costs
     *  {@code DRONE_COST_PER_TICK} — see the constants below for what that works out to. */
    public final int capacity;

    /** Shots a camera gets per full charge of each tier: 60 / 200 / 600. */
    public static final int CAMERA_COST = 1;
    /** Drone flight is the expensive load — rotors, radio and a live video feed. At 1 unit per
     *  tick a standard cell gives 3 seconds short of a minute of flight; the top cell ~30. */
    public static final int DRONE_COST_PER_TICK = 1;

    public BatteryItem(Settings settings, int capacity) {
        super(settings.maxCount(1));
        this.capacity = capacity;
    }

    /** Remaining charge. A battery with no component yet is treated as factory-fresh (full),
     *  so one obtained by any means — creative, /give, a recipe — just works. */
    public static int getCharge(ItemStack stack) {
        if (!(stack.getItem() instanceof BatteryItem battery)) return 0;
        Integer stored = stack.get(ModDataComponents.BATTERY_CHARGE);
        return stored == null ? battery.capacity : Math.max(0, Math.min(battery.capacity, stored));
    }

    public static int getCapacity(ItemStack stack) {
        return stack.getItem() instanceof BatteryItem battery ? battery.capacity : 0;
    }

    public static void setCharge(ItemStack stack, int charge) {
        if (!(stack.getItem() instanceof BatteryItem battery)) return;
        stack.set(ModDataComponents.BATTERY_CHARGE, Math.max(0, Math.min(battery.capacity, charge)));
    }

    /** Drains {@code amount} and reports whether there was enough to cover it. Returns false
     *  without partially draining, so a device is never left in a "half-fired" state. */
    public static boolean drain(ItemStack stack, int amount) {
        int charge = getCharge(stack);
        if (charge < amount) return false;
        setCharge(stack, charge - amount);
        return true;
    }

    public static boolean isEmpty(ItemStack stack) {
        return getCharge(stack) <= 0;
    }

    /** 0.0 - 1.0, for bars and HUD readouts. */
    public static float chargeFraction(ItemStack stack) {
        int cap = getCapacity(stack);
        return cap <= 0 ? 0f : getCharge(stack) / (float) cap;
    }

    private static Formatting colorFor(float fraction) {
        if (fraction <= 0.10f) return Formatting.RED;
        if (fraction <= 0.30f) return Formatting.GOLD;
        return Formatting.GREEN;
    }

    private static String chargeLine(ItemStack stack) {
        return "残量: " + getCharge(stack) + " / " + getCapacity(stack);
    }

    //? if >=1.21.11 {
    /*@Override
    public void appendTooltip(ItemStack stack, TooltipContext context,
                              net.minecraft.component.type.TooltipDisplayComponent tooltipDisplay,
                              java.util.function.Consumer<Text> tooltipSink, TooltipType type) {
        tooltipSink.accept(Text.literal(chargeLine(stack)).formatted(colorFor(chargeFraction(stack))));
    }*/
    //?} else {
    @Override
    public void appendTooltip(ItemStack stack, TooltipContext context, List<Text> tooltip, TooltipType type) {
        tooltip.add(Text.literal(chargeLine(stack)).formatted(colorFor(chargeFraction(stack))));
    }
    //?}

    // A charge bar on the item itself, so a drained cell is obvious in the hotbar without
    // hovering. Reuses vanilla's durability-bar slot, which is otherwise unused here.
    @Override
    public boolean isItemBarVisible(ItemStack stack) {
        return getCharge(stack) < getCapacity(stack);
    }

    @Override
    public int getItemBarStep(ItemStack stack) {
        return Math.round(chargeFraction(stack) * 13.0f);
    }

    @Override
    public int getItemBarColor(ItemStack stack) {
        float f = chargeFraction(stack);
        if (f <= 0.10f) return 0xC2362B;
        if (f <= 0.30f) return 0xE08A3C;
        return 0x4CAF50;
    }
}
