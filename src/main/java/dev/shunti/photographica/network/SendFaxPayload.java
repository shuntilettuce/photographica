package dev.hitom.photographica.network;

import net.minecraft.network.RegistryByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;

/**
 * C2S: sent when a player presses SEND on a fax machine's screen — {@code pos} identifies
 * which machine is sending (so the server can pull the photo out of ITS own out-tray slot,
 * never trusting a stack sent over the wire), {@code targetNumber} is whatever the player
 * typed into the destination field.
 */
public record SendFaxPayload(BlockPos pos, int targetNumber) implements CustomPayload {
    public static final CustomPayload.Id<SendFaxPayload> ID =
            new CustomPayload.Id<>(Identifier.of("photographica", "send_fax"));

    public static final PacketCodec<RegistryByteBuf, SendFaxPayload> CODEC = new PacketCodec<>() {
        @Override
        public SendFaxPayload decode(RegistryByteBuf buf) {
            BlockPos pos = BlockPos.PACKET_CODEC.decode(buf);
            int targetNumber = buf.readInt();
            return new SendFaxPayload(pos, targetNumber);
        }
        @Override
        public void encode(RegistryByteBuf buf, SendFaxPayload v) {
            BlockPos.PACKET_CODEC.encode(buf, v.pos);
            buf.writeInt(v.targetNumber);
        }
    };

    @Override
    public Id<? extends CustomPayload> getId() { return ID; }
}
