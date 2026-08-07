package cn.ussshenzhou.notenoughbandwidth.mixin;

import cn.ussshenzhou.notenoughbandwidth.NotEnoughBandwidthLegacyConfig;
import net.minecraft.server.network.ServerCommonPacketListenerImpl;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.Constant;
import org.spongepowered.asm.mixin.injection.ModifyConstant;

/**
 * @author MapleSugar365
 */
@Mixin(value = ServerCommonPacketListenerImpl.class, priority = 2000)
public abstract class ServerCommonPacketListenerImplMixin {

    @ModifyConstant(method = "keepConnectionAlive", constant = @Constant(longValue = 15000L), require = 0)
    private static long neblLengthenKeepAliveTimeout(long original) {
        var cfg = NotEnoughBandwidthLegacyConfig.get();
        if (cfg == null) {
            return original;
        }
        return Math.max(original, cfg.connectionTimeoutSeconds * 1000L);
    }
}