package net.toshimichi.recorder;

import com.mojang.authlib.GameProfile;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import net.minecraft.client.Minecraft;
import net.minecraft.client.entity.EntityOtherPlayerMP;
import net.minecraft.client.entity.EntityPlayerSP;
import net.minecraft.client.multiplayer.WorldClient;
import net.minecraft.client.network.NetworkPlayerInfo;
import net.minecraft.entity.DataWatcher;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityList;
import net.minecraft.entity.EntityLiving;
import net.minecraft.entity.EntityTrackerEntry;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.entity.player.EnumPlayerModelParts;
import net.minecraft.item.ItemStack;
import net.minecraft.network.EnumConnectionState;
import net.minecraft.network.EnumPacketDirection;
import net.minecraft.network.Packet;
import net.minecraft.network.PacketBuffer;
import net.minecraft.network.play.client.C17PacketCustomPayload;
import net.minecraft.network.play.server.S01PacketJoinGame;
import net.minecraft.network.play.server.S03PacketTimeUpdate;
import net.minecraft.network.play.server.S04PacketEntityEquipment;
import net.minecraft.network.play.server.S06PacketUpdateHealth;
import net.minecraft.network.play.server.S08PacketPlayerPosLook;
import net.minecraft.network.play.server.S05PacketSpawnPosition;
import net.minecraft.network.play.server.S09PacketHeldItemChange;
import net.minecraft.network.play.server.S0APacketUseBed;
import net.minecraft.network.play.server.S0CPacketSpawnPlayer;
import net.minecraft.network.play.server.S0EPacketSpawnObject;
import net.minecraft.network.play.server.S0FPacketSpawnMob;
import net.minecraft.network.play.server.S10PacketSpawnPainting;
import net.minecraft.network.play.server.S11PacketSpawnExperienceOrb;
import net.minecraft.network.play.server.S12PacketEntityVelocity;
import net.minecraft.network.play.server.S18PacketEntityTeleport;
import net.minecraft.network.play.server.S19PacketEntityHeadLook;
import net.minecraft.network.play.server.S1BPacketEntityAttach;
import net.minecraft.network.play.server.S20PacketEntityProperties;
import net.minecraft.network.play.server.S1CPacketEntityMetadata;
import net.minecraft.network.play.server.S1DPacketEntityEffect;
import net.minecraft.network.play.server.S1FPacketSetExperience;
import net.minecraft.network.play.server.S21PacketChunkData;
import net.minecraft.network.play.server.S2BPacketChangeGameState;
import net.minecraft.network.play.server.S30PacketWindowItems;
import net.minecraft.network.play.server.S34PacketMaps;
import net.minecraft.network.play.server.S33PacketUpdateSign;
import net.minecraft.network.play.server.S35PacketUpdateTileEntity;
import net.minecraft.network.play.server.S38PacketPlayerListItem;
import net.minecraft.network.play.server.S3BPacketScoreboardObjective;
import net.minecraft.network.play.server.S3CPacketUpdateScore;
import net.minecraft.network.play.server.S3DPacketDisplayScoreboard;
import net.minecraft.network.play.server.S3EPacketTeams;
import net.minecraft.network.play.server.S3FPacketCustomPayload;
import net.minecraft.network.play.server.S44PacketWorldBorder;
import net.minecraft.network.play.server.S47PacketPlayerListHeaderFooter;
import net.minecraft.potion.PotionEffect;
import net.minecraft.scoreboard.Score;
import net.minecraft.scoreboard.ScoreObjective;
import net.minecraft.scoreboard.ScorePlayerTeam;
import net.minecraft.scoreboard.Scoreboard;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.BlockPos;
import net.minecraft.util.ChatComponentText;
import net.minecraft.util.EnumChatFormatting;
import net.minecraft.util.IChatComponent;
import net.minecraft.util.MathHelper;
import net.minecraft.world.WorldSavedData;
import net.minecraft.world.WorldSettings;
import net.minecraft.world.chunk.Chunk;
import net.minecraft.world.storage.MapData;
import net.minecraftforge.fml.common.network.internal.FMLProxyPacket;
import net.toshimichi.recorder.mixin.AccessorChunkProviderClient;
import net.toshimichi.recorder.mixin.AccessorGuiPlayerTabOverlay;
import net.toshimichi.recorder.mixin.AccessorMapStorage;
import net.toshimichi.recorder.mixin.AccessorS38PacketPlayerListItem;
import net.toshimichi.recorder.mixin.InvokerEntityTrackerEntry;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.BufferedOutputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.EnumSet;
import java.util.List;

