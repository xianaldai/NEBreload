package cn.ussshenzhou.notenoughbandwidth.aggregation;

import cn.ussshenzhou.notenoughbandwidth.NotEnoughBandwidthLegacyConfig;
import cn.ussshenzhou.notenoughbandwidth.ModConstants;
import cn.ussshenzhou.notenoughbandwidth.config.ConfigHelper;
import cn.ussshenzhou.notenoughbandwidth.indextype.CustomPacketPrefixHelper;
import cn.ussshenzhou.notenoughbandwidth.stat.SimpleStatManager;
import cn.ussshenzhou.notenoughbandwidth.zstd.ZstdHelper;
import com.mojang.logging.LogUtils;
import com.mojang.logging.annotations.MethodsReturnNonnullByDefault;
import io.netty.buffer.ByteBufAllocator;
import net.minecraft.network.Connection;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.ProtocolInfo;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.network.filters.GenericPacketSplitter;
import net.neoforged.neoforge.network.handling.IPayloadContext;

import java.util.ArrayList;

/**
 * @author USS_Shenzhou
 */
@MethodsReturnNonnullByDefault
public class PacketAggregationPacket implements CustomPacketPayload {
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
        var splitterProbe = WALKER.walk(s -> s.anyMatch(frame -> frame.getDeclaringClass() == GenericPacketSplitter.class));
        var rawBuf = new RegistryFriendlyByteBuf(ByteBufAllocator.DEFAULT.buffer(), buffer.registryAccess(), buffer.getConnectionType());
        packetsToEncode.forEach(p -> encodePackets(rawBuf, p));

        int rawSize = rawBuf.readableBytes();
        boolean compress = !splitterProbe && rawSize >= 32;
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
        if (!splitterProbe) {
            SimpleStatManager.outRaw(rawSize);
        }
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

    private void encodePackets(RegistryFriendlyByteBuf raw, AggregatedEncodePacket packet) {
        var type = packet.type;
        var d = new RegistryFriendlyByteBuf(ByteBufAllocator.DEFAULT.buffer(), raw.registryAccess(), raw.getConnectionType());
        try {
            packet.encode(d, protocolInfo, connection.getSending());
        } catch (Exception e) {
            if (isDebug()) LogUtils.getLogger().warn("NEBL: Skipped packet {} due to encode failure: {}", type, e.getMessage());
            d.release();
            return;
        }
        // p
        CustomPacketPrefixHelper.write(type, raw);
        // s
        raw.writeVarInt(d.readableBytes());
        // d
        raw.writeBytes(d);
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
        SimpleStatManager.inRaw(raw.readableBytes());
        var protocolInfo = context.connection().getInboundProtocol();
        var packetsToHandle = new ArrayList<AggregatedDecodePacket>();
        while (raw.readableBytes() > 0) {
            deAggregatePackets(raw, packetsToHandle);
        }
        data.release();
        raw.release();
        this.handlePackets(packetsToHandle, protocolInfo, context);
    }

    private void deAggregatePackets(RegistryFriendlyByteBuf buf, ArrayList<AggregatedDecodePacket> packetsToHandle) {
        // p
        var type = CustomPacketPrefixHelper.read(buf);
        // s
        var size = buf.readVarInt();
        // d
        var data = new RegistryFriendlyByteBuf(buf.readRetainedSlice(size), this.data.registryAccess(), this.data.getConnectionType());
        packetsToHandle.add(new AggregatedDecodePacket(type, data));
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
