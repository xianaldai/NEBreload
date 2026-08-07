package cn.ussshenzhou.notenoughbandwidth.chunk;

import cn.ussshenzhou.notenoughbandwidth.NotEnoughBandwidthLegacyConfig;
import com.google.common.cache.Cache;
import com.google.common.cache.CacheBuilder;
import com.mojang.logging.LogUtils;
import net.minecraft.network.Connection;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.LinkedHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.atomic.AtomicLong;

/**
 * @author MapleSugar365
 */
public class ChunkReferenceStore {
    private static final Cache<Connection, ChunkReferenceStore> CACHE = CacheBuilder.newBuilder().weakKeys().build();
    private static final ThreadLocal<MessageDigest> SHA256 = ThreadLocal.withInitial(() -> {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException(e);
        }
    });

    private final LinkedHashMap<Long, Long> entries = new LinkedHashMap<>();
    private final int maxEntries;
    private static final AtomicLong TOTAL = new AtomicLong();
    private static final AtomicLong REF = new AtomicLong();
    private static final AtomicLong FULL = new AtomicLong();
    private static final AtomicLong FULL_BYTES = new AtomicLong();
    private static final AtomicLong MISMATCH = new AtomicLong();

    private ChunkReferenceStore(NotEnoughBandwidthLegacyConfig cfg) {
        this.maxEntries = Math.max(16, cfg.chunkReferenceMaxServerEntries);
    }

    public static ChunkReferenceStore get(Connection connection) {
        var cfg = NotEnoughBandwidthLegacyConfig.get();
        if (cfg == null || !cfg.chunkReferenceEnabled) {
            return null;
        }
        try {
            return CACHE.get(connection, () -> new ChunkReferenceStore(cfg));
        } catch (ExecutionException e) {
            return null;
        }
    }


    public static void recordDecision(boolean referenced, int dataLength, boolean mismatch) {
        TOTAL.incrementAndGet();
        if (referenced) {
            REF.incrementAndGet();
        } else {
            FULL.incrementAndGet();
            FULL_BYTES.addAndGet(dataLength);
        }
        if (mismatch) {
            MISMATCH.incrementAndGet();
        }
        var cfg = NotEnoughBandwidthLegacyConfig.get();
        if (cfg != null && cfg.debugLog && TOTAL.get() % 100 == 0) {
            LogUtils.getLogger().info("NEBL chunk-ref server: total={}, ref={}, full={}, fullBytes={}KB, mismatch={}",
                    TOTAL.get(), REF.get(), FULL.get(), FULL_BYTES.get() / 1024, MISMATCH.get());
        }
    }

    public static int minBytes() {
        var cfg = NotEnoughBandwidthLegacyConfig.get();
        return cfg == null ? 4096 : Math.max(1, cfg.chunkReferenceMinBytes);
    }

    public synchronized boolean has(long packedPos) {
        return entries.containsKey(packedPos);
    }

    public synchronized boolean isKnown(long packedPos, long hash) {
        Long known = entries.get(packedPos);
        return known != null && known == hash;
    }

    public synchronized void record(long packedPos, long hash) {
        entries.remove(packedPos);
        entries.put(packedPos, hash);
        while (entries.size() > maxEntries) {
            var it = entries.entrySet().iterator();
            it.next();
            it.remove();
        }
    }

    public synchronized void invalidate(long packedPos) {
        entries.remove(packedPos);
    }

    public static long hash(byte[] data) {
        MessageDigest digest = SHA256.get();
        digest.reset();
        byte[] d = digest.digest(data);
        long h = 0L;
        for (int i = 0; i < Long.BYTES; i++) {
            h = (h << 8) | (d[i] & 0xFFL);
        }
        return h;
    }
}