/** Writes the live connection to disk. record() is called from the netty thread, the rest from the client thread. */
public final class Recording {

    private static final Logger log = LogManager.getLogger();

    private static volatile DataOutputStream out;
    // the connection being recorded: the codecs also run for server list pings and LAN guests in this JVM
    private static volatile Channel channel;
    private static long start;
    private static File file;

    private Recording() {
    }

    public static File directory() {
        return new File(Minecraft.getMinecraft().mcDataDir, "recordings");
    }

    public static boolean isRecording() {
        return out != null;
    }

    public static void toggle(Minecraft mc) {
        if (isRecording()) {
            stop();
            chat(mc, "Recording saved as " + file.getName());
            return;
        }

        try {
            start(mc);
            chat(mc, "Recording to " + file.getName());
        } catch (IOException e) {
            log.error("Failed to start recording", e);
            chat(mc, EnumChatFormatting.RED + "Failed to start recording: " + e.getMessage());
        }
    }

    public static synchronized void start(Minecraft mc) throws IOException {
        directory().mkdirs();
        file = new File(directory(), new SimpleDateFormat("yyyy-MM-dd_HH-mm-ss").format(new Date()) + ".mcrc");
        start = System.currentTimeMillis();

        DataOutputStream stream = new DataOutputStream(new BufferedOutputStream(new FileOutputStream(file)));
        RecordingFile.writeHeader(stream, start, profile(mc));
        // the world state the server sent before we started listening, so a replay has something to show
        for (Packet<?> packet : snapshot(mc)) RecordingFile.writeEntry(stream, 0, RecordingFile.SNAPSHOT, serialize(EnumPacketDirection.CLIENTBOUND, packet));
        channel = mc.getNetHandler().getNetworkManager().channel();
        out = stream;
    }

    public static synchronized void stop() {
        if (out == null) return;
        try {
            out.close();
        } catch (IOException e) {
            log.error("Failed to close {}", file, e);
        }
        out = null;
    }

    public static void record(Channel source, byte direction, ByteBuf buf) {
        if (out == null || source != channel) return;

        byte[] data = new byte[buf.readableBytes()];
        buf.getBytes(buf.readerIndex(), data);
        record(data, direction);
    }

    // singleplayer: the LocalChannel carries objects, so the frame is built the way the codec would have
    public static void record(Channel source, byte direction, Packet<?> packet) {
        if (out == null || source != channel) return;
        try {
            if (packet instanceof FMLProxyPacket) {
                // mod channel messages have no PLAY id; on a real connection they cross the wire as S3F / C17
                FMLProxyPacket proxy = (FMLProxyPacket) packet;
                if (direction == RecordingFile.SERVERBOUND) {
                    // not toC17Packet(): it wraps the live payload, which serializing would drain before the real send
                    record(serialize(EnumPacketDirection.SERVERBOUND, new C17PacketCustomPayload(proxy.channel(), new PacketBuffer(proxy.payload().duplicate()))), direction);
                } else {
                    for (Packet<?> part : proxy.toS3FPackets()) record(serialize(EnumPacketDirection.CLIENTBOUND, part), direction);
                }
                return;
            }
            record(serialize(direction == RecordingFile.SERVERBOUND ? EnumPacketDirection.SERVERBOUND : EnumPacketDirection.CLIENTBOUND, packet), direction);
        } catch (IOException | RuntimeException e) {
            // anything thrown here escapes into netty, which closes the connection: losing a row beats losing the world
            log.error("Failed to serialize {}", packet.getClass().getSimpleName(), e);
        }
    }

    private static void record(byte[] data, byte direction) {
        synchronized (Recording.class) {
            if (out == null) return;
            try {
                RecordingFile.writeEntry(out, (int) (System.currentTimeMillis() - start), direction, data);
            } catch (IOException e) {
                log.error("Failed to write {}, stopping", file, e);
                stop();
            }
        }
    }

    // in-world key and mouse button presses, as LWJGL reports them; GUI input and mouse motion are not recorded
    public static synchronized void recordInput(byte device, int code, boolean pressed) {
        if (out == null) return;
        try {
            RecordingFile.writeEntry(out, (int) (System.currentTimeMillis() - start), RecordingFile.INPUT,
                    new byte[]{device, (byte) (code >> 24), (byte) (code >> 16), (byte) (code >> 8), (byte) code, (byte) (pressed ? 1 : 0)});
        } catch (IOException e) {
            log.error("Failed to write {}, stopping", file, e);
            stop();
        }
    }

