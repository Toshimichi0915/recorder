package net.toshimichi.recorder;

import com.mojang.authlib.GameProfile;
import io.netty.buffer.Unpooled;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.util.concurrent.Future;
import io.netty.util.concurrent.GenericFutureListener;
import net.minecraft.client.Minecraft;
import net.minecraft.client.entity.AbstractClientPlayer;
import net.minecraft.client.entity.EntityOtherPlayerMP;
import net.minecraft.client.entity.EntityPlayerSP;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.gui.inventory.GuiContainer;
import net.minecraft.client.multiplayer.WorldClient;
import net.minecraft.client.network.NetHandlerPlayClient;
import net.minecraft.client.network.NetworkPlayerInfo;
import net.minecraft.network.EnumConnectionState;
import net.minecraft.network.EnumPacketDirection;
import net.minecraft.network.NetworkManager;
import net.minecraft.network.Packet;
import net.minecraft.network.PacketBuffer;
import net.minecraft.entity.DataWatcher;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.block.Block;
import net.minecraft.block.material.Material;
import net.minecraft.block.state.IBlockState;
import net.minecraft.network.play.client.C03PacketPlayer;
import net.minecraft.network.play.client.C07PacketPlayerDigging;
import net.minecraft.network.play.client.C08PacketPlayerBlockPlacement;
import net.minecraft.network.play.client.C09PacketHeldItemChange;
import net.minecraft.network.play.client.C0APacketAnimation;
import net.minecraft.network.play.client.C0BPacketEntityAction;
import net.minecraft.network.play.client.C0DPacketCloseWindow;
import net.minecraft.network.play.server.S01PacketJoinGame;
import net.minecraft.network.play.server.S07PacketRespawn;
import net.minecraft.network.play.server.S08PacketPlayerPosLook;
import net.minecraft.network.play.server.S12PacketEntityVelocity;
import net.minecraft.network.play.server.S1BPacketEntityAttach;
import net.minecraft.network.play.server.S1CPacketEntityMetadata;
import net.minecraft.network.play.server.S38PacketPlayerListItem;
import net.minecraft.network.play.server.S39PacketPlayerAbilities;
import net.minecraft.init.Blocks;
import net.minecraft.item.ItemStack;
import net.minecraft.util.BlockPos;
import net.minecraft.util.MathHelper;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.MovingObjectPosition;
import net.minecraft.util.Vec3;
import net.minecraft.world.World;
import net.minecraft.world.WorldSettings;
import net.minecraftforge.fml.common.network.handshake.NetworkDispatcher;
import net.toshimichi.recorder.mixin.AccessorEntityOtherPlayerMP;
import net.toshimichi.recorder.mixin.AccessorS38PacketPlayerListItem;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.lwjgl.input.Mouse;

import java.io.File;
import java.io.IOException;
import java.util.List;

/**
 * Feeds a recording into a fresh NetHandlerPlayClient over a channel that goes nowhere.
 * Clientbound packets drive the world. The viewer is a spectator; the recorded player is a puppet entity
 * driven by its own serverbound packets (the viewer's mode can be switched from the pause menu). Left-click an entity to see through its eyes, sneak to let go.
 */
public final class Replay {

    private static final Logger log = LogManager.getLogger();
    private static Replay current;
    private static final int PUPPET_ID = Integer.MIN_VALUE + 7; // nothing a server hands out


    private final Minecraft mc = Minecraft.getMinecraft();
    private final GuiScreen parent;
    private final List<RecordingFile.Entry> entries;
    private final GameProfile profile; // who recorded it, not who is watching
    private NetHandlerPlayClient handler;
    private EntityOtherPlayerMP puppet;
    private byte skinParts; // hat, jacket, sleeves...: outlives the puppet across respawns
    private boolean clickWasDown;
    private BlockPos digPos; // the puppet's cracks: the server shows them to everyone but the digger
    private long digStart;
    private long digDuration;
    private int index;
    private boolean paused;
    private long base;       // replay millis at the last resume or jump
    private long resumedAt;  // wall clock at the last resume or jump
    private WorldExport export; // only while converting
    private WorldSettings.GameType gameType = WorldSettings.GameType.SPECTATOR; // the viewer's, outlives seeks

