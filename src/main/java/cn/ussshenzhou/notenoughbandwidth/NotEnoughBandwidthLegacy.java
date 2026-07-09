package cn.ussshenzhou.notenoughbandwidth;

import cn.ussshenzhou.notenoughbandwidth.config.ConfigHelper;
import cn.ussshenzhou.notenoughbandwidth.stat.ModKey;
import cn.ussshenzhou.notenoughbandwidth.util.ModNetworkRegistry;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModList;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.loading.FMLEnvironment;

import java.util.List;

/**
 * @author USS_Shenzhou
 */
@Mod(ModConstants.MOD_ID)
public class NotEnoughBandwidthLegacy {
    private static final List<String> INCOMPATIBLE_MODS = List.of(
            "badpackets",
            "bandwidthoptimizer",
            "hariplayer",
            "krypton_fnp",
            "krypton_hybrid",
            "zstd_compresser",
            "zstdnet",
            "zstdmc"
    );

    public NotEnoughBandwidthLegacy(IEventBus modEventBus) {
        ConfigHelper.loadConfig(new NotEnoughBandwidthLegacyConfig());
        modEventBus.addListener(ModNetworkRegistry::networkPacketRegistry);
        modEventBus.addListener(cn.ussshenzhou.network.ModNetworkRegistry::networkPacketRegistry);

        if (FMLEnvironment.dist == Dist.CLIENT) {
            modEventBus.addListener(ModKey::onRegisterKey);
            ModKey.register();
        }

        checkModCompatibility();
    }

    private void checkModCompatibility() {
        for (String modId : INCOMPATIBLE_MODS) {
            if (ModList.get().isLoaded(modId)) {
                throw new RuntimeException("Detected mod: " + modId + ", it is incompatible with NEBL. You need to remove this mod to continue!");
            }
        }
    }

}