    // the session profile carries no skin; asks Mojang's session server the first time if the launcher passed none
    private static GameProfile profile(Minecraft mc) {
        GameProfile profile = new GameProfile(mc.getSession().getProfile().getId(), mc.getSession().getUsername());
        profile.getProperties().putAll(mc.getProfileProperties());
        return profile;
    }

    // ponytail: no open containers; add them if the replay needs them
    private static List<Packet<?>> snapshot(Minecraft mc) throws IOException {
        WorldClient world = mc.theWorld;
        EntityPlayerSP player = mc.thePlayer;
        List<Packet<?>> packets = new ArrayList<>();

        packets.add(new S01PacketJoinGame(player.getEntityId(), mc.playerController.getCurrentGameType(),
                world.getWorldInfo().isHardcoreModeEnabled(), world.provider.getDimensionId(), world.getDifficulty(),
                20, world.getWorldType(), player.hasReducedDebug()));
        // F3's server brand, and where the compass points
        if (player.getClientBrand() != null) {
            packets.add(new S3FPacketCustomPayload("MC|Brand", new PacketBuffer(Unpooled.buffer()).writeString(player.getClientBrand())));
        }
        packets.add(new S05PacketSpawnPosition(world.getSpawnPoint()));

        S38PacketPlayerListItem tabList = new S38PacketPlayerListItem();
        ((AccessorS38PacketPlayerListItem) tabList).setAction(S38PacketPlayerListItem.Action.ADD_PLAYER);
        for (NetworkPlayerInfo info : player.sendQueue.getPlayerInfoMap()) {
            ((AccessorS38PacketPlayerListItem) tabList).getPlayers().add(tabList.new AddPlayerData(
                    info.getGameProfile(), info.getResponseTime(), info.getGameType(), info.getDisplayName()));
        }
        // NPCs: servers list them only long enough to spawn them, and the client needs the entry for S0C
        S38PacketPlayerListItem unlisted = new S38PacketPlayerListItem();
        ((AccessorS38PacketPlayerListItem) unlisted).setAction(S38PacketPlayerListItem.Action.REMOVE_PLAYER);
        for (Entity entity : world.loadedEntityList) {
            if (!(entity instanceof EntityOtherPlayerMP) || player.sendQueue.getPlayerInfo(entity.getUniqueID()) != null) continue;
            GameProfile npc = ((EntityPlayer) entity).getGameProfile();
            ((AccessorS38PacketPlayerListItem) tabList).getPlayers().add(tabList.new AddPlayerData(npc, 0, WorldSettings.GameType.SURVIVAL, null));
            ((AccessorS38PacketPlayerListItem) unlisted).getPlayers().add(unlisted.new AddPlayerData(npc, 0, null, null));
        }
        packets.add(tabList);
        AccessorGuiPlayerTabOverlay tabOverlay = (AccessorGuiPlayerTabOverlay) mc.ingameGUI.getTabList();
        if (tabOverlay.getHeader() != null || tabOverlay.getFooter() != null) {
            packets.add(headerFooter(tabOverlay.getHeader(), tabOverlay.getFooter()));
        }

        packets.add(new S03PacketTimeUpdate(world.getTotalWorldTime(), world.getWorldTime(),
                world.getGameRules().getBoolean("doDaylightCycle")));

        for (Chunk chunk : ((AccessorChunkProviderClient) world.getChunkProvider()).getChunkListing()) {
            packets.add(new S21PacketChunkData(chunk, true, 0xFFFF));
        }

        // after the chunks, which carry no tile entity data: sign text, skulls, banners, spawners
        for (TileEntity tile : world.loadedTileEntityList) {
            Packet<?> description;
            try {
                description = tile.getDescriptionPacket();
            } catch (RuntimeException e) {
                continue; // a modded tile that only expects to be asked on the server
            }
            if (description instanceof S33PacketUpdateSign || description instanceof S35PacketUpdateTileEntity) packets.add(description);
        }

        // what is drawn on every map the server has sent, held or framed
        for (WorldSavedData data : ((AccessorMapStorage) world.getMapStorage()).getLoadedDataMap().values()) {
            if (!(data instanceof MapData) || !data.mapName.startsWith("map_")) continue;
            MapData map = (MapData) data;
            packets.add(new S34PacketMaps(Integer.parseInt(data.mapName.substring(4)), map.scale,
                    map.mapDecorations.values(), map.colors, 0, 0, 128, 128));
        }

        // 1 starts the rain at strength 0, so the strengths follow it
        if (world.getWorldInfo().isRaining()) packets.add(new S2BPacketChangeGameState(1, 0));
        float rain = world.getRainStrength(1);
        packets.add(new S2BPacketChangeGameState(7, rain));
        // getThunderStrength is already scaled by the rain; the packet carries the raw value
        packets.add(new S2BPacketChangeGameState(8, rain == 0 ? 0 : world.getThunderStrength(1) / rain));
        packets.add(new S44PacketWorldBorder(world.getWorldBorder(), S44PacketWorldBorder.Action.INITIALIZE));

        // teams and objectives before the scores and display slots that name them
        Scoreboard scoreboard = world.getScoreboard();
        for (ScorePlayerTeam team : scoreboard.getTeams()) {
            // a color index the client has no format for is stored as null; RESET (-1) is what "no color" reads as
            if (team.getChatFormat() == null) team.setChatFormat(EnumChatFormatting.RESET);
            packets.add(new S3EPacketTeams(team, 0));
        }
        for (ScoreObjective objective : scoreboard.getScoreObjectives()) packets.add(new S3BPacketScoreboardObjective(objective, 0));
        // a score for an objective the client never got is stored with a null objective, and can't be shown anyway
        for (Score score : scoreboard.getScores()) if (score.getObjective() != null) packets.add(new S3CPacketUpdateScore(score));
        // list, sidebar, below name, then one sidebar per team color
        for (int slot = 0; slot < 19; slot++) {
            ScoreObjective objective = scoreboard.getObjectiveInDisplaySlot(slot);
            if (objective != null) packets.add(new S3DPacketDisplayScoreboard(slot, objective));
        }

        // before S08, so the puppet picks up the held slot when it spawns
        packets.add(new S30PacketWindowItems(0, player.inventoryContainer.getInventory()));
        packets.add(new S09PacketHeldItemChange(player.inventory.currentItem));
        packets.add(new S06PacketUpdateHealth(player.getHealth(), player.getFoodStats().getFoodLevel(), player.getFoodStats().getSaturationLevel()));
        packets.add(new S1FPacketSetExperience(player.experience, player.experienceTotal, player.experienceLevel));

        // after the chunks: this is what dismisses GuiDownloadTerrain on replay
        packets.add(new S08PacketPlayerPosLook(player.posX, player.posY, player.posZ, player.rotationYaw,
                player.rotationPitch, EnumSet.noneOf(S08PacketPlayerPosLook.EnumFlags.class)));
        // the skin layers shown; the server only echoes them back when they change
        int parts = 0;
        for (EnumPlayerModelParts part : mc.gameSettings.getModelParts()) parts |= part.getPartMask();
        DataWatcher skinParts = new DataWatcher(null);
        skinParts.addObject(10, (byte) parts);
        packets.add(new S1CPacketEntityMetadata(player.getEntityId(), skinParts, true));

        for (Entity entity : world.loadedEntityList) {
            if (entity instanceof EntityOtherPlayerMP) {
                EntityPlayer other = (EntityPlayer) entity;
                packets.add(new S0CPacketSpawnPlayer(other));
                packets.add(serverPosition(other));
                // S0F carries the head yaw, S0C does not: without this the head faces south until it turns
                packets.add(new S19PacketEntityHeadLook(other, (byte) MathHelper.floor_float(other.getRotationYawHead() * 256 / 360)));
                equipment(other, packets);
                if (other.isPlayerSleeping()) packets.add(new S0APacketUseBed(other, new BlockPos(other)));
            } else if (entity instanceof EntityLivingBase && entity != player && EntityList.getEntityID(entity) != 0) {
                packets.add(new S0FPacketSpawnMob((EntityLivingBase) entity));
                packets.add(serverPosition(entity));
                equipment((EntityLivingBase) entity, packets);
            } else if (!(entity instanceof EntityLivingBase) && !entity.isDead) {
                Packet<?> spawn;
                try {
                    spawn = ((InvokerEntityTrackerEntry) new EntityTrackerEntry(entity, 0, 0, false)).invokeCreateSpawnPacket();
                } catch (IllegalArgumentException e) {
                    continue; // not a vanilla object
                }
                // modded entities come back as FML packets, which have no PLAY id
                if (!(spawn instanceof S0EPacketSpawnObject || spawn instanceof S10PacketSpawnPainting || spawn instanceof S11PacketSpawnExperienceOrb)) continue;
                packets.add(spawn);
                if (!(spawn instanceof S10PacketSpawnPainting)) packets.add(serverPosition(entity)); // a painting's is never set
                // unlike S0C and S0F the spawn carries no metadata: the dropped item's stack, the framed item
                if (!entity.getDataWatcher().getIsBlank()) packets.add(new S1CPacketEntityMetadata(entity.getEntityId(), entity.getDataWatcher(), true));
                // S0F carries its own velocity; S0C players are interpolated, so motion means nothing for them
                packets.add(new S12PacketEntityVelocity(entity.getEntityId(), entity.motionX, entity.motionY, entity.motionZ));
            }
        }

        if (!unlisted.getEntries().isEmpty()) packets.add(unlisted);

        // after every spawn, so both ends of each link exist on replay; the player is in here too
        for (Entity entity : world.loadedEntityList) {
            if (entity instanceof EntityLivingBase) {
                // max health, speed, horse jump strength; the client only holds what the server sent or defaulted
                packets.add(new S20PacketEntityProperties(entity.getEntityId(), ((EntityLivingBase) entity).getAttributeMap().getAllAttributes()));
                for (PotionEffect effect : ((EntityLivingBase) entity).getActivePotionEffects()) {
                    packets.add(new S1DPacketEntityEffect(entity.getEntityId(), effect));
                }
            }
            if (entity.ridingEntity != null) packets.add(new S1BPacketEntityAttach(0, entity, entity.ridingEntity));
            if (entity instanceof EntityLiving && ((EntityLiving) entity).getLeashedToEntity() != null) {
                packets.add(new S1BPacketEntityAttach(1, entity, ((EntityLiving) entity).getLeashedToEntity()));
            }
        }
        return packets;
    }

    // the spawn packets take posX, which is still gliding toward serverPosX; the server's relative moves build on the
    // latter, so spawning at the former leaves the entity off by the glide's gap until the server next teleports it
    private static S18PacketEntityTeleport serverPosition(Entity entity) {
        return new S18PacketEntityTeleport(entity.getEntityId(), entity.serverPosX, entity.serverPosY, entity.serverPosZ,
                (byte) MathHelper.floor_float(entity.rotationYaw * 256 / 360),
                (byte) MathHelper.floor_float(entity.rotationPitch * 256 / 360), entity.onGround);
    }

    // held item and armor, slots 0-4, as EntityTrackerEntry sends them after a spawn
    private static void equipment(EntityLivingBase entity, List<Packet<?>> packets) {
        for (int slot = 0; slot < 5; slot++) {
            ItemStack stack = entity.getEquipmentInSlot(slot);
            if (stack != null) packets.add(new S04PacketEntityEquipment(entity.getEntityId(), slot, stack));
        }
    }

    // the packet's only constructor leaves the footer out; null means none, which the wire spells as empty text
    private static S47PacketPlayerListHeaderFooter headerFooter(IChatComponent header, IChatComponent footer) throws IOException {
        PacketBuffer buf = new PacketBuffer(Unpooled.buffer());
        try {
            buf.writeChatComponent(header != null ? header : new ChatComponentText(""));
            buf.writeChatComponent(footer != null ? footer : new ChatComponentText(""));
            S47PacketPlayerListHeaderFooter packet = new S47PacketPlayerListHeaderFooter();
            packet.readPacketData(buf);
            return packet;
        } finally {
            buf.release();
        }
    }

    private static byte[] serialize(EnumPacketDirection direction, Packet<?> packet) throws IOException {
        PacketBuffer buf = new PacketBuffer(Unpooled.buffer());
        try {
            buf.writeVarIntToBuffer(EnumConnectionState.PLAY.getPacketId(direction, packet));
            packet.writePacketData(buf);
            byte[] data = new byte[buf.readableBytes()];
            buf.readBytes(data);
            return data;
        } finally {
            buf.release();
        }
    }

    private static void chat(Minecraft mc, String message) {
        mc.thePlayer.addChatMessage(new ChatComponentText(message));
    }
}
