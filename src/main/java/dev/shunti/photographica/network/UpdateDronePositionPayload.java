package dev.hitom.photographica.network;

import net.minecraft.network.RegistryByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.Identifier;

/**
 * C2S: sent ~20/s by whoever is piloting a drone (see {@code DronePilot}) — the pilot's own
 * view already moved this tick (collision-checked against their own client world), this just
 * tells the server to move the actual {@code DroneEntity} to match, so normal entity tracking
 * carries it on to everyone else.
 */
public record UpdateDronePositionPayload(int droneEntityId, double x, double y, double z,
                                          float yaw, float pitch, float bank) implements CustomPayload {
    public static final CustomPayload.Id<UpdateDronePositionPayload> ID =
            new CustomPayload.Id<>(Identifier.of("photographica", "update_drone_position"));

    public static final PacketCodec<RegistryByteBuf, UpdateDronePositionPayload> CODEC = new PacketCodec<>() {
        @Override
        public UpdateDronePositionPayload decode(RegistryByteBuf buf) {
            int id = buf.readInt();
            double x = buf.readDouble();
            double y = buf.readDouble();
            double z = buf.readDouble();
            float yaw = buf.readFloat();
            float pitch = buf.readFloat();
            float bank = buf.readFloat();
            return new UpdateDronePositionPayload(id, x, y, z, yaw, pitch, bank);
        }
        @Override
        public void encode(RegistryByteBuf buf, UpdateDronePositionPayload v) {
            buf.writeInt(v.droneEntityId);
            buf.writeDouble(v.x);
            buf.writeDouble(v.y);
            buf.writeDouble(v.z);
            buf.writeFloat(v.yaw);
            buf.writeFloat(v.pitch);
            buf.writeFloat(v.bank);
        }
    };

    @Override
    public Id<? extends CustomPayload> getId() { return ID; }
}
