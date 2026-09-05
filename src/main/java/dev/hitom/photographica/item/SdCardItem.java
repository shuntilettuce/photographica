package dev.hitom.photographica.item;

import dev.hitom.photographica.component.ModDataComponents;
import dev.hitom.photographica.component.PhotoData;
import dev.hitom.photographica.component.SdCardData;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.tooltip.TooltipType;
import net.minecraft.text.Text;
import net.minecraft.util.Hand;
//? if >=1.21.4 {
/*import net.minecraft.util.ActionResult;*/
//?} else {
import net.minecraft.util.TypedActionResult;
//?}
import net.minecraft.world.World;

import java.util.List;

public class SdCardItem extends Item {
    /** How many photos this tier holds. Tiered like vanilla tools: identical to use, the
     *  difference is purely how much it carries. */
    public final int capacity;

    public SdCardItem(Settings settings, int capacity) {
        super(settings.maxCount(1));
        this.capacity = capacity;
    }

    /** Capacity of the card tier {@code stack} is, or {@link SdCardData#DEFAULT_CAPACITY} for
     *  anything that is not a card. */
    public static int capacityOf(ItemStack stack) {
        return stack.getItem() instanceof SdCardItem card ? card.capacity : SdCardData.DEFAULT_CAPACITY;
    }

    /** Right-click while holding the card to open its gallery directly, without needing to
     *  load it into a camera first. */
    //? if >=1.21.11 {
    /*@Override
    public ActionResult use(World world, PlayerEntity user, Hand hand) {
        ItemStack stack = user.getStackInHand(hand);
        if (world.isClient()) openGallery(stack);
        return ActionResult.SUCCESS;
    }*/
    //?} else if >=1.21.4 {
    /*@Override
    public ActionResult use(World world, PlayerEntity user, Hand hand) {
        ItemStack stack = user.getStackInHand(hand);
        if (world.isClient) openGallery(stack);
        return ActionResult.SUCCESS;
    }*/
    //?} else {
    @Override
    public TypedActionResult<ItemStack> use(World world, PlayerEntity user, Hand hand) {
        ItemStack stack = user.getStackInHand(hand);
        if (world.isClient) openGallery(stack);
        return TypedActionResult.success(stack, world.isClient);
    }
    //?}

    private static void openGallery(ItemStack stack) {
        net.minecraft.client.MinecraftClient mc = net.minecraft.client.MinecraftClient.getInstance();
        SdCardData data = getSdCard(stack);
        mc.setScreen(new dev.hitom.photographica.client.screen.SdCardGalleryScreen(stack, data, mc.currentScreen));
    }

    //? if >=1.21.11 {
    /*@Override
    public void appendTooltip(ItemStack stack, TooltipContext context,
                               net.minecraft.component.type.TooltipDisplayComponent tooltipDisplay,
                               java.util.function.Consumer<Text> tooltipSink, TooltipType type) {
        SdCardData data = stack.getOrDefault(ModDataComponents.SD_CARD, SdCardData.EMPTY);
        tooltipSink.accept(Text.literal("§e" + data.photos().size() + "/" + data.capacity() + "枚保存済"));
        if (!data.photos().isEmpty()) {
            int max = Math.min(3, data.photos().size());
            for (int i = 0; i < max; i++) {
                PhotoData p = data.photos().get(i);
                tooltipSink.accept(Text.literal("§8  " + p.photographer() + " @(" + p.x() + "," + p.y() + "," + p.z() + ")"));
            }
            if (data.photos().size() > max) {
                tooltipSink.accept(Text.literal("§8  ...他" + (data.photos().size() - max) + "枚"));
            }
        }
    }*/
    //?} else {
    @Override
    public void appendTooltip(ItemStack stack, TooltipContext context, List<Text> tooltip, TooltipType type) {
        SdCardData data = stack.getOrDefault(ModDataComponents.SD_CARD, SdCardData.EMPTY);
        tooltip.add(Text.literal("§e" + data.photos().size() + "/" + data.capacity() + "枚保存済"));
        if (!data.photos().isEmpty()) {
            int max = Math.min(3, data.photos().size());
            for (int i = 0; i < max; i++) {
                PhotoData p = data.photos().get(i);
                tooltip.add(Text.literal("§8  " + p.photographer() + " @(" + p.x() + "," + p.y() + "," + p.z() + ")"));
            }
            if (data.photos().size() > max) {
                tooltip.add(Text.literal("§8  ...他" + (data.photos().size() - max) + "枚"));
            }
        }
    }
    //?}

    public static SdCardData getSdCard(ItemStack stack) {
        return stack.getOrDefault(ModDataComponents.SD_CARD, SdCardData.EMPTY);
    }

    public static void setSdCard(ItemStack stack, SdCardData data) {
        stack.set(ModDataComponents.SD_CARD, data);
    }

    public static boolean hasSdCard(ItemStack stack) {
        return stack.contains(ModDataComponents.SD_CARD);
    }
}
