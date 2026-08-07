package cn.ussshenzhou.notenoughbandwidth.chunk;

import cn.ussshenzhou.notenoughbandwidth.ModConstants;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/**
 * @author MapleSugar365
 */
public record ChunkReferencePayload(int x, int z) implements CustomPacketPayload {
    public static final Type<ChunkReferencePayload> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(ModConstants.MOD_ID, "chunk_reference"));
    public static final StreamCodec<ByteBuf, ChunkReferencePayload> STREAM_CODEC = StreamCodec.composite(
            ByteBufCodecs.VAR_INT, ChunkReferencePayload::x,
            ByteBufCodecs.VAR_INT, ChunkReferencePayload::z,
            ChunkReferencePayload::new
    );

    public static void handle(ChunkReferencePayload payload, IPayloadContext context) {
        context.enqueueWork(() -> ClientChunkCache.applyReference(payload, context));
    }

    @Override
    public Type<ChunkReferencePayload> type() {
        return TYPE;
    }
}