    private Replay(GuiScreen parent, RecordingFile.Contents contents) {
        this.parent = parent;
        this.entries = contents.entries;
        // version 1 files never stored it; the viewer is the best guess
        this.profile = contents.profile != null ? contents.profile : mc.getSession().getProfile();
    }

    public static Replay current() {
        return current;
    }

    public static void start(File file, GuiScreen parent) throws IOException {
        current = new Replay(parent, RecordingFile.readAll(file));
        current.connect();
        current.resumedAt = System.currentTimeMillis();
        current.tick();
    }

    // the whole recording in one go, nothing drawn in between; returns the new save's folder name
    public static String convert(File file, GuiScreen parent) throws IOException {
        Replay replay = new Replay(parent, RecordingFile.readAll(file));
        replay.export = new WorldExport(file.getName().replaceFirst("\\.mcrc$", ""));
        replay.connect();
        try {
            while (replay.index < replay.entries.size()) replay.feed(replay.entries.get(replay.index++));
            if (replay.mc.theWorld != null) replay.export.saveAll(replay.mc.theWorld);
        } finally {
            replay.export.finish(replay.puppet != null ? replay.puppet : replay.mc.thePlayer);
            replay.mc.loadWorld((WorldClient) null);
            replay.mc.displayGuiScreen(parent);
        }
        return replay.export.directory;
    }

    public boolean isPaused() {
        return paused;
    }

    // the wall clock keeps running past the last entry; the replay does not
    public long elapsed() {
        return Math.min(duration(), paused ? base : base + System.currentTimeMillis() - resumedAt);
    }

    public long duration() {
        return entries.isEmpty() ? 0 : entries.get(entries.size() - 1).time;
    }

    public WorldSettings.GameType getGameType() {
        return gameType;
    }

    public void cycleGameType() {
        switch (gameType) {
            case SPECTATOR: gameType = WorldSettings.GameType.CREATIVE; break;
            case CREATIVE: gameType = WorldSettings.GameType.SURVIVAL; break;
            default: gameType = WorldSettings.GameType.SPECTATOR; break;
        }
    }

    public void togglePause() {
        base = elapsed();
        resumedAt = System.currentTimeMillis();
        paused = !paused;
    }

    // backwards means a new world fast-forwarded from the start: packets cannot be undone
    public void jump(long millis) {
        EntityPlayerSP before = null;
        if (millis < elapsed()) {
            before = mc.thePlayer;
            mc.loadWorld((WorldClient) null);
            connect();
        }
        base = millis;
        resumedAt = System.currentTimeMillis();
        tick();
        // the new world puts the viewer back at the snapshot; keep them where they were, unless that was another dimension
        EntityPlayerSP player = mc.thePlayer;
        if (before != null && player != null && player.dimension == before.dimension) {
            player.setPositionAndRotation(before.posX, before.posY, before.posZ, before.rotationYaw, before.rotationPitch);
        }
    }

    public void exit() {
        current = null;
        mc.loadWorld((WorldClient) null);
        mc.displayGuiScreen(parent);
    }

    // called every client tick; the handler is swapped out from under us when the world is left any other way
    public void tick() {
        if (index > 0 && mc.getNetHandler() != handler) {
            current = null;
            return;
        }

        long elapsed = elapsed();
        while (index < entries.size() && entries.get(index).time <= elapsed) {
            feed(entries.get(index++));
        }

        if (puppet == null) return;
        if (digPos != null) {
            mc.theWorld.sendBlockBreakProgress(PUPPET_ID, digPos, (int) Math.min(9, (elapsed - digStart) * 10 / digDuration) - 1);
        }
        // the recording may carry the server's own game mode changes; the viewer keeps the one picked
        if (mc.playerController.getCurrentGameType() != gameType) {
            mc.playerController.setGameType(gameType);
        }

        boolean down = Mouse.isButtonDown(0);
        MovingObjectPosition hit = mc.objectMouseOver;
        if (down && !clickWasDown && mc.currentScreen == null && hit != null
                && hit.typeOfHit == MovingObjectPosition.MovingObjectType.ENTITY) {
            mc.setRenderViewEntity(hit.entityHit);
        }
        clickWasDown = down;

        Entity view = mc.getRenderViewEntity();
        if (view != mc.thePlayer && (mc.gameSettings.keyBindSneak.isKeyDown() || view.isDead)) {
            // let go where they were looking from, as vanilla spectating leaves you
            mc.thePlayer.setPositionAndRotation(view.posX, view.posY, view.posZ, view.rotationYaw, view.rotationPitch);
            mc.setRenderViewEntity(mc.thePlayer);
        }
    }

