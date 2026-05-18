package dev.hitom.photographica.item;

import net.minecraft.item.Item;

/**
 * Cosmetic/inventory lens item. The actual "mounted lens" is stored in
 * {@link dev.hitom.photographica.component.CameraSettings#lensType()} on the camera ItemStack.
 *
 * For this iteration the lens item exists so the player can have it in inventory and
 * craft it; mounting/unmounting is performed via the camera dial GUI.
 */
public class LensItem extends Item {
	public final int lensKind;

	public LensItem(Settings settings, int lensKind) {
		super(settings.maxCount(1));
		this.lensKind = lensKind;
	}
}
