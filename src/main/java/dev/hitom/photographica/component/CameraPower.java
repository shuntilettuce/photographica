package dev.hitom.photographica.component;

import dev.hitom.photographica.item.BatteryItem;
import dev.hitom.photographica.item.FlashItem;
import net.minecraft.item.ItemStack;

/**
 * The one place that answers "can this camera fire, and what does firing cost it".
 *
 * <p>Lives in common code because both sides need the same answer for different reasons: the
 * client refuses the shutter and shows why, the server is what actually spends the charge. A
 * split rule here would show up as a camera that clicks but never produces a photo.
 */
public final class CameraPower {
    private CameraPower() {}

    /**
     * Master switch for the whole power mechanic. Off until there is a way to RECHARGE a cell —
     * enforcing it before then would mean every battery in the world is a consumable that
     * silently becomes dead weight, which is a worse experience than not having the mechanic.
     *
     * <p>Everything else here stays live and correct, so turning this back on is the only edit
     * needed once charging exists: the battery slot, the per-shot and per-tick costs, the
     * charge tracking and the flash's power draw are all already wired up and simply have no
     * consequences while this is false.
     */
    public static final boolean POWER_ENFORCED = false;

    public static CameraGear gearOf(ItemStack cameraStack) {
        CameraGear gear = cameraStack.get(ModDataComponents.CAMERA_GEAR);
        return gear == null ? CameraGear.EMPTY : gear;
    }

    /** Total charge one exposure costs, including the flash if one is fitted. */
    public static int shotCost(ItemStack cameraStack) {
        CameraGear gear = gearOf(cameraStack);
        int cost = BatteryItem.CAMERA_COST;
        if (gear.hasFlash()) cost += FlashItem.shotCostOf(gear.flash());
        return cost;
    }

    public static boolean hasBattery(ItemStack cameraStack) {
        return gearOf(cameraStack).hasBattery();
    }

    public static int remainingCharge(ItemStack cameraStack) {
        return BatteryItem.getCharge(gearOf(cameraStack).battery());
    }

    /** True when a battery is fitted and holds enough for one exposure at the current setup —
     *  or always, while {@link #POWER_ENFORCED} is off. */
    public static boolean hasPowerForShot(ItemStack cameraStack) {
        if (!POWER_ENFORCED) return true;
        CameraGear gear = gearOf(cameraStack);
        if (!gear.hasBattery()) return false;
        return BatteryItem.getCharge(gear.battery()) >= shotCost(cameraStack);
    }

    /** Distinguishes "no cell fitted" from "cell is flat" — they need different actions. */
    public static String powerFailureMessage(ItemStack cameraStack) {
        return hasBattery(cameraStack) ? "⚠ バッテリー残量がありません" : "⚠ バッテリーが入っていません";
    }

    /**
     * Spends one exposure's worth of charge. Returns false and spends nothing if there isn't
     * enough, so a refused shot never leaves a partially drained cell.
     *
     * <p>Writes the drained battery back into the camera's gear component: the stack inside a
     * component is a value, not a live reference, so mutating it in place would be discarded.
     */
    public static boolean consumeForShot(ItemStack cameraStack) {
        if (!POWER_ENFORCED) return true;
        CameraGear gear = gearOf(cameraStack);
        if (!gear.hasBattery()) return false;
        ItemStack battery = gear.battery().copy();
        if (!BatteryItem.drain(battery, shotCost(cameraStack))) return false;
        CameraGear.install(cameraStack, gear.withBattery(battery));
        return true;
    }

    /**
     * How much light the fitted flash puts on a subject {@code distance} blocks away, 0.0 - 1.0.
     * Zero with no flash fitted, which is what makes the exposure model fall back to available
     * light untouched.
     */
    public static float flashIllumination(ItemStack cameraStack, float distance) {
        CameraGear gear = gearOf(cameraStack);
        if (!gear.hasFlash()) return 0f;
        return FlashItem.illuminationAt(gear.flash(), distance);
    }
}
