package dev.hitom.photographica.mixin.client;

import dev.hitom.photographica.client.screen.CameraScreen;
import dev.hitom.photographica.client.screen.FilmCameraScreen;
import dev.hitom.photographica.item.CameraItem;
import dev.hitom.photographica.item.FilmCameraItem;
import dev.hitom.photographica.item.MirrorlessCameraItem;
import dev.hitom.photographica.item.VideoCameraItem;
import dev.hitom.photographica.client.screen.VideoCameraScreen;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.MultiPlayerGameMode;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.phys.EntityHitResult;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Opens the camera settings screen when the player right-clicks (empty main hand)
 * an armor stand that has a camera equipped in any slot.
 *
 * We hook interactWithEntity rather than interactEntity because the vanilla
 * ArmorStand.interactAt() fires inside interactWithEntity and returns CONSUME
 * (removing the camera from the stand) before interactEntity is ever reached.
 * Intercepting at the earlier hook lets us cancel the take-out action and open
 * the settings screen instead.
 *
 * Sneaking falls through to vanilla so the player can still use normal armor-stand
 * interactions while crouching.
 */
@Mixin(MultiPlayerGameMode.class)
public class ClientPlayerInteractionManagerMixin {

    /**
     * Primary hook: fires for position-sensitive entity interaction (the path armor
     * stands actually use — returns CONSUME when removing an item from a slot).
     */
    @Inject(method = "interactWithEntity",
            at = @At("HEAD"),
            cancellable = true)
    private void photographica$openArmorStandCameraScreenAt(
            Player player, Entity entity, EntityHitResult hitResult, InteractionHand hand,
            CallbackInfoReturnable<InteractionResult> cir) {
        tryOpenCameraScreen(player, entity, hand, cir);
    }

    /**
     * Fallback hook: fires when interactAt returns PASS (no position-sensitive action).
     * Kept in case some code path reaches here instead of interactWithEntity.
     */
    @Inject(method = "interact",
            at = @At("HEAD"),
            cancellable = true)
    private void photographica$openArmorStandCameraScreen(
            Player player, Entity entity, InteractionHand hand,
            CallbackInfoReturnable<InteractionResult> cir) {
        tryOpenCameraScreen(player, entity, hand, cir);
    }

    // ── Shared logic ──────────────────────────────────────────────────────────

    private static void tryOpenCameraScreen(Player player, Entity entity, InteractionHand hand,
                                            CallbackInfoReturnable<InteractionResult> cir) {
        if (hand != InteractionHand.MAIN_HAND) return;
        if (!(entity instanceof ArmorStand stand)) return;
        if (player.isShiftKeyDown()) return;
        if (!player.getMainHandItem().isEmpty()) return;

        // Find a camera on the stand (main hand, off hand, or chest slot)
        ItemStack camera = null;
        for (EquipmentSlot slot : new EquipmentSlot[]{
                EquipmentSlot.MAINHAND, EquipmentSlot.OFFHAND, EquipmentSlot.CHEST}) {
            ItemStack s = stand.getItemBySlot(slot);
            if (isCameraItem(s)) { camera = s; break; }
        }
        if (camera == null) return;

        final ItemStack cameraStack = camera;
        Minecraft mc = Minecraft.getInstance();
        if (cameraStack.getItem() instanceof VideoCameraItem) {
            mc.setScreen(new VideoCameraScreen(cameraStack, stand.getId()));
        } else if (cameraStack.getItem() instanceof FilmCameraItem) {
            mc.setScreen(new FilmCameraScreen(cameraStack, stand.getId()));
        } else {
            mc.setScreen(new CameraScreen(cameraStack, stand.getId()));
        }
        cir.setReturnValue(InteractionResult.SUCCESS);
    }

    private static boolean isCameraItem(ItemStack stack) {
        return !stack.isEmpty() && (
                stack.getItem() instanceof CameraItem ||
                stack.getItem() instanceof FilmCameraItem ||
                stack.getItem() instanceof MirrorlessCameraItem ||
                stack.getItem() instanceof VideoCameraItem
        );
    }
}
