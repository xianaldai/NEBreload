package cn.ussshenzhou.notenoughbandwidth.compat.replaymod;

import cn.ussshenzhou.notenoughbandwidth.ModConstants;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/**
 * @author MapleSugar365
 */
public record ReplayModRecordingStatusPayload(boolean recording) implements CustomPacketPayload {
    public static final Type<ReplayModRecordingStatusPayload> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(ModConstants.MOD_ID, "replaymod_recording_status"));
    public static final StreamCodec<ByteBuf, ReplayModRecordingStatusPayload> STREAM_CODEC =
            StreamCodec.composite(ByteBufCodecs.BOOL, ReplayModRecordingStatusPayload::recording, ReplayModRecordingStatusPayload::new);

    @Override
    public Type<ReplayModRecordingStatusPayload> type() {
        return TYPE;
    }

    public static void handle(ReplayModRecordingStatusPayload payload, IPayloadContext context) {
        if (context.flow() == PacketFlow.SERVERBOUND) {
            RecordingStatusManager.setRecording(context.connection(), payload.recording());
            context.reply(payload);
        } else {
            ReplayModCompat.onRecordingStatusAck();
        }
    }
}
