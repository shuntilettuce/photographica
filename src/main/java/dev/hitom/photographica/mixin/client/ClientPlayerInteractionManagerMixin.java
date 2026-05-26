package dev.hitom.photographica.mixin.client;

import dev.hitom.photographica.client.screen.CameraScreen;
import dev.hitom.photographica.client.screen.FilmCameraScreen;
import dev.hitom.photographica.item.CameraItem;
import dev.hitom.photographica.item.FilmCameraItem;
import dev.hitom.photographica.item.MirrorlessCameraItem;
import dev.hitom.photographica.network.EquipCameraToArmorStandPayload;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerInteractionManager;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EquipmentSlot;
import net.minecraft.entity.decoration.ArmorStandEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Hand;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Intercepts right-click on an armor stand:
 *
 * 1. Player holding a camera + stand has no camera → equip the camera to the stand.
 * 2. Player with empty hand + stand has a camera → open camera settings screen.
 *
 * Sneaking always falls through to vanilla behavior (item pickup / slot cycling).
 */
@Mixin(ClientPlayerInteractionManager.class)
public class ClientPlayerInteractionManagerMixin {

    @Inject(method = "interactEntity",
            at = @At("HEAD"),
            cancellable = true)
    private void photographica$armorStandCameraInteract(
            PlayerEntity player, Entity entity, Hand hand,
            CallbackInfoReturnable<ActionResult> cir) {
        if (hand != Hand.MAIN_HAND) return;
        if (!(entity instanceof ArmorStandEntity stand)) return;
        if (player.isSneaking()) return;

        ItemStack held = player.getMainHandStack();

        // ── Case 1: Player is holding a camera → try to equip it to the stand ──
        if (isCameraItem(held)) {
            // Only equip if the stand has no camera in either hand already.
            boolean standHasCamera = isCameraItem(stand.getEquippedStack(EquipmentSlot.MAINHAND))
                    || isCameraItem(stand.getEquippedStack(EquipmentSlot.OFFHAND));
            if (!standHasCamera) {
                ClientPlayNetworking.send(new EquipCameraToArmorStandPayload(stand.getId()));
                cir.setReturnValue(ActionResult.SUCCESS);
            }
            return;
        }

        // ── Case 2: Player has empty hand → open settings if stand has a camera ──
        if (!held.isEmpty()) return;

        ItemStack camera = stand.getEquippedStack(EquipmentSlot.MAINHAND);
        if (!isCameraItem(camera)) {
            camera = stand.getEquippedStack(EquipmentSlot.OFFHAND);
            if (!isCameraItem(camera)) return;
        }

        final ItemStack cameraStack = camera;
        MinecraftClient mc = MinecraftClient.getInstance();
        mc.setScreen(cameraStack.getItem() instanceof FilmCameraItem
                ? new FilmCameraScreen(cameraStack, stand.getId())
                : new CameraScreen(cameraStack, stand.getId()));
        cir.setReturnValue(ActionResult.SUCCESS);
    }

    private static boolean isCameraItem(ItemStack stack) {
        return !stack.isEmpty() && (
                stack.getItem() instanceof CameraItem ||
                stack.getItem() instanceof FilmCameraItem ||
                stack.getItem() instanceof MirrorlessCameraItem
        );
    }
}
