package cn.ussshenzhou.notenoughbandwidth.mixin;

import cn.ussshenzhou.notenoughbandwidth.NotEnoughBandwidthLegacyConfig;
import io.netty.channel.Channel;
import io.netty.channel.ChannelPipeline;
import io.netty.handler.timeout.ReadTimeoutHandler;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * @author MapleSugar365
 */
@Mixin(targets = "net/minecraft/network/Connection$1")
public abstract class ConnectionReadTimeoutMixin {

    @Inject(method = "initChannel", at = @At("RETURN"), require = 0)
    private void neblOverrideReadTimeout(Channel channel, CallbackInfo ci) {
        var cfg = NotEnoughBandwidthLegacyConfig.get();
        if (cfg == null) {
            return;
        }
        ChannelPipeline pipeline = channel.pipeline();
        if (pipeline.get("timeout") instanceof ReadTimeoutHandler) {
            pipeline.replace("timeout", "timeout", new ReadTimeoutHandler(Math.max(30, cfg.connectionTimeoutSeconds)));
        }
    }
}