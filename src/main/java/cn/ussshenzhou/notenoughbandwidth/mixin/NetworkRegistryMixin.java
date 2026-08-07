package cn.ussshenzhou.notenoughbandwidth.mixin;

import cn.ussshenzhou.notenoughbandwidth.aggregation.AggregationManager;
import cn.ussshenzhou.notenoughbandwidth.compat.replaymod.ReplayModCompat;
import cn.ussshenzhou.notenoughbandwidth.compat.replaymod.ReplayModRecordingStatusPayload;
import cn.ussshenzhou.notenoughbandwidth.compat.replaymod.RecordingStatusManager;
import cn.ussshenzhou.notenoughbandwidth.indextype.NamespaceIndexManager;
import cn.ussshenzhou.notenoughbandwidth.zstd.ZstdHelper;
import com.llamalad7.mixinextras.sugar.Local;
import net.minecraft.network.Connection;
import net.minecraft.network.ConnectionProtocol;
import net.minecraft.network.protocol.configuration.ClientConfigurationPacketListener;
import net.minecraft.network.protocol.configuration.ServerConfigurationPacketListener;
import net.neoforged.neoforge.network.payload.ModdedNetworkQueryComponent;
import net.neoforged.neoforge.network.registration.NetworkPayloadSetup;
import net.neoforged.neoforge.network.registration.NetworkRegistry;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.ArrayList;
import java.util.Map;
import java.util.Set;

/**
 * @author USS_Shenzhou
 */
@SuppressWarnings("UnstableApiUsage")
@Mixin(NetworkRegistry.class)
public class NetworkRegistryMixin {

    // server init
    @Inject(method = "initializeNeoForgeConnection(Lnet/minecraft/network/protocol/configuration/ServerConfigurationPacketListener;Ljava/util/Map;)V", at = @At("TAIL"))
    private static void neblServerInitialize(ServerConfigurationPacketListener listener, Map<ConnectionProtocol, Set<ModdedNetworkQueryComponent>> clientChannels, CallbackInfo ci, @Local(name = "setup") NetworkPayloadSetup setup) {
        Connection connection = listener.getConnection();
        RecordingStatusManager.remove(connection);
        ZstdHelper.clearCache(connection);
        AggregationManager.clearCache(connection);
        NamespaceIndexManager.init(new ArrayList<>(setup.channels().get(ConnectionProtocol.PLAY).keySet()));
        AggregationManager.init();
    }

    // client init
    @Inject(method = "initializeNeoForgeConnection(Lnet/minecraft/network/protocol/configuration/ClientConfigurationPacketListener;Lnet/neoforged/neoforge/network/registration/NetworkPayloadSetup;)V", at = @At("TAIL"))
    private static void neblClientInitialize(ClientConfigurationPacketListener listener, NetworkPayloadSetup setup, CallbackInfo ci) {
        Connection connection = listener.getConnection();
        ReplayModCompat.setClientConnection(connection);
        var playChannels = setup.channels().get(ConnectionProtocol.PLAY);
        ReplayModCompat.setServerSupportsNebl(playChannels != null && playChannels.containsKey(ReplayModRecordingStatusPayload.TYPE.id()));
        RecordingStatusManager.remove(connection);
        ZstdHelper.clearCache(connection);
        AggregationManager.clearCache(connection);
        NamespaceIndexManager.init(new ArrayList<>(setup.channels().get(ConnectionProtocol.PLAY).keySet()));
        AggregationManager.init();
    }
}
