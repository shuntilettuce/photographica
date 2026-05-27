package dev.hitom.photographica.mixin.client;

import dev.hitom.photographica.client.screen.CameraScreen;
import dev.hitom.photographica.client.screen.FilmCameraScreen;
import dev.hitom.photographica.item.CameraItem;
import dev.hitom.photographica.item.FilmCameraItem;
import dev.hitom.photographica.item.MirrorlessCameraItem;
import dev.hitom.photographica.item.VideoCameraItem;
import dev.hitom.photographica.client.screen.VideoCameraScreen;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerInteractionManager;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EquipmentSlot;
import net.minecraft.entity.decoration.ArmorStandEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.EntityHitResult;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Opens the camera settings screen when the player right-clicks (empty main hand)
 * an armor stand that has a camera equipped in any slot.
 *
 * We hook interactEntityAtLocation rather than interactEntity because the vanilla
 * ArmorStand.interactAt() fires inside interactEntityAtLocation and returns CONSUME
 * (removing the camera from the stand) before interactEntity is ever reached.
 * Intercepting at the earlier hook lets us cancel the take-out action and open
 * the settings screen instead.
 *
 * Sneaking falls through to vanilla so the player can still use normal armor-stand
 * interactions while crouching.
 */
@Mixin(ClientPlayerInteractionManager.class)
public class ClientPlayerInteractionManagerMixin {

    /**
     * Primary hook: fires for position-sensitive entity interaction (the path armor
     * stands actually use — returns CONSUME when removing an item from a slot).
     */
    @Inject(method = "interactEntityAtLocation",
            at = @At("HEAD"),
            cancellable = true)
    private void photographica$openArmorStandCameraScreenAt(
            PlayerEntity player, Entity entity, EntityHitResult hitResult, Hand hand,
            CallbackInfoReturnable<ActionResult> cir) {
        if (tryOpenCameraScreen(player, entity, hand, cir)) return;
    }

    /**
     * Fallback hook: fires when interactAt returns PASS (no position-sensitive action).
     * Kept in case some code path reaches here instead of interactEntityAtLocation.
     */
    @Inject(method = "interactEntity",
            at = @At("HEAD"),
            cancellable = true)
    private void photographica$openArmorStandCameraScreen(
            PlayerEntity player, Entity entity, Hand hand,
            CallbackInfoReturnable<ActionResult> cir) {
        tryOpenCameraScreen(player, entity, hand, cir);
    }

    // ── Shared logic ──────────────────────────────────────────────────────────

    private static boolean tryOpenCameraScreen(PlayerEntity player, Entity entity, Hand hand,
                                               CallbackInfoReturnable<ActionResult> cir) {
        if (hand != Hand.MAIN_HAND) return false;
        if (!(entity instanceof ArmorStandEntity stand)) return false;
        if (player.isSneaking()) return false;
        if (!player.getMainHandStack().isEmpty()) return false;

        // Find a camera on the stand (main hand, off hand, or chest slot)
        ItemStack camera = null;
        for (EquipmentSlot slot : new EquipmentSlot[]{
                EquipmentSlot.MAINHAND, EquipmentSlot.OFFHAND, EquipmentSlot.CHEST}) {
            ItemStack s = stand.getEquippedStack(slot);
            if (isCameraItem(s)) { camera = s; break; }
        }
        if (camera == null) return false;

        final ItemStack cameraStack = camera;
        MinecraftClient mc = MinecraftClient.getInstance();
        if (cameraStack.getItem() instanceof VideoCameraItem) {
            mc.setScreen(new VideoCameraScreen(cameraStack, stand.getId()));
        } else if (cameraStack.getItem() instanceof FilmCameraItem) {
            mc.setScreen(new FilmCameraScreen(cameraStack, stand.getId()));
        } else {
            mc.setScreen(new CameraScreen(cameraStack, stand.getId()));
        }
        cir.setReturnValue(ActionResult.SUCCESS);
        return true;
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
