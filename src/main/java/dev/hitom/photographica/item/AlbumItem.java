package dev.hitom.photographica.item;

import dev.hitom.photographica.component.AlbumData;
import dev.hitom.photographica.component.ModDataComponents;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.tooltip.TooltipType;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.Hand;
//? if >=1.21.4 {
/*import net.minecraft.util.ActionResult;*/
//?} else {
import net.minecraft.util.TypedActionResult;
//?}
import net.minecraft.world.World;

import java.util.List;

/**
 * A physical photo album — a bundle for printed photos, the paper counterpart to an SD card.
 * Holds real {@link ItemStack}s (see {@link AlbumData}), so pulling a print back out hands back
 * the exact photo, not a freshly minted copy of its metadata.
 *
 * <p>Unlike the camera dial screens, the album's screen is a real synced
 * {@link net.minecraft.screen.ScreenHandler} (slots the player drags photos into), so opening
 * it goes through {@code player.openHandledScreen} server-side rather than a client-only
 * {@code Consumer} — the same reasoning {@code CameraGearScreens#openIfSneaking} documents.
 */
public class AlbumItem extends Item {

    public AlbumItem(Settings settings) {
        super(settings.maxCount(1));
    }

    public static AlbumData getAlbum(ItemStack stack) {
        AlbumData data = stack.get(ModDataComponents.ALBUM);
        return data == null ? AlbumData.EMPTY : data;
    }

    //? if >=1.21.11 {
    /*@Override
    public ActionResult use(World world, PlayerEntity user, Hand hand) {
        if (!world.isClient()) openAlbumScreen(user, hand);
        return ActionResult.SUCCESS;
    }*/
    //?} else if >=1.21.4 {
    /*@Override
    public ActionResult use(World world, PlayerEntity user, Hand hand) {
        if (!world.isClient) openAlbumScreen(user, hand);
        return ActionResult.SUCCESS;
    }*/
    //?} else {
    @Override
    public TypedActionResult<ItemStack> use(World world, PlayerEntity user, Hand hand) {
        ItemStack stack = user.getStackInHand(hand);
        if (!world.isClient) openAlbumScreen(user, hand);
        return TypedActionResult.success(stack, world.isClient);
    }
    //?}

    /** Opens the album's slot screen — main hand only, matching how
     *  {@code AlbumScreenHandler} resolves the album it edits. */
    private static void openAlbumScreen(PlayerEntity user, Hand hand) {
        if (hand != Hand.MAIN_HAND) return;
        user.openHandledScreen(new net.minecraft.screen.NamedScreenHandlerFactory() {
            @Override
            public Text getDisplayName() {
                return user.getStackInHand(Hand.MAIN_HAND).getName();
            }

            @Override
            public net.minecraft.screen.ScreenHandler createMenu(int syncId,
                    net.minecraft.entity.player.PlayerInventory inv, PlayerEntity player) {
                return new dev.hitom.photographica.screen.AlbumScreenHandler(syncId, inv);
            }
        });
    }

    private static String countLine(ItemStack stack) {
        AlbumData data = getAlbum(stack);
        return data.filledSlots() + " / " + data.capacity() + " 枚";
    }

    //? if >=1.21.11 {
    /*@Override
    public void appendTooltip(ItemStack stack, TooltipContext context,
                              net.minecraft.component.type.TooltipDisplayComponent tooltipDisplay,
                              java.util.function.Consumer<Text> tooltipSink, TooltipType type) {
        tooltipSink.accept(Text.literal(countLine(stack)).formatted(Formatting.GRAY));
    }*/
    //?} else {
    @Override
    public void appendTooltip(ItemStack stack, TooltipContext context, List<Text> tooltip, TooltipType type) {
        tooltip.add(Text.literal(countLine(stack)).formatted(Formatting.GRAY));
    }
    //?}
}
