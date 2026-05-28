package dev.hitom.photographica.item;

import dev.hitom.photographica.component.CameraSettings;
import dev.hitom.photographica.component.LensKind;
import dev.hitom.photographica.component.ModDataComponents;
import dev.hitom.photographica.component.SdCardData;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.tooltip.TooltipType;
import net.minecraft.text.Text;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Formatting;
import net.minecraft.util.Hand;
//? if <1.21.4 {
import net.minecraft.util.TypedActionResult;
//?}
import net.minecraft.world.World;

import java.util.List;
import java.util.function.Consumer;

//? if >=1.21.4 {
/*public class CameraItem extends Item {*/
//?} else {
public class CameraItem extends Item implements net.minecraft.item.Equipment {
//?}
	/** Wired by client init. Opens the dial GUI on shift+right-click. */
	public static Consumer<ItemStack> clientOpenScreen = stack -> {};
	/** Wired by client init. Captures screenshot to disk and notifies server on right-click. */
	public static Consumer<ItemStack> clientTakePhoto = stack -> {};

	public CameraItem(Settings settings) {
		super(settings.maxCount(1));
	}

	//? if <1.21.4 {
	@Override
	public net.minecraft.entity.EquipmentSlot getSlotType() {
		return net.minecraft.entity.EquipmentSlot.CHEST;
	}
	//?}

	public static CameraSettings getSettings(ItemStack stack) {
		CameraSettings s = stack.get(ModDataComponents.CAMERA_SETTINGS);
		return s != null ? s : CameraSettings.DEFAULT;
	}

	public static void setSettings(ItemStack stack, CameraSettings settings) {
		stack.set(ModDataComponents.CAMERA_SETTINGS, settings);
	}

	//? if >=1.21.11 {
	/*@Override
	public void appendTooltip(ItemStack stack, Item.TooltipContext context, net.minecraft.component.type.TooltipDisplayComponent tooltipDisplay, java.util.function.Consumer<Text> tooltipSink, TooltipType type) {
		CameraSettings s = getSettings(stack);
		tooltipSink.accept(Text.literal("レンズ: " + LensKind.displayName(s.lensType())).formatted(Formatting.GRAY));
		if (stack.contains(ModDataComponents.SD_CARD)) {
			SdCardData sd = stack.get(ModDataComponents.SD_CARD);
			int count = sd != null ? sd.photos().size() : 0;
			int cap   = sd != null ? sd.capacity()      : SdCardData.DEFAULT_CAPACITY;
			tooltipSink.accept(Text.literal("SD: " + count + "/" + cap + "枚").formatted(Formatting.GREEN));
		} else {
			tooltipSink.accept(Text.literal("SD: 未挿入").formatted(Formatting.DARK_GRAY));
		}
		String[] expLabels = {"M", "Av", "Tv", "P"};
		tooltipSink.accept(Text.literal("露出: " + expLabels[Math.max(0, Math.min(3, s.exposureMode()))]).formatted(Formatting.DARK_GRAY));
	}*/
	//?} else {
	@Override
	public void appendTooltip(ItemStack stack, TooltipContext context, List<Text> tooltip, TooltipType type) {
		CameraSettings s = getSettings(stack);
		tooltip.add(Text.literal("レンズ: " + LensKind.displayName(s.lensType())).formatted(Formatting.GRAY));
		if (stack.contains(ModDataComponents.SD_CARD)) {
			SdCardData sd = stack.get(ModDataComponents.SD_CARD);
			int count = sd != null ? sd.photos().size() : 0;
			int cap   = sd != null ? sd.capacity()      : SdCardData.DEFAULT_CAPACITY;
			tooltip.add(Text.literal("SD: " + count + "/" + cap + "枚").formatted(Formatting.GREEN));
		} else {
			tooltip.add(Text.literal("SD: 未挿入").formatted(Formatting.DARK_GRAY));
		}
		String[] expLabels = {"M", "Av", "Tv", "P"};
		tooltip.add(Text.literal("露出: " + expLabels[Math.max(0, Math.min(3, s.exposureMode()))]).formatted(Formatting.DARK_GRAY));
	}
	//?}

	//? if >=1.21.4 {
	/*@Override
	public ActionResult use(World world, PlayerEntity user, Hand hand) {
		ItemStack stack = user.getStackInHand(hand);
		if (world.isClient()) clientTakePhoto.accept(stack);
		return ActionResult.SUCCESS;
	}*/
	//?} else {
	@Override
	public TypedActionResult<ItemStack> use(World world, PlayerEntity user, Hand hand) {
		ItemStack stack = user.getStackInHand(hand);
		if (world.isClient()) clientTakePhoto.accept(stack);
		return TypedActionResult.success(stack, world.isClient());
	}
	//?}
}
