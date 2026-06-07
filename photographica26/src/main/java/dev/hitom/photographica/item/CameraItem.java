package dev.hitom.photographica.item;

import dev.hitom.photographica.component.CameraSettings;
import dev.hitom.photographica.component.LensKind;
import dev.hitom.photographica.component.ModDataComponents;
import dev.hitom.photographica.component.SdCardData;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.component.TooltipProvider;
import net.minecraft.network.chat.Component;
import net.minecraft.ChatFormatting;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.level.Level;

import java.util.function.Consumer;

public class CameraItem extends Item {
	/** Wired by client init. Opens the dial GUI on shift+right-click. */
	public static Consumer<ItemStack> clientOpenScreen = stack -> {};
	/** Wired by client init. Captures screenshot to disk and notifies server on right-click. */
	public static Consumer<ItemStack> clientTakePhoto = stack -> {};

	public CameraItem(Properties settings) {
		super(settings.stacksTo(1));
	}

	public static CameraSettings getSettings(ItemStack stack) {
		CameraSettings s = stack.get(ModDataComponents.CAMERA_SETTINGS);
		return s != null ? s : CameraSettings.DEFAULT;
	}

	public static void setSettings(ItemStack stack, CameraSettings settings) {
		stack.set(ModDataComponents.CAMERA_SETTINGS, settings);
	}

	@Override
	public void appendHoverText(ItemStack stack, Item.TooltipContext context,
	                            net.minecraft.world.item.component.TooltipDisplay tooltipDisplay,
	                            Consumer<Component> tooltipSink, TooltipFlag type) {
		CameraSettings s = getSettings(stack);
		tooltipSink.accept(Component.literal("レンズ: " + LensKind.displayName(s.lensType())).withStyle(ChatFormatting.GRAY));
		if (stack.has(ModDataComponents.SD_CARD)) {
			SdCardData sd = stack.get(ModDataComponents.SD_CARD);
			int count = sd != null ? sd.photos().size() : 0;
			int cap   = sd != null ? sd.capacity()      : SdCardData.DEFAULT_CAPACITY;
			tooltipSink.accept(Component.literal("SD: " + count + "/" + cap + "枚").withStyle(ChatFormatting.GREEN));
		} else {
			tooltipSink.accept(Component.literal("SD: 未挿入").withStyle(ChatFormatting.DARK_GRAY));
		}
		String[] expLabels = {"M", "Av", "Tv", "P"};
		tooltipSink.accept(Component.literal("露出: " + expLabels[Math.max(0, Math.min(3, s.exposureMode()))]).withStyle(ChatFormatting.DARK_GRAY));
	}

	@Override
	public InteractionResult use(Level world, Player user, InteractionHand hand) {
		ItemStack stack = user.getItemInHand(hand);
		if (world.isClientSide()) clientTakePhoto.accept(stack);
		return InteractionResult.SUCCESS;
	}
}
