package cn.ussshenzhou.notenoughbandwidth.mapping;

import cn.ussshenzhou.notenoughbandwidth.NotEnoughBandwidthLegacyConfig;
import com.google.common.cache.Cache;
import com.google.common.cache.CacheBuilder;
import net.minecraft.network.Connection;
import net.minecraft.network.protocol.PacketFlow;

import java.util.concurrent.ExecutionException;

/**
 * @author MapleSugar365
 */
public class MappingSessionHolder {
    private static final Cache<Connection, MappingSessionHolder> CACHE = CacheBuilder.newBuilder().weakKeys().build();

    private final KineticTemplateDictionarySession[] sessions = new KineticTemplateDictionarySession[2];

    private MappingSessionHolder() {
        sessions[0] = new KineticTemplateDictionarySession();
        sessions[1] = new KineticTemplateDictionarySession();
    }

    public static KineticTemplateDictionarySession get(Connection connection, PacketFlow flow) {
        var cfg = NotEnoughBandwidthLegacyConfig.get();
        if (cfg == null || !cfg.packetDictionaryEnabled) {
            return null;
        }
        try {
            return CACHE.get(connection, MappingSessionHolder::new).sessions[flow.ordinal()];
        } catch (ExecutionException e) {
            return null;
        }
    }

    public static void clear(Connection connection) {
        CACHE.invalidate(connection);
    }
}