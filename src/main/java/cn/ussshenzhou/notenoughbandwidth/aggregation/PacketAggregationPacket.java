package cn.ussshenzhou.notenoughbandwidth.aggregation;

import cn.ussshenzhou.notenoughbandwidth.NotEnoughBandwidthLegacyConfig;
import cn.ussshenzhou.notenoughbandwidth.ModConstants;
import cn.ussshenzhou.notenoughbandwidth.chunk.ChunkReferencePayload;
import cn.ussshenzhou.notenoughbandwidth.chunk.ChunkReferenceStore;
import cn.ussshenzhou.notenoughbandwidth.config.ConfigHelper;
import cn.ussshenzhou.notenoughbandwidth.indextype.CustomPacketPrefixHelper;
import cn.ussshenzhou.notenoughbandwidth.stat.SimpleStatManager;
import cn.ussshenzhou.notenoughbandwidth.mapping.KineticTemplateDictionarySession;
import cn.ussshenzhou.notenoughbandwidth.mapping.MappingSessionHolder;
import cn.ussshenzhou.notenoughbandwidth.zstd.ZstdHelper;
import io.netty.buffer.Unpooled;
import com.mojang.logging.LogUtils;
import com.mojang.logging.annotations.MethodsReturnNonnullByDefault;
import io.netty.buffer.ByteBufAllocator;
import net.minecraft.network.Connection;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.ProtocolInfo;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.network.protocol.game.ClientboundLevelChunkWithLightPacket;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.network.filters.GenericPacketSplitter;
import net.neoforged.neoforge.network.handling.IPayloadContext;

import java.util.ArrayList;

/**
 * @author USS_Shenzhou
 */
