package dev.hitom.photographica.network;

import dev.hitom.photographica.Photographica;
import net.minecraft.network.RegistryByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.Identifier;

/**
 * Sent by the client when the player presses "取り出す" in a camera settings screen
 * opened from an armor stand.  The server finds the camera on the stand, removes it,
 * and places it in the player's inventory (or drops it if full).
 */
public record UnequipCameraFromArmorStandPayload(int entityId) implements CustomPayload {
    public static final CustomPayload.Id<UnequipCameraFromArmorStandPayload> ID =
            new CustomPayload.Id<>(Identifier.of(Photographica.MOD_ID, "unequip_camera_from_armor_stand"));

    public static final PacketCodec<RegistryByteBuf, UnequipCameraFromArmorStandPayload> CODEC =
            new PacketCodec<>() {
                @Override
                public UnequipCameraFromArmorStandPayload decode(RegistryByteBuf buf) {
                    return new UnequipCameraFromArmorStandPayload(buf.readInt());
                }

                @Override
                public void encode(RegistryByteBuf buf, UnequipCameraFromArmorStandPayload value) {
                    buf.writeInt(value.entityId());
                }
            };

    @Override
    public Id<? extends CustomPayload> getId() { return ID; }
}
