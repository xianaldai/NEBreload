package cn.ussshenzhou.notenoughbandwidth.mixin;

import cn.ussshenzhou.notenoughbandwidth.NotEnoughBandwidthLegacyConfig;
import net.minecraft.network.protocol.common.custom.DiscardedPayload;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

/**
 * Keep discarded clientbound custom payload decoding in line with NEBL's packet limit.
 */
@Mixin(DiscardedPayload.class)
public class DiscardedPayloadMixin {

    @ModifyVariable(method = "lambda$codec$1", at = @At("HEAD"), argsOnly = true, ordinal = 0)
    private static int neblUseConfiguredClientboundPayloadLimit(int maxSize) {
        if (maxSize != 1_048_576) {
            return maxSize;
        }
        var cfg = NotEnoughBandwidthLegacyConfig.get();
        return cfg == null ? maxSize : Math.max(maxSize, cfg.getMaxPacketSize());
    }
}
