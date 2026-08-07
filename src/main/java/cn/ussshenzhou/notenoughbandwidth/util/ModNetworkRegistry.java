package cn.ussshenzhou.notenoughbandwidth.util;

import cn.ussshenzhou.notenoughbandwidth.ModConstants;
import cn.ussshenzhou.notenoughbandwidth.aggregation.PacketAggregationPacket;
import cn.ussshenzhou.notenoughbandwidth.chunk.ChunkMissPayload;
import cn.ussshenzhou.notenoughbandwidth.chunk.ChunkReferencePayload;
import cn.ussshenzhou.notenoughbandwidth.compat.replaymod.ReplayModRecordingStatusPayload;
import net.minecraft.network.codec.StreamCodec;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.registration.HandlerThread;

/**
 * @author USS_Shenzhou
 */
public class ModNetworkRegistry {

    public static void networkPacketRegistry(RegisterPayloadHandlersEvent event) {
        var registrar = event.registrar(ModConstants.MOD_ID).executesOn(HandlerThread.NETWORK);

        registrar.playBidirectional(PacketAggregationPacket.TYPE, StreamCodec.ofMember(PacketAggregationPacket::encode, PacketAggregationPacket::new), PacketAggregationPacket::handler);
        registrar.playBidirectional(ReplayModRecordingStatusPayload.TYPE, ReplayModRecordingStatusPayload.STREAM_CODEC, ReplayModRecordingStatusPayload::handle);
        registrar.playToClient(ChunkReferencePayload.TYPE, ChunkReferencePayload.STREAM_CODEC, ChunkReferencePayload::handle);
        registrar.playToServer(ChunkMissPayload.TYPE, ChunkMissPayload.STREAM_CODEC, ChunkMissPayload::handle);
    }
}
