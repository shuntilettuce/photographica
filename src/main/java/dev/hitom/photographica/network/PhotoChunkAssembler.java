package dev.hitom.photographica.network;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Splits a photo's PNG bytes into fixed-size chunks for sending, and reassembles chunks back
 * into the original bytes as they arrive. Used identically in both directions — server
 * reassembling an {@link UploadPhotoChunkPayload} stream, client reassembling a
 * {@link DownloadPhotoChunkPayload} stream — each side keeps its own instance.
 */
public final class PhotoChunkAssembler {
    /** Comfortably under any custom-payload size concern regardless of photo resolution. */
    public static final int CHUNK_SIZE = 24 * 1024;

    // Everything below is a bound on UNTRUSTED input. The server feeds client-sent
    // UploadPhotoChunkPayloads straight into receive(), on the netty thread, so every size here
    // was previously attacker-controlled: totalChunks sized an array directly (Integer.MAX_VALUE
    // = instant OOM), chunkIndex indexed it unchecked, and nothing ever expired.
    /** Max chunks per photo — 256 * 24KB = 6MB, far above a 1280px-wide PNG. */
    public static final int MAX_CHUNKS = 256;
    /** Max photos being reassembled at once, across all senders. */
    private static final int MAX_CONCURRENT = 16;
    /** How long a partial reassembly survives without new chunks before being dropped. */
    private static final long ENTRY_TTL_MS = 60_000L;

    private static final class Entry {
        final byte[][] chunks;
        int received;
        long lastTouchedMs;
        Entry(int totalChunks) {
            this.chunks = new byte[totalChunks][];
            this.lastTouchedMs = System.currentTimeMillis();
        }
    }

    private final Map<UUID, Entry> parts = new ConcurrentHashMap<>();

    public interface ChunkConsumer {
        void accept(int chunkIndex, int totalChunks, byte[] chunk);
    }

    /** Splits {@code data} into {@link #CHUNK_SIZE} pieces and hands each to {@code sink} in
     *  order. Returns false without sending anything if the data is too large for the receiver
     *  to accept ({@link #MAX_CHUNKS}) — better to fail here, where it can be logged against a
     *  specific photo, than to emit a stream the far side will silently reject chunk by chunk. */
    public static boolean split(byte[] data, ChunkConsumer sink) {
        int total = Math.max(1, (data.length + CHUNK_SIZE - 1) / CHUNK_SIZE);
        if (total > MAX_CHUNKS) return false;
        for (int i = 0; i < total; i++) {
            int from = i * CHUNK_SIZE;
            int to = Math.min(data.length, from + CHUNK_SIZE);
            byte[] chunk = new byte[to - from];
            System.arraycopy(data, from, chunk, 0, chunk.length);
            sink.accept(i, total, chunk);
        }
        return true;
    }

    /**
     * Feeds one chunk in. Returns the fully reassembled byte array once every chunk for
     * {@code id} has arrived, or {@code null} while still waiting on more.
     *
     * <p>Every field of every chunk is treated as hostile: on the server this is fed directly
     * from a client packet, on the netty thread. Anything malformed is dropped by returning
     * null rather than throwing, because an exception here happens off the server thread where
     * it is far more disruptive than a lost photo.
     */
    public synchronized byte[] receive(UUID id, int chunkIndex, int totalChunks, byte[] chunk) {
        if (id == null || chunk == null) return null;
        if (totalChunks < 1 || totalChunks > MAX_CHUNKS) return null;
        if (chunkIndex < 0 || chunkIndex >= totalChunks) return null;
        if (chunk.length > CHUNK_SIZE) return null;

        evictStale();

        Entry entry = parts.get(id);
        if (entry == null) {
            // Cap on concurrent reassemblies: without it, a flood of one-chunk-each photos
            // under distinct ids grows this map without limit and nothing ever removes them.
            if (parts.size() >= MAX_CONCURRENT) return null;
            entry = new Entry(totalChunks);
            parts.put(id, entry);
        }
        // A later chunk claiming a different totalChunks than the one that sized the buffer —
        // either a corrupted stream or a deliberate attempt to index out of bounds.
        if (entry.chunks.length != totalChunks) return null;

        entry.lastTouchedMs = System.currentTimeMillis();
        if (entry.chunks[chunkIndex] == null) {
            entry.chunks[chunkIndex] = chunk;
            entry.received++;
        }
        if (entry.received < totalChunks) return null;

        int total = 0;
        for (byte[] p : entry.chunks) total += p.length;
        byte[] full = new byte[total];
        int pos = 0;
        for (byte[] p : entry.chunks) {
            System.arraycopy(p, 0, full, pos, p.length);
            pos += p.length;
        }
        parts.remove(id);
        return full;
    }

    /** Drops reassemblies that stopped receiving chunks — a sender that disconnects or gives
     *  up halfway would otherwise pin its partial photo in memory permanently. */
    private void evictStale() {
        long cutoff = System.currentTimeMillis() - ENTRY_TTL_MS;
        parts.entrySet().removeIf(e -> e.getValue().lastTouchedMs < cutoff);
    }

    /** Drops any in-progress reassembly for {@code id} (e.g. the requester disconnected). */
    public void abandon(UUID id) {
        parts.remove(id);
    }
}
