package cn.ussshenzhou.notenoughbandwidth.aggregation;

import com.mojang.logging.LogUtils;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.ConnectionProtocol;
import net.minecraft.network.ProtocolInfo;
import net.minecraft.network.codec.IdDispatchCodec;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.network.protocol.PacketType;
import net.minecraft.network.protocol.common.ClientboundCustomPayloadPacket;
import net.minecraft.network.protocol.common.ServerboundCustomPayloadPacket;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.network.registration.NetworkRegistry;

/**
 * @author USS_Shenzhou
 */
@SuppressWarnings("DataFlowIssue")
public class AggregatedEncodePacket {
    public final ResourceLocation type;
    private final boolean isMinecraft;
    private final Packet<?> packet;
    private final CustomPacketPayload payload;

    public AggregatedEncodePacket(Packet<?> p, ResourceLocation type) {
        if (p instanceof ServerboundCustomPayloadPacket(CustomPacketPayload pld)) {
            this.isMinecraft = false;
            this.packet = null;
            this.payload = pld;
        } else if (p instanceof ClientboundCustomPayloadPacket(CustomPacketPayload pld)) {
            this.isMinecraft = false;
            this.packet = null;
            this.payload = pld;
        } else {
            this.isMinecraft = true;
            this.packet = p;
            this.payload = null;
        }
        this.type = type;
    }

    public boolean isMinecraft() {
        return isMinecraft;
    }

    public Packet<?> getRawPacket() {
        return packet;
    }

    public CustomPacketPayload getPayload() {
        return payload;
    }

    public Packet<?> getVanillaPacket() {
        return isMinecraft ? packet : null;
    }

    public void encode(ByteBuf buf, ProtocolInfo<?> protocolInfo, PacketFlow packetFlow) {
        if (isMinecraft) {
            encodeVanilla(buf, protocolInfo);
        } else {
            encodeCustom(buf, packetFlow);
        }
    }

    /**
     * @see IdDispatchCodec
     */
    @SuppressWarnings({"rawtypes", "unchecked"})
    private void encodeVanilla(ByteBuf buf, ProtocolInfo<?> protocolInfo) {
        IdDispatchCodec<ByteBuf, Packet<?>, PacketType> vanillaCodec = (IdDispatchCodec) protocolInfo.codec();
        var type = vanillaCodec.typeGetter.apply(packet);
        int id = vanillaCodec.toId.getOrDefault(type, -1);
        if (id == -1) {
            LogUtils.getLogger().error("NEBL: Skipped EncoderException: Sending unknown packet " + type);
            return;
        }
        var entry = vanillaCodec.byId.get(id);
        var codec = (StreamCodec<ByteBuf, Packet<?>>) entry.serializer();
        codec.encode(buf, packet);
    }

    @SuppressWarnings({"UnstableApiUsage", "unchecked"})
    private void encodeCustom(ByteBuf buf, PacketFlow packetFlow) {
        var codec = (StreamCodec<ByteBuf, CustomPacketPayload>) NetworkRegistry.getCodec(payload.type().id(), ConnectionProtocol.PLAY, packetFlow);
        codec.encode(buf, payload);
    }

}
