package cn.ussshenzhou.notenoughbandwidth.chunk;

import cn.ussshenzhou.notenoughbandwidth.ModConstants;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ChunkMap;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.world.level.ChunkPos;
import cn.ussshenzhou.notenoughbandwidth.mixin.ChunkMapInvoker;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/**
 * @author MapleSugar365
 */
public record ChunkMissPayload(int x, int z) implements CustomPacketPayload {
    public static final Type<ChunkMissPayload> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(ModConstants.MOD_ID, "chunk_miss"));
    public static final StreamCodec<ByteBuf, ChunkMissPayload> STREAM_CODEC = StreamCodec.composite(
            ByteBufCodecs.VAR_INT, ChunkMissPayload::x,
            ByteBufCodecs.VAR_INT, ChunkMissPayload::z,
            ChunkMissPayload::new
    );

    public static void handle(ChunkMissPayload payload, IPayloadContext context) {
        ChunkReferenceStore store = ChunkReferenceStore.get(context.connection());
        ChunkPos pos = new ChunkPos(payload.x(), payload.z());
        if (store != null) {
            store.invalidate(ChunkPos.asLong(payload.x(), payload.z()));
        }
        if (context.listener() instanceof ServerGamePacketListenerImpl listener) {
            ServerPlayer player = listener.getPlayer();
            if (player != null && player.serverLevel() != null) {
                ChunkMap chunkMap = player.serverLevel().getChunkSource().chunkMap;
                ((ChunkMapInvoker) chunkMap).neblMarkChunkPendingToSend(player, pos);
            }
        }
    }

    @Override
    public Type<ChunkMissPayload> type() {
        return TYPE;
    }
}