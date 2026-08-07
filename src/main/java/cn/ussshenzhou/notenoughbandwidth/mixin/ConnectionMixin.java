package cn.ussshenzhou.notenoughbandwidth.mixin;

import cn.ussshenzhou.notenoughbandwidth.NotEnoughBandwidthLegacyConfig;
import cn.ussshenzhou.notenoughbandwidth.aggregation.AggregationManager;
import cn.ussshenzhou.notenoughbandwidth.compat.replaymod.ReplayModCompat;
import cn.ussshenzhou.notenoughbandwidth.compat.replaymod.RecordingStatusManager;
import cn.ussshenzhou.notenoughbandwidth.util.PacketUtil;
import io.netty.channel.local.LocalAddress;
import net.minecraft.network.Connection;
import net.minecraft.network.ConnectionProtocol;
import net.minecraft.network.PacketListener;
import net.minecraft.network.PacketSendListener;
import net.minecraft.network.protocol.BundlePacket;
import net.minecraft.network.protocol.Packet;
import org.jetbrains.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.fml.loading.FMLEnvironment;

import java.net.SocketAddress;

/**
 * @author USS_Shenzhou
 */
@Mixin(value = Connection.class, priority = 1)
public abstract class ConnectionMixin {

    @Shadow
    @Nullable
    private volatile PacketListener packetListener;

    @Shadow
    public abstract void send(Packet<?> packet, @Nullable PacketSendListener listener, boolean flush);

    @Shadow
    public abstract SocketAddress getRemoteAddress();

    @Inject(method = "send(Lnet/minecraft/network/protocol/Packet;Lnet/minecraft/network/PacketSendListener;Z)V", at = @At("HEAD"), cancellable = true)
    private void neblPacketAggregate(Packet<?> packet, @Nullable PacketSendListener listener, boolean flush, CallbackInfo ci) {
        if (this.getRemoteAddress() instanceof LocalAddress || this.packetListener == null || this.packetListener.protocol() != ConnectionProtocol.PLAY) {
            return;
        }
        var connection = (Connection) (Object) this;
        String type = PacketUtil.getTrueType(packet).toString();
        if (NotEnoughBandwidthLegacyConfig.bypassType(type)) {
            return;
        }
        boolean recordingMovementPacket = NotEnoughBandwidthLegacyConfig.MOVEMENT_PACKETS.contains(type)
                && (FMLEnvironment.dist == Dist.CLIENT && ReplayModCompat.isRecordingActive()
                || FMLEnvironment.dist == Dist.DEDICATED_SERVER && RecordingStatusManager.isRecording(connection));
        if (NotEnoughBandwidthLegacyConfig.skipType(type) || recordingMovementPacket) {
            AggregationManager.flushConnection((Connection) (Object) this);
            return;
        }
        if (packet instanceof BundlePacket<?> bundlePacket) {
            bundlePacket.subPackets().forEach(p -> this.send(p, listener, flush));
            ci.cancel();
            return;
        }
        AggregationManager.takeOver(packet, (Connection) (Object) this);
        ci.cancel();
    }

    @Inject(method = "exceptionCaught(Lio/netty/channel/ChannelHandlerContext;Ljava/lang/Throwable;)V", at = @At("HEAD"), cancellable = true)
    private void neblIgnoreRecordingStatusEncodeFailure(io.netty.channel.ChannelHandlerContext ctx, Throwable cause, CallbackInfo ci) {
        if (isNeblPayloadEncodeFailure(cause)) {
            ci.cancel();
        }
    }

    private static boolean isNeblPayloadEncodeFailure(Throwable cause) {
        Throwable t = cause;
        while (t != null) {
            String msg = t.getMessage();
            if (msg != null && msg.startsWith("Failed encoding custom payload nebl:")) {
                return true;
            }
            t = t.getCause();
        }
        return false;
    }
}
