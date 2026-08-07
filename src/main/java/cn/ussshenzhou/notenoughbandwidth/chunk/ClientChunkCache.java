package cn.ussshenzhou.notenoughbandwidth.chunk;

import cn.ussshenzhou.notenoughbandwidth.NotEnoughBandwidthLegacyConfig;
import com.google.common.cache.Cache;
import com.google.common.cache.CacheBuilder;
import com.mojang.logging.LogUtils;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.network.Connection;
import net.minecraft.network.protocol.common.ServerboundCustomPayloadPacket;
import net.minecraft.network.protocol.game.ClientboundLevelChunkWithLightPacket;
import net.minecraft.world.level.ChunkPos;
import cn.ussshenzhou.notenoughbandwidth.stat.SimpleStatManager;
import net.neoforged.neoforge.network.handling.IPayloadContext;

import java.util.LinkedHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.atomic.AtomicLong;

/**
 * @author MapleSugar365
 */
public class ClientChunkCache {
    private static final Cache<Connection, ClientChunkCache> CACHE = CacheBuilder.newBuilder().weakKeys().build();

    private final LinkedHashMap<Long, CachedChunk> entries = new LinkedHashMap<>(256, 0.75f, true);
    private final int maxEntries;
    private static final AtomicLong CACHED = new AtomicLong();
    private static final AtomicLong APPLIED = new AtomicLong();
    private static final AtomicLong MISS = new AtomicLong();

    private ClientChunkCache(NotEnoughBandwidthLegacyConfig cfg) {
        this.maxEntries = Math.max(8, cfg.chunkReferenceMaxClientCache);
    }

    public static ClientChunkCache get(Connection connection) {
        var cfg = NotEnoughBandwidthLegacyConfig.get();
        if (cfg == null || !cfg.chunkReferenceEnabled) {
            return null;
        }
        try {
            return CACHE.get(connection, () -> new ClientChunkCache(cfg));
        } catch (ExecutionException e) {
            return null;
        }
    }


    private record CachedChunk(ClientboundLevelChunkWithLightPacket packet, int size) {
    }

    private static void maybeLog() {
        var cfg = NotEnoughBandwidthLegacyConfig.get();
        if (cfg != null && cfg.debugLog && (APPLIED.get() + MISS.get()) % 100 == 0) {
            LogUtils.getLogger().info("NEBL chunk-ref client: cached={}, applied={}, miss={}", CACHED.get(), APPLIED.get(), MISS.get());
        }
    }

    public static int minBytes() {
        var cfg = NotEnoughBandwidthLegacyConfig.get();
        return cfg == null ? 4096 : Math.max(1, cfg.chunkReferenceMinBytes);
    }

    public synchronized void put(int x, int z, ClientboundLevelChunkWithLightPacket packet, int originalSize) {
        CACHED.incrementAndGet();
        long key = ChunkPos.asLong(x, z);
        entries.remove(key);
        entries.put(key, new CachedChunk(packet, originalSize));
        while (entries.size() > maxEntries) {
            var it = entries.entrySet().iterator();
            it.next();
            it.remove();
        }
    }

    public synchronized CachedChunk get(int x, int z) {
        return entries.get(ChunkPos.asLong(x, z));
    }

    public static void applyReference(ChunkReferencePayload payload, IPayloadContext context) {
        Connection connection = context.connection();
        ClientChunkCache cache = get(connection);
        CachedChunk cached = cache == null ? null : cache.get(payload.x(), payload.z());
        if (cached != null && context.listener() instanceof ClientPacketListener listener) {
            APPLIED.incrementAndGet();
            maybeLog();
            SimpleStatManager.inRaw(cached.size());
            try {
                listener.handleLevelChunkWithLight(cached.packet());
            } catch (Exception e) {
                connection.send(new ServerboundCustomPayloadPacket(new ChunkMissPayload(payload.x(), payload.z())));
            }
        } else {
            MISS.incrementAndGet();
            maybeLog();
            connection.send(new ServerboundCustomPayloadPacket(new ChunkMissPayload(payload.x(), payload.z())));
        }
    }
}