    // once the snapshot has placed the viewer: a body for the recorded player, and freedom for the viewer
    private void spawnPuppet() {
        EntityPlayerSP player = mc.thePlayer;
        // the name and skin the server listed at this point, as a nick plugin may have changed them; the skin is looked up by UUID
        NetworkPlayerInfo info = handler.getPlayerInfo(profile.getId());
        puppet = new Puppet(mc.theWorld, info != null ? info.getGameProfile() : profile);
        puppet.setPositionAndRotation(player.posX, player.posY, player.posZ, player.rotationYaw, player.rotationPitch);
        puppet.setPositionAndRotation2(player.posX, player.posY, player.posZ, player.rotationYaw, player.rotationPitch, 0, false);
        // share the arrays: the replayed inventory packets fill the viewer's inventory, the puppet shows it
        puppet.inventory.mainInventory = player.inventory.mainInventory;
        puppet.inventory.armorInventory = player.inventory.armorInventory;
        puppet.inventory.currentItem = player.inventory.currentItem;
        puppet.capabilities.isCreativeMode = player.capabilities.isCreativeMode; // before the viewer turns spectator
        puppet.getDataWatcher().updateObject(10, skinParts);
        mc.theWorld.addEntityToWorld(PUPPET_ID, puppet);
        mc.playerController.setGameType(gameType);
        // step back so the first thing on screen is not the inside of the puppet's head
        Vec3 look = player.getLookVec();
        player.setPosition(player.posX - look.xCoord * 3, player.posY + 1, player.posZ - look.zCoord * 3);
    }

    private void connect() {
        NetworkManager manager = new NetworkManager(EnumPacketDirection.CLIENTBOUND) {
            @Override
            public void sendPacket(Packet packetIn) {
            }

            @Override
            public void sendPacket(Packet packetIn, GenericFutureListener<? extends Future<? super Void>> listener,
                                   GenericFutureListener<? extends Future<? super Void>>... listeners) {
            }
        };
        new EmbeddedChannel(manager); // channelActive gives the manager its channel
        NetworkDispatcher.allocAndSet(manager); // Forge's handleJoinGame asks it for the dimension
        manager.setConnectionState(EnumConnectionState.PLAY);

        handler = new NetHandlerPlayClient(mc, parent, manager, mc.getSession().getProfile());
        manager.setNetHandler(handler);
        puppet = null;
        digPos = null;
        skinParts = 0;
        index = 0;
    }

