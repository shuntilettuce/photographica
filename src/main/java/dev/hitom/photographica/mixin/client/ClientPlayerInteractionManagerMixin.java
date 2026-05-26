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
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Opens the camera settings screen when the player right-clicks (empty main hand)
 * an armor stand that has a camera equipped in any slot (MAINHAND, OFFHAND, or CHEST).
 *
 * Sneaking falls through to vanilla behavior.
 * When the player holds a camera and clicks the stand, vanilla Equipment equipping
 * handles it automatically (cameras implement Equipment → CHEST slot).
 */
@Mixin(ClientPlayerInteractionManager.class)
public class ClientPlayerInteractionManagerMixin {

    @Inject(method = "interactEntity",
            at = @At("HEAD"),
            cancellable = true)
    private void photographica$openArmorStandCameraScreen(
            PlayerEntity player, Entity entity, Hand hand,
            CallbackInfoReturnable<ActionResult> cir) {
        if (hand != Hand.MAIN_HAND) return;
        if (!(entity instanceof ArmorStandEntity stand)) return;
        if (player.isSneaking()) return;
        if (!player.getMainHandStack().isEmpty()) return;

        // Find camera on the stand (check all equip slots)
        ItemStack camera = null;
        for (EquipmentSlot slot : new EquipmentSlot[]{
                EquipmentSlot.MAINHAND, EquipmentSlot.OFFHAND, EquipmentSlot.CHEST}) {
            ItemStack s = stand.getEquippedStack(slot);
            if (isCameraItem(s)) { camera = s; break; }
        }
        if (camera == null) return;

        final ItemStack cameraStack = camera;
        MinecraftClient mc = MinecraftClient.getInstance();
        if (cameraStack.getItem() instanceof VideoCameraItem) {
            mc.setScreen(new VideoCameraScreen(cameraStack));
        } else if (cameraStack.getItem() instanceof FilmCameraItem) {
            mc.setScreen(new FilmCameraScreen(cameraStack, stand.getId()));
        } else {
            mc.setScreen(new CameraScreen(cameraStack, stand.getId()));
        }
        cir.setReturnValue(ActionResult.SUCCESS);
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
