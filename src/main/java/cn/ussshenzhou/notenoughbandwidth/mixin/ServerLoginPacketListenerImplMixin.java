package cn.ussshenzhou.notenoughbandwidth.mixin;

import cn.ussshenzhou.notenoughbandwidth.NotEnoughBandwidthLegacyConfig;
import net.minecraft.server.network.ServerLoginPacketListenerImpl;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.Constant;
import org.spongepowered.asm.mixin.injection.ModifyConstant;

/**
 * @author MapleSugar365
 */
@Mixin(value = ServerLoginPacketListenerImpl.class, priority = 2000)
public abstract class ServerLoginPacketListenerImplMixin {

    @ModifyConstant(method = "tick", constant = @Constant(intValue = 600), require = 0)
    private static int neblLengthenLoginTimeout(int original) {
        var cfg = NotEnoughBandwidthLegacyConfig.get();
        if (cfg == null) {
            return original;
        }
        return Math.max(original, cfg.loginTimeoutSeconds * 20);
    }
}
