package cn.ussshenzhou.notenoughbandwidth.mixin;

import cn.ussshenzhou.notenoughbandwidth.NotEnoughBandwidthLegacyConfig;
import cn.ussshenzhou.notenoughbandwidth.indextype.CustomPacketPrefixHelper;
import net.minecraft.network.ConnectionProtocol;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * @author USS_Shenzhou
 */
@Mixin(targets = "net.minecraft.network.protocol.common.custom.CustomPacketPayload$1")
public class CustomPacketPayloadMixin {

    @SuppressWarnings("AlibabaLowerCamelCaseVariableNaming")
    @Shadow
    @Final
    ConnectionProtocol val$protocol;

    @Redirect(method = "writeCap(Lnet/minecraft/network/FriendlyByteBuf;Lnet/minecraft/network/protocol/common/custom/CustomPacketPayload$Type;Lnet/minecraft/network/protocol/common/custom/CustomPacketPayload;)V", at = @At(value = "INVOKE", target = "Lnet/minecraft/network/FriendlyByteBuf;writeResourceLocation(Lnet/minecraft/resources/ResourceLocation;)Lnet/minecraft/network/FriendlyByteBuf;"))
    private FriendlyByteBuf neblIndexedHeaderEncode(FriendlyByteBuf buf, ResourceLocation identifier) {
        if (val$protocol != ConnectionProtocol.PLAY || NotEnoughBandwidthLegacyConfig.skipType(identifier.toString())) {
            buf.writeResourceLocation(identifier);
            return buf;
        }
        CustomPacketPrefixHelper.write(identifier, buf);
        return buf;
    }

    @Redirect(method = "decode(Lnet/minecraft/network/FriendlyByteBuf;)Lnet/minecraft/network/protocol/common/custom/CustomPacketPayload;", at = @At(value = "INVOKE", target = "Lnet/minecraft/network/FriendlyByteBuf;readResourceLocation()Lnet/minecraft/resources/ResourceLocation;"))
    private ResourceLocation neblIndexedHeaderDecode(FriendlyByteBuf buf) {
        var tryRead = new FriendlyByteBuf(buf.retainedDuplicate());
        try {
            var tryType = tryRead.readResourceLocation();
            if (NotEnoughBandwidthLegacyConfig.skipType(tryType.toString())) {
                return buf.readResourceLocation();
            }
        } catch (Exception ignored) {
        } finally {
            tryRead.release();
        }
        if (val$protocol != ConnectionProtocol.PLAY) {
            return buf.readResourceLocation();
        }
        return CustomPacketPrefixHelper.read(buf);
    }
}
