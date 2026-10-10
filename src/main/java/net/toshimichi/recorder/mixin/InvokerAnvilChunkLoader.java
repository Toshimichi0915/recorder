package net.toshimichi.recorder.mixin;

import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.world.World;
import net.minecraft.world.chunk.Chunk;
import net.minecraft.world.chunk.storage.AnvilChunkLoader;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

// saveChunk queues the write on a thread that drops a chunk saved again while its last save is still being written
@Mixin(AnvilChunkLoader.class)
public interface InvokerAnvilChunkLoader {

    @Invoker("writeChunkToNBT")
    void invokeWriteChunkToNBT(Chunk chunk, World world, NBTTagCompound level);
}
