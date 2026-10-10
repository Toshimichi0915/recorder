package net.toshimichi.recorder;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiCreateWorld;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.nbt.CompressedStreamTools;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.network.Packet;
import net.minecraft.network.play.server.S01PacketJoinGame;
import net.minecraft.network.play.server.S07PacketRespawn;
import net.minecraft.network.play.server.S21PacketChunkData;
import net.minecraft.util.BlockPos;
import net.minecraft.world.World;
import net.minecraft.world.WorldSettings;
import net.minecraft.world.WorldType;
import net.minecraft.world.chunk.Chunk;
import net.minecraft.world.chunk.storage.AnvilChunkLoader;
import net.minecraft.world.chunk.storage.RegionFileCache;
import net.minecraft.world.storage.ISaveFormat;
import net.minecraft.world.storage.ISaveHandler;
import net.minecraft.world.storage.WorldInfo;
import net.toshimichi.recorder.mixin.AccessorChunkProviderClient;
import net.toshimichi.recorder.mixin.InvokerAnvilChunkLoader;
import org.apache.commons.io.FileUtils;

import java.io.DataOutputStream;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;

/**
 * Writes every chunk a replay loads into a single player world, each as it last stood before it was unloaded.
 * Only the last world counts: a lobby or dimension change throws away what came before it.
 */
final class WorldExport {

    private final ISaveHandler save;
    private final WorldInfo info;
    private final InvokerAnvilChunkLoader writer;
    final String directory;

    WorldExport(String name) {
        ISaveFormat format = Minecraft.getMinecraft().getSaveLoader();
        directory = GuiCreateWorld.getUncollidingSaveDirName(format, name);
        save = format.getSaveLoader(directory, false);
        // "3;minecraft:air;1;" is a superflat of nothing: no made-up terrain next to the recorded chunks
        info = new WorldInfo(new WorldSettings(0, WorldSettings.GameType.CREATIVE, false, false, WorldType.FLAT)
                .setWorldName("3;minecraft:air;1;").enableCommands(), name);
        // writeChunkToNBT touches no loader state, so one loader serves every dimension
        writer = (InvokerAnvilChunkLoader) new AnvilChunkLoader(save.getWorldDirectory());
    }

    // before the handler gets it: these packets throw chunks away
    void before(Packet<?> packet, World world) throws IOException {
        if (world == null) return;
        // a respawn into the same dimension keeps the client's world; any other is a new one, maybe a new lobby
        if (packet instanceof S01PacketJoinGame || packet instanceof S07PacketRespawn
                && ((S07PacketRespawn) packet).getDimensionID() != Minecraft.getMinecraft().thePlayer.dimension) {
            discard();
        } else if (packet instanceof S21PacketChunkData) {
            S21PacketChunkData data = (S21PacketChunkData) packet;
            if (data.func_149274_i() && data.getExtractedSize() == 0) save(world.getChunkFromChunkCoords(data.getChunkX(), data.getChunkZ()));
        }
    }

    private void discard() throws IOException {
        RegionFileCache.clearRegionFileReferences(); // the open region files would survive the delete
        File[] dirs = save.getWorldDirectory().listFiles((dir, name) -> name.equals("region") || name.startsWith("DIM"));
        if (dirs == null) return;
        for (File dir : dirs) FileUtils.deleteDirectory(dir);
    }

    void saveAll(World world) throws IOException {
        for (Chunk chunk : new ArrayList<>(((AccessorChunkProviderClient) world.getChunkProvider()).getChunkListing())) {
            save(chunk);
        }
    }

    // written on the spot, so a later save of the same chunk always wins
    private void save(Chunk chunk) throws IOException {
        if (chunk.isEmpty()) return; // the client's stand-in for a chunk it does not have
        World world = chunk.getWorld();
        String folder = world.provider.getSaveFolder();
        File dir = folder == null ? save.getWorldDirectory() : new File(save.getWorldDirectory(), folder);
        NBTTagCompound level = new NBTTagCompound();
        writer.invokeWriteChunkToNBT(chunk, world, level);
        NBTTagCompound root = new NBTTagCompound();
        root.setTag("Level", level);
        try (DataOutputStream out = RegionFileCache.getChunkOutputStream(dir, chunk.xPosition, chunk.zPosition)) {
            CompressedStreamTools.write(root, out);
        }
    }

    // the player joins where the recording ended, in that dimension, carrying what the recorded player carried
    void finish(EntityPlayer player) {
        NBTTagCompound tag = null;
        if (player != null) {
            tag = new NBTTagCompound();
            player.writeToNBT(tag);
            tag.setInteger("Dimension", player.worldObj.provider.getDimensionId()); // the puppet's own field is never set
            tag.removeTag("UUIDMost"); // the recorded player's, not whoever opens the world
            tag.removeTag("UUIDLeast");
            tag.removeTag("Riding"); // the mount is already in its chunk
            if (player.worldObj.provider.getDimensionId() == 0) info.setSpawn(new BlockPos(player));
        }
        save.saveWorldInfoWithPlayer(info, tag); // the anvil handler also stamps the format version
        RegionFileCache.clearRegionFileReferences(); // flushes and closes the region files
    }
}
