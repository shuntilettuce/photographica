package dev.hitom.photographica.network;

import net.minecraft.network.RegistryByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.Identifier;

/**
 * C2S: sent once by the pilot's own client the instant {@code DronePilot} computes zero signal
 * — the drone itself has no way to notice its remote link died (it's just an entity being
 * pushed around by position-sync packets), so the pilot has to be the one to report it.
 *
 * <p>Carries the drone's velocity at that exact moment, because a real airframe losing its
 * link does not stop: it keeps whatever momentum it had and coasts on, decelerating only from
 * drag while gravity pulls it down. The client is the only side that knows this velocity —
 * flight physics live entirely in {@code DronePilot}, and the entity itself just follows
 * position packets — so it has to travel with the report.
 */
public record DroneSignalLostPayload(int droneEntityId, double vx, double vy, double vz) implements CustomPayload {
    public static final CustomPayload.Id<DroneSignalLostPayload> ID =
            new CustomPayload.Id<>(Identifier.of("photographica", "drone_signal_lost"));

    public static final PacketCodec<RegistryByteBuf, DroneSignalLostPayload> CODEC = new PacketCodec<>() {
        @Override
        public DroneSignalLostPayload decode(RegistryByteBuf buf) {
            int id = buf.readInt();
            double vx = buf.readDouble();
            double vy = buf.readDouble();
            double vz = buf.readDouble();
            return new DroneSignalLostPayload(id, vx, vy, vz);
        }
        @Override
        public void encode(RegistryByteBuf buf, DroneSignalLostPayload v) {
            buf.writeInt(v.droneEntityId);
            buf.writeDouble(v.vx);
            buf.writeDouble(v.vy);
            buf.writeDouble(v.vz);
        }
    };

    @Override
    public Id<? extends CustomPayload> getId() { return ID; }
}
