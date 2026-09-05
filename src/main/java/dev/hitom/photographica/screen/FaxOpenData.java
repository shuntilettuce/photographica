package dev.hitom.photographica.screen;

import net.minecraft.network.RegistryByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.util.math.BlockPos;

/**
 * Extra data the server hands the client when opening a fax machine's screen (see
 * {@code ExtendedScreenHandlerType} in {@code ModScreenHandlers}) — the client's own
 * {@link dev.hitom.photographica.block.entity.FaxMachineBlockEntity} isn't directly reachable
 * (its inventory is a throwaway client-side stand-in, same as every other screen in this mod),
 * so anything the screen needs to know about the REAL machine has to ride along here instead:
 * {@code pos} so the SEND button knows which machine to tell the server to send from, and
 * {@code machineNumber} so the screen can just display it.
 */
public record FaxOpenData(BlockPos pos, int machineNumber) {
    public static final PacketCodec<RegistryByteBuf, FaxOpenData> PACKET_CODEC = new PacketCodec<>() {
        @Override
        public FaxOpenData decode(RegistryByteBuf buf) {
            BlockPos pos = BlockPos.PACKET_CODEC.decode(buf);
            int machineNumber = buf.readInt();
            return new FaxOpenData(pos, machineNumber);
        }
        @Override
        public void encode(RegistryByteBuf buf, FaxOpenData v) {
            BlockPos.PACKET_CODEC.encode(buf, v.pos);
            buf.writeInt(v.machineNumber);
        }
    };
}
