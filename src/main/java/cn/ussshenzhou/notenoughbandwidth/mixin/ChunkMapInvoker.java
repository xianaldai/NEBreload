package cn.ussshenzhou.notenoughbandwidth.mixin;

import net.minecraft.server.level.ChunkMap;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.ChunkPos;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/**
 * @author MapleSugar365
 */
@Mixin(ChunkMap.class)
public interface ChunkMapInvoker {
    @Invoker("markChunkPendingToSend")
    void neblMarkChunkPendingToSend(ServerPlayer player, ChunkPos pos);
}