    @SuppressWarnings("unchecked")
    private void feed(RecordingFile.Entry entry) {
        if (entry.direction == RecordingFile.INPUT) return; // key presses, not packets: a mouse row reads as an S01
        boolean serverbound = entry.direction == RecordingFile.SERVERBOUND;
        try {
            Packet packet = decode(entry);

            if (packet instanceof S38PacketPlayerListItem) {
                ownSkin((S38PacketPlayerListItem) packet);
                keepSkins((S38PacketPlayerListItem) packet);
            }
            if (packet instanceof S1CPacketEntityMetadata && ((S1CPacketEntityMetadata) packet).getEntityId() == mc.thePlayer.getEntityId()) {
                // the recorded player's own metadata is the puppet's; only the skin layers, health would kill it off
                List<DataWatcher.WatchableObject> objects = ((S1CPacketEntityMetadata) packet).func_149376_c();
                // an empty update decodes as null, not an empty list
                if (objects != null) for (DataWatcher.WatchableObject object : objects) {
                    if (object.getDataValueId() == 10) skinParts = (Byte) object.getObject();
                }
                if (puppet != null) puppet.getDataWatcher().updateObject(10, skinParts);
                return;
            }

            if (!serverbound) {
                if (export != null) export.before(packet, mc.theWorld);
                // a new world and a new player: like the snapshot, its S08 must reach the handler to close GuiDownloadTerrain
                if (packet instanceof S01PacketJoinGame || packet instanceof S07PacketRespawn) {
                    puppet = null;
                    digPos = null;
                }
                if (puppet == null) {
                    packet.processPacket(handler);
                    if (packet instanceof S08PacketPlayerPosLook) spawnPuppet();
                } else if (packet instanceof S08PacketPlayerPosLook) {
                    // ponytail: relative flags ignored; 1.8 servers teleport absolutely
                    S08PacketPlayerPosLook pos = (S08PacketPlayerPosLook) packet;
                    puppet.setPositionAndRotation2(pos.getX(), pos.getY(), pos.getZ(), pos.getYaw(), pos.getPitch(), 3, false);
                } else if (packet instanceof S39PacketPlayerAbilities) {
                    // the puppet keeps the creative flag for placement; the rest would shove the spectator around
                    puppet.capabilities.isCreativeMode = ((S39PacketPlayerAbilities) packet).isCreativeMode();
                } else if (packet instanceof S1BPacketEntityAttach && ((S1BPacketEntityAttach) packet).getLeash() == 0
                        && ((S1BPacketEntityAttach) packet).getEntityId() == mc.thePlayer.getEntityId()) {
                    // the recorded player's ride is the puppet's; the handler would seat the viewer
                    puppet.mountEntity(mc.theWorld.getEntityByID(((S1BPacketEntityAttach) packet).getVehicleEntityId()));
                    // getting off sets the puppet down on the mount; glide from there, not from before the ride
                    puppet.setPositionAndRotation2(puppet.posX, puppet.posY, puppet.posZ, puppet.rotationYaw, puppet.rotationPitch, 0, false);
                } else if (!(packet instanceof S12PacketEntityVelocity
                        && ((S12PacketEntityVelocity) packet).getEntityID() == mc.thePlayer.getEntityId())) {
                    // self velocity would shove the spectator around
                    packet.processPacket(handler);
                }
            } else if (puppet != null) {
                if (packet instanceof C03PacketPlayer) {
                    C03PacketPlayer move = (C03PacketPlayer) packet;
                    // what a packet leaves out stays at the glide's target: the puppet itself lags behind it, and
                    // a standing player sends position only once a second, so each tick would cut the glide short
                    AccessorEntityOtherPlayerMP target = (AccessorEntityOtherPlayerMP) puppet;
                    puppet.setPositionAndRotation2(
                            move.isMoving() ? move.getPositionX() : target.getTargetX(),
                            move.isMoving() ? move.getPositionY() : target.getTargetY(),
                            move.isMoving() ? move.getPositionZ() : target.getTargetZ(),
                            move.getRotating() ? move.getYaw() : (float) target.getTargetYaw(),
                            move.getRotating() ? move.getPitch() : (float) target.getTargetPitch(), 3, false);
                    if (move.getRotating()) puppet.setRotationYawHead(move.getYaw());
                    puppet.onGround = move.isOnGround(); // break speed drops fivefold in the air
                } else if (packet instanceof C07PacketPlayerDigging) {
                    dig((C07PacketPlayerDigging) packet, entry.time);
                } else if (packet instanceof C08PacketPlayerBlockPlacement) {
                    place((C08PacketPlayerBlockPlacement) packet);
                } else if (packet instanceof C0APacketAnimation) {
                    puppet.swingItem();
                } else if (packet instanceof C09PacketHeldItemChange) {
                    puppet.inventory.currentItem = ((C09PacketHeldItemChange) packet).getSlotId();
                } else if (packet instanceof C0DPacketCloseWindow) {
                    // the server never echoes a close: the client shuts its own window and only tells the server
                    if (mc.currentScreen instanceof GuiContainer) mc.thePlayer.closeScreenAndDropStack();
                } else if (packet instanceof C0BPacketEntityAction) {
                    switch (((C0BPacketEntityAction) packet).getAction()) {
                        case START_SNEAKING: puppet.setSneaking(true); break;
                        case STOP_SNEAKING: puppet.setSneaking(false); break;
                        case START_SPRINTING: puppet.setSprinting(true); break;
                        case STOP_SPRINTING: puppet.setSprinting(false); break;
                        default: break;
                    }
                }
            }
        } catch (Exception e) {
            log.warn("Skipping a packet at {}ms", entry.time, e);
        }
    }

    // offline mode servers list the player with no skin; the header has it. A skin the server did send stays,
    // since that is what everyone saw, nick plugins included
    private void ownSkin(S38PacketPlayerListItem packet) {
        if (packet.getAction() != S38PacketPlayerListItem.Action.ADD_PLAYER || profile.getProperties().isEmpty()) return;
        List<S38PacketPlayerListItem.AddPlayerData> players = ((AccessorS38PacketPlayerListItem) packet).getPlayers();
        for (int i = 0; i < players.size(); i++) {
            S38PacketPlayerListItem.AddPlayerData data = players.get(i);
            if (data.getProfile().getId().equals(profile.getId()) && !data.getProfile().getProperties().containsKey("textures")) {
                players.set(i, packet.new AddPlayerData(profile, data.getPing(), data.getGameMode(), data.getDisplayName()));
            }
        }
    }

    // NPCs are listed only long enough to spawn: a player looks up its skin on first render, which a seek or
    // the snapshot never gets to before the removal, so make it look now while the entry is still there
    private void keepSkins(S38PacketPlayerListItem packet) {
        if (packet.getAction() != S38PacketPlayerListItem.Action.REMOVE_PLAYER || mc.theWorld == null) return;
        for (S38PacketPlayerListItem.AddPlayerData data : packet.getEntries()) {
            EntityPlayer player = mc.theWorld.getPlayerEntityByUUID(data.getProfile().getId());
            if (player instanceof AbstractClientPlayer) ((AbstractClientPlayer) player).getLocationSkin();
        }
    }

    // the digger's client draws its own cracks and break effect; replay them for the puppet
    private void dig(C07PacketPlayerDigging dig, long time) throws Exception {
        BlockPos pos = dig.getPosition();
        switch (dig.getStatus()) {
            case START_DESTROY_BLOCK:
                RecordingFile.Entry end = digEnd(pos);
                if (end == null) {
                    destroy(pos); // creative or instant mining: the client sends no STOP and breaks it on the spot
                    break;
                }
                digPos = pos;
                digStart = time;
                if (((C07PacketPlayerDigging) decode(end)).getStatus() == C07PacketPlayerDigging.Action.STOP_DESTROY_BLOCK) {
                    digDuration = Math.max(1, end.time - time);
                } else {
                    // ponytail: no haste or fatigue on the puppet; aborted digs under potions crack at the wrong pace
                    float perTick = mc.theWorld.getBlockState(pos).getBlock().getPlayerRelativeBlockHardness(puppet, mc.theWorld, pos);
                    digDuration = perTick <= 0 ? Long.MAX_VALUE : (long) Math.ceil(1 / perTick) * 50;
                }
                break;
            case STOP_DESTROY_BLOCK:
                destroy(pos);
                // fall through
            case ABORT_DESTROY_BLOCK:
                mc.theWorld.sendBlockBreakProgress(PUPPET_ID, pos, -1);
                digPos = null;
                break;
            default:
                break;
        }
    }

    // like PlayerControllerMP.onPlayerRightClick: the client places at once, the server's block change undoes a refusal
    private void place(C08PacketPlayerBlockPlacement place) {
        if (place.getPlacedBlockDirection() == 255) return; // a right click in the air, nothing placed
        BlockPos pos = place.getPosition();
        EnumFacing side = EnumFacing.getFront(place.getPlacedBlockDirection());
        float x = place.getPlacedBlockOffsetX(), y = place.getPlacedBlockOffsetY(), z = place.getPlacedBlockOffsetZ();
        ItemStack held = puppet.inventory.getCurrentItem();
        if (held != null && held.getItem().onItemUseFirst(held, puppet, mc.theWorld, pos, side, x, y, z)) return;

        IBlockState state = mc.theWorld.getBlockState(pos);
        if ((!puppet.isSneaking() || held == null || held.getItem().doesSneakBypassUse(mc.theWorld, pos, puppet))
                && state.getBlock().onBlockActivated(mc.theWorld, pos, state, puppet, side, x, y, z)) return;
        if (held == null) return;

        // the stack is the viewer's own; the server only resends it when its count disagrees with the client's
        int meta = held.getMetadata();
        int size = held.stackSize;
        held.onItemUse(puppet, mc.theWorld, pos, side, x, y, z);
        if (puppet.capabilities.isCreativeMode) {
            held.setItemDamage(meta);
            held.stackSize = size;
        } else if (held.stackSize <= 0) {
            puppet.inventory.mainInventory[puppet.inventory.currentItem] = null;
        }
    }

