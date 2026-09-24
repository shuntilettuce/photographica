package dev.hitom.photographica.screen;

import dev.hitom.photographica.component.CameraGear;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.screen.NamedScreenHandlerFactory;
import net.minecraft.screen.ScreenHandler;
import net.minecraft.text.Text;
import net.minecraft.util.Hand;
import net.minecraft.world.World;
import org.jetbrains.annotations.Nullable;

/**
 * Opens the camera body's slot screen. Bound to sneak + right-click rather than a keybinding:
 * fitting a lens or swapping a card is a thing you do TO a specific camera you are holding, and
 * the mod previously spent four separate keybindings on exactly that.
 */
public final class CameraGearScreens {
    private CameraGearScreens() {}

    /**
     * @return true if the interaction was consumed by opening the screen, so the caller should
     *         return without also firing the shutter.
     */
    public static boolean openIfSneaking(World world, PlayerEntity user, Hand hand) {
        if (!user.isSneaking()) return false;
        ItemStack stack = user.getStackInHand(hand);
        // Main hand only — the handler resolves the body from the main hand, so opening this
        // for an off-hand camera would silently edit the wrong one.
        if (hand != Hand.MAIN_HAND || !CameraGear.isCamera(stack)) return false;
        // Server opens it; the client just reports the interaction as handled so it doesn't
        // also take a photo on its own side. Tested via the player type rather than
        // World.isClient, which is a field before 1.21.11 and a method from it onwards — this
        // says the same thing and needs no version split.
        if (user instanceof net.minecraft.server.network.ServerPlayerEntity) {
            user.openHandledScreen(factory(stack));
        }
        return true;
    }

    private static NamedScreenHandlerFactory factory(ItemStack camera) {
        return new NamedScreenHandlerFactory() {
            @Override
            public Text getDisplayName() {
                return camera.getName();
            }

            @Nullable
            @Override
            public ScreenHandler createMenu(int syncId, net.minecraft.entity.player.PlayerInventory inv,
                                            PlayerEntity player) {
                return new CameraGearScreenHandler(syncId, inv);
            }
        };
    }
}