@MethodsReturnNonnullByDefault
public class PacketAggregationPacket implements CustomPacketPayload {
    private static final int MAX_SESSION_PACKET_BYTES = 4 * 1024 * 1024;
    public static final Type<PacketAggregationPacket> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(ModConstants.MOD_ID, "packet_aggregation_packet"));

    public static boolean isDebug() {
        var config = ConfigHelper.getConfigRead(NotEnoughBandwidthLegacyConfig.class);
        return config != null && config.debugLog;
    }

    @Override
    public Type<PacketAggregationPacket> type() {
        return TYPE;
    }

    private int bakedSize;
    private long rawOriginalBytes;
    private long rawInBytes;
    //----------------------------------------encode----------------------------------------
    private static final StackWalker WALKER = StackWalker.getInstance(StackWalker.Option.RETAIN_CLASS_REFERENCE);
    private final ArrayList<AggregatedEncodePacket> packetsToEncode;
    private final ProtocolInfo<?> protocolInfo;
    private Connection connection;

    public PacketAggregationPacket(ArrayList<AggregatedEncodePacket> packetsToEncode, ProtocolInfo<?> protocolInfo, Connection connection) {
        this.packetsToEncode = packetsToEncode;
        this.protocolInfo = protocolInfo;
        this.connection = connection;
    }

    /**
     * <pre>
     * ┌---┬-----┬-----┬----┬----┬----┬----┬----...
     * │ B │ (S) │  p0 │ s0 │ d0 │ p1 │ s1 │ d1 ...
     * └---┴-----┴-----┴----┴----┴----┴----┴----...
     *           └--packet 1---┘└--packet 2---┘
     *           └----------compressed----------┘
     *
     * B = bool, whether compressed
     * S = varint, size of compressed buf. not exist if uncompressed.
     * p = prefix (medium/int/utf-8)， type of this subpacket
     * s = varint, size of this subpacket
     * d = bytes, data of this subpacket
     * </pre>
     */
    @SuppressWarnings("UnstableApiUsage")
    public void encode(RegistryFriendlyByteBuf buffer) {
        //skip GenericPacketSplitter
        if (WALKER.walk(s -> s.anyMatch(frame -> frame.getDeclaringClass() == GenericPacketSplitter.class))) {
            return;
        }
        var rawBuf = new RegistryFriendlyByteBuf(ByteBufAllocator.DEFAULT.buffer(), buffer.registryAccess(), buffer.getConnectionType());
        var session = MappingSessionHolder.get(connection, connection.getSending());
        packetsToEncode.forEach(p -> encodePackets(rawBuf, p, session));

        int rawSize = rawBuf.readableBytes();
        boolean compress = rawSize >= 32;
        // B
        buffer.writeBoolean(compress);
        if (compress) {
            // S
            buffer.writeVarInt(rawSize);
            var compressedBuf = new FriendlyByteBuf(ZstdHelper.compress(connection, rawBuf));
            int compressedSize = compressedBuf.readableBytes();
            logCompressRatio(rawSize, compressedSize);
            buffer.writeBytes(compressedBuf);
            this.bakedSize = compressedSize;
            compressedBuf.release();
        } else {
            buffer.writeBytes(rawBuf);
            this.bakedSize = rawSize;
        }
        SimpleStatManager.outRaw((int) rawOriginalBytes);
        rawBuf.release();
    }

    private static void logCompressRatio(int rawSize, int compressedSize) {
        if (ConfigHelper.getConfigRead(NotEnoughBandwidthLegacyConfig.class).debugLog) {
            var log = "Packet aggregated and compressed: "
                    + rawSize
                    + " bytes -> "
                    + compressedSize
                    + " bytes ( "
                    + String.format("%.2f", 100f * compressedSize / rawSize)
                    + "%)";
            if (isDebug()) LogUtils.getLogger().debug(log);
        }
    }

    private void encodePackets(RegistryFriendlyByteBuf raw, AggregatedEncodePacket packet, KineticTemplateDictionarySession session) {
        var type = packet.type;
        var d = new RegistryFriendlyByteBuf(ByteBufAllocator.DEFAULT.buffer(), raw.registryAccess(), raw.getConnectionType());
        try {
            packet.encode(d, protocolInfo, connection.getSending());
        } catch (Exception e) {
            if (isDebug()) LogUtils.getLogger().warn("NEBL: Skipped packet {} due to encode failure: {}", type, e.getMessage());
            d.release();
            return;
        }
        byte[] data = new byte[d.readableBytes()];
        d.getBytes(0, data);
        rawOriginalBytes += data.length;
        ChunkReferenceStore chunkStore = null;
        long packedPos = 0L;
        long chunkHash = 0L;
        boolean chunkCandidate = false;
        if (packet.getVanillaPacket() instanceof ClientboundLevelChunkWithLightPacket chunkPacket && data.length >= ChunkReferenceStore.minBytes()) {
            chunkStore = ChunkReferenceStore.get(connection);
            if (chunkStore != null) {
                chunkHash = ChunkReferenceStore.hash(data);
                packedPos = ChunkPos.asLong(chunkPacket.getX(), chunkPacket.getZ());
                chunkCandidate = true;
                if (chunkStore.isKnown(packedPos, chunkHash)) {
                    ChunkReferenceStore.recordDecision(true, data.length, false);
                    // reference instead of the full chunk
                    CustomPacketPrefixHelper.write(ChunkReferencePayload.TYPE.id(), raw);
                    var refBuf = Unpooled.buffer();
                    ChunkReferencePayload.STREAM_CODEC.encode(refBuf, new ChunkReferencePayload(chunkPacket.getX(), chunkPacket.getZ()));
                    int refLen = refBuf.readableBytes();
                    raw.writeVarInt(1 + refLen);
                    raw.writeByte(0);
                    raw.writeBytes(refBuf);
                    refBuf.release();
                    d.release();
                    return;
                }
                ChunkReferenceStore.recordDecision(false, data.length, chunkStore.has(packedPos));
            }
        }
        // p
        CustomPacketPrefixHelper.write(type, raw);
        if (chunkCandidate) {
            chunkStore.record(packedPos, chunkHash);
        }
        if (session != null && data.length <= MAX_SESSION_PACKET_BYTES) {
            byte[] frame = session.encode(data);
            raw.writeVarInt(1 + frame.length);
            raw.writeByte(1);
            raw.writeBytes(frame);
        } else {
            raw.writeVarInt(1 + data.length);
            raw.writeByte(0);
            raw.writeBytes(data);
        }
        d.release();
    }

    //----------------------------------------decode----------------------------------------
    private RegistryFriendlyByteBuf data;

    public PacketAggregationPacket(RegistryFriendlyByteBuf buffer) {
        this.protocolInfo = null;
        this.packetsToEncode = null;
        this.data = new RegistryFriendlyByteBuf(buffer.retainedDuplicate(), buffer.registryAccess(), buffer.getConnectionType());
        buffer.readerIndex(buffer.writerIndex());
    }

    //----------------------------------------handle----------------------------------------
    public void handler(IPayloadContext context) {
        this.connection = context.connection();
        this.rawInBytes = 0L;
        var session = MappingSessionHolder.get(this.connection, this.connection.getReceiving());
        SimpleStatManager.inRaw(bakedSize - data.readableBytes());
        // B
        boolean compressed = data.readBoolean();
        RegistryFriendlyByteBuf raw;
        if (compressed) {
            // S
            int size = data.readVarInt();
            var compressedData = data.retainedDuplicate();
            try {
                raw = new RegistryFriendlyByteBuf(ZstdHelper.decompress(connection, compressedData, size), data.registryAccess(), data.getConnectionType());
            } catch (Exception e) {
                LogUtils.getLogger().error("NEBL: Failed to decompress packet aggregation from {}, clearing cache and skipping. This is expected after server switches.", connection.getRemoteAddress());
                LogUtils.getLogger().error("NEBL: Decompression error details:", e);
                ZstdHelper.clearCache(connection);
                AggregationManager.clearCache(connection);
                data.release();
                return;
            } finally {
                compressedData.release();
            }
        } else {
            raw = new RegistryFriendlyByteBuf(data.retain(), data.registryAccess(), data.getConnectionType());
        }
        var protocolInfo = context.connection().getInboundProtocol();
        var packetsToHandle = new ArrayList<AggregatedDecodePacket>();
        while (raw.readableBytes() > 0) {
            deAggregatePackets(raw, packetsToHandle, session);
        }
        SimpleStatManager.inRaw((int) rawInBytes);
        data.release();
        raw.release();
        this.handlePackets(packetsToHandle, protocolInfo, context);
    }

    private void deAggregatePackets(RegistryFriendlyByteBuf buf, ArrayList<AggregatedDecodePacket> packetsToHandle, KineticTemplateDictionarySession session) {
        // p
        var type = CustomPacketPrefixHelper.read(buf);
        // s
        int size = buf.readVarInt();
        if (size < 1 || size > buf.readableBytes()) {
            throw new IllegalStateException("NEBL: Invalid aggregated packet size " + size);
        }
        // f
        byte flag = buf.readByte();
        byte[] payload = new byte[size - 1];
        buf.readBytes(payload);
        byte[] data;
        if (flag == 0) {
            data = payload;
        } else if (flag == 1) {
            try {
                data = session.decode(payload);
            } catch (Exception e) {
                LogUtils.getLogger().warn("NEBL: Failed to decode mapping frame for {}: {}", type, e.toString());
                return;
            }
        } else {
            LogUtils.getLogger().warn("NEBL: Unknown sub-packet flag {} for {}", flag, type);
            return;
        }
        rawInBytes += data.length;
        var dataBuf = new RegistryFriendlyByteBuf(Unpooled.wrappedBuffer(data), this.data.registryAccess(), this.data.getConnectionType());
        packetsToHandle.add(new AggregatedDecodePacket(type, dataBuf));
    }

    private void handlePackets(ArrayList<AggregatedDecodePacket> packetsToHandle, ProtocolInfo<?> protocolInfo, IPayloadContext context) {
        packetsToHandle.forEach(packet -> {
            packet.handle(protocolInfo, context);
            packet.getData().release();
        });
    }

    public int getBakedSize() {
        return bakedSize;
    }

    public void setBakedSize(int bakedSize) {
        this.bakedSize = bakedSize;
    }
}