    // like PlayerControllerMP.onPlayerDestroyBlock: gone at once, the server's block change undoes a refusal
    private void destroy(BlockPos pos) {
        IBlockState state = mc.theWorld.getBlockState(pos);
        Block block = state.getBlock();
        if (block.getMaterial() == Material.air) return;
        mc.theWorld.playAuxSFX(2001, pos, Block.getStateId(state));
        if (block.removedByPlayer(mc.theWorld, pos, puppet, false)) {
            block.onBlockDestroyedByPlayer(mc.theWorld, pos, state);
        }
    }

    // a dig in progress always ends in a STOP or ABORT for its own block; anything else means it broke instantly
    private RecordingFile.Entry digEnd(BlockPos pos) throws Exception {
        for (int i = index; i < entries.size(); i++) {
            RecordingFile.Entry e = entries.get(i);
            if (e.direction != RecordingFile.SERVERBOUND || e.data[0] != 0x07) continue;
            C07PacketPlayerDigging next = (C07PacketPlayerDigging) decode(e);
            C07PacketPlayerDigging.Action action = next.getStatus();
            if (action == C07PacketPlayerDigging.Action.START_DESTROY_BLOCK) return null;
            if (action == C07PacketPlayerDigging.Action.ABORT_DESTROY_BLOCK
                    || action == C07PacketPlayerDigging.Action.STOP_DESTROY_BLOCK) {
                return next.getPosition().equals(pos) ? e : null;
            }
        }
        return null;
    }

    private static Packet decode(RecordingFile.Entry entry) throws Exception {
        PacketBuffer buf = new PacketBuffer(Unpooled.wrappedBuffer(entry.data));
        try {
            Packet packet = EnumConnectionState.PLAY.getPacket(entry.direction == RecordingFile.SERVERBOUND
                    ? EnumPacketDirection.SERVERBOUND : EnumPacketDirection.CLIENTBOUND, buf.readVarIntFromBuffer());
            packet.readPacketData(buf);
            return packet;
        } finally {
            buf.release();
        }
    }

    // the server leaves a player's own sounds to their client, so the recording never has their footsteps;
    // this plays them the way the recorder's client did
    private static final class Puppet extends EntityOtherPlayerMP {

        private int nextStep;

        Puppet(World world, GameProfile profile) {
            super(world, profile);
        }

        // EntityPlayer's goes to everyone near but this player, which a client never sends anywhere
        @Override
        public void playSound(String name, float volume, float pitch) {
            if (!isSilent()) worldObj.playSound(posX, posY, posZ, name, volume, pitch, false);
        }

        // like Entity.moveEntity, which the puppet's glide never calls
        // ponytail: no swim splashes, no fence-top steps; add from moveEntity if missed
        @Override
        public void onLivingUpdate() {
            double x = posX, y = posY, z = posZ;
            super.onLivingUpdate();
            if (ridingEntity != null || onGround && isSneaking()) return;
            BlockPos pos = new BlockPos(MathHelper.floor_double(posX), MathHelper.floor_double(posY - 0.2), MathHelper.floor_double(posZ));
            Block block = worldObj.getBlockState(pos).getBlock();
            double dy = block == Blocks.ladder ? posY - y : 0;
            distanceWalkedOnStepModified += MathHelper.sqrt_double((posX - x) * (posX - x) + dy * dy + (posZ - z) * (posZ - z)) * 0.6;
            if (distanceWalkedOnStepModified > nextStep && block.getMaterial() != Material.air) {
                nextStep = (int) distanceWalkedOnStepModified + 1;
                playStepSound(pos, block);
            }
        }
    }
}
