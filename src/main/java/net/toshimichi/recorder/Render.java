package net.toshimichi.recorder;

import net.minecraft.client.Minecraft;
import net.minecraft.client.audio.SoundCategory;
import net.minecraft.client.entity.EntityPlayerSP;
import net.minecraft.client.gui.GuiMainMenu;
import net.minecraft.client.settings.GameSettings;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.item.ItemStack;
import net.minecraft.util.MathHelper;
import net.minecraft.util.MovingObjectPosition;
import net.minecraft.util.Vec3;
import net.minecraftforge.client.event.RenderWorldLastEvent;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.Display;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL12;
import org.lwjgl.util.glu.GLU;

import java.io.File;
import java.io.IOException;
import java.io.PrintWriter;
import java.nio.ByteBuffer;
import java.nio.FloatBuffer;
import java.nio.IntBuffer;
import java.nio.channels.Channels;
import java.nio.channels.WritableByteChannel;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Offline rendering for videos, on only when RECORDER_RENDER names a shot list. The replay and the game timer run
 * on a virtual clock that steps one 60 fps frame at a time, so every frame lands on its exact moment however slow
 * the drawing is. Each shot goes to its own mp4 through ffmpeg, with a .jsonl of where the fighters are on screen.
 * <pre>
 * file /path/to/recording.mcrc
 * target 6700                      entity id the cameras frame against the recorded player
 * name start_ms end_ms speed fp
 * name start_ms end_ms speed orbit angle_deg deg_per_s radius height
 * name start_ms end_ms speed shoulder side height
 * wear legs 6700                   the recorded player's armour slot (feet, legs, chest, head) wears a copy of that
 *                                  entity's whenever it shows empty: a client that lost track of what it wore (a ghost
 *                                  item) recorded a bare slot
 * </pre>
 * The path may not contain spaces. Third person cameras stop short of walls, an orbit swings round them, and a target
 * more than 16 blocks off (killed and back at spawn) leaves the recorded player framed alone, angles counted from
 * where they face.
 */
public final class Render {

    private static final Logger log = LogManager.getLogger();
    private static final int WIDTH = 1920, HEIGHT = 1080, FPS = 60, WARMUP = 45;
    private static final List<String> ARMOR = Arrays.asList("feet", "legs", "chest", "head"); // armorInventory order
    private static Render current;

    private final Minecraft mc = Minecraft.getMinecraft();
    private final File dir;
    private File recording;
    private int target;
    private final List<String[]> shots = new ArrayList<>();
    private final List<String[]> wear = new ArrayList<>();
    private final ItemStack[] worn = new ItemStack[4]; // the first copy seen of each piece lent
    private int shot = -1;
    private int warmup;
    private int frame;
    private double wall;   // virtual wall clock the replay reads, millis
    private double ticks;  // virtual game ticks
    private int ticksGiven;
    private Process ffmpeg;
    private WritableByteChannel pipe;
    private PrintWriter meta;
    private ByteBuffer pixels;
    private final FloatBuffer modelview = BufferUtils.createFloatBuffer(16), projection = BufferUtils.createFloatBuffer(16);
    private final IntBuffer viewport = BufferUtils.createIntBuffer(16);
    private final FloatBuffer out = BufferUtils.createFloatBuffer(3);
    private float[] targetOnScreen, selfOnScreen;
    private double camX, camY, camZ, lookX, lookY, lookZ; // smoothed
    private boolean camSet;
    private double dodge; // radians the orbit has swung off its angle to see past walls

    private Render(File shotList) throws IOException {
        dir = shotList.getParentFile();
        for (String line : Files.readAllLines(shotList.toPath())) {
            String[] w = line.trim().split("\\s+");
            if (w[0].isEmpty() || w[0].startsWith("#")) continue;
            if (w[0].equals("file")) recording = new File(w[1]);
            else if (w[0].equals("target")) target = Integer.parseInt(w[1]);
            else if (w[0].equals("wear")) wear.add(w);
            else shots.add(w);
        }
    }

    public static void init() {
        String path = System.getenv("RECORDER_RENDER");
        if (path == null || path.isEmpty()) return;
        try {
            current = new Render(new File(path));
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    public static boolean active() {
        return current != null && current.shot >= 0;
    }

    // what the replay takes for the wall clock
    public static long clock() {
        return active() ? (long) current.wall : System.currentTimeMillis();
    }

    // called right after Timer.updateTimer: hand out the virtual ticks instead of the real ones
    public static void timer(net.minecraft.util.Timer timer) {
        if (!active()) return;
        int whole = (int) Math.floor(current.ticks);
        timer.elapsedTicks = Math.min(10, whole - current.ticksGiven);
        current.ticksGiven = whole;
        timer.renderPartialTicks = timer.elapsedPartialTicks = (float) (current.ticks - whole);
    }

    public static Render current() {
        return current;
    }

    @SubscribeEvent
    public void onRender(TickEvent.RenderTickEvent event) {
        try {
            if (event.phase == TickEvent.Phase.START) camera(event.renderTickTime);
            else step();
        } catch (Exception e) {
            log.error("Render failed", e);
            mc.shutdown();
        }
    }

    private void step() throws IOException {
        if (shot < 0) {
            if (!(mc.currentScreen instanceof GuiMainMenu)) return;
            GameSettings s = mc.gameSettings;
            s.pauseOnLostFocus = false;
            s.enableVsync = false;
            Display.setVSyncEnabled(false); // the option alone is only read at startup; a hidden window's vsync never returns
            s.limitFramerate = (int) GameSettings.Options.FRAMERATE_LIMIT.getValueMax();
            s.chatVisibility = EntityPlayer.EnumChatVisibility.HIDDEN;
            s.setSoundLevel(SoundCategory.MASTER, 0);
            s.guiScale = 3;
            mc.resize(WIDTH, HEIGHT);
            shot = 0;
            Replay.start(recording, mc.currentScreen);
            setup();
            return;
        }
        Replay replay = Replay.current();
        if (warmup > 0) {
            warmup--;
            ticks += 1.0 / 3;
            return;
        }
        capture(replay);
        double speed = Double.parseDouble(shots.get(shot)[3]);
        wall += 1000.0 / FPS * speed;
        ticks += 20.0 / FPS * speed;
        if (replay.elapsed() < Long.parseLong(shots.get(shot)[2])) return;
        finish();
        if (++shot == shots.size()) {
            mc.shutdown();
            return;
        }
        setup();
    }

    private void setup() throws IOException {
        String[] s = shots.get(shot);
        Replay.current().jump(Long.parseLong(s[1]));
        warmup = WARMUP;
        frame = 0;
        camSet = false;
        dodge = 0;
        mc.gameSettings.hideGUI = !s[4].equals("fp");
        String name = s[0];
        ffmpeg = new ProcessBuilder("ffmpeg", "-y", "-loglevel", "error", "-f", "rawvideo", "-pix_fmt", "bgra",
                "-s", WIDTH + "x" + HEIGHT, "-r", String.valueOf(FPS), "-i", "-",
                "-vf", "vflip", "-c:v", "libx264", "-preset", "fast", "-crf", "12", "-pix_fmt", "yuv420p",
                new File(dir, name + ".mp4").getPath()).inheritIO().redirectInput(ProcessBuilder.Redirect.PIPE).start();
        pipe = Channels.newChannel(ffmpeg.getOutputStream());
        meta = new PrintWriter(new File(dir, name + ".jsonl"), "UTF-8");
        pixels = BufferUtils.createByteBuffer(WIDTH * HEIGHT * 4);
        log.info("Rendering shot {}", name);
    }

    private void finish() throws IOException {
        pipe.close();
        meta.close();
        try {
            ffmpeg.waitFor();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private void capture(Replay replay) throws IOException {
        pixels.clear();
        GL11.glPixelStorei(GL11.GL_PACK_ALIGNMENT, 1);
        GL11.glReadPixels(0, 0, WIDTH, HEIGHT, GL12.GL_BGRA, GL11.GL_UNSIGNED_BYTE, pixels);
        while (pixels.hasRemaining()) pipe.write(pixels);
        Entity t = mc.theWorld.getEntityByID(target);
        meta.printf("{\"f\":%d,\"t\":%d,\"o\":%s,\"p\":%s,\"hp\":%.2f,\"ohurt\":%d}%n", frame++, replay.elapsed(),
                json(targetOnScreen), json(selfOnScreen), mc.thePlayer.getHealth(),
                t instanceof EntityPlayer ? ((EntityPlayer) t).hurtTime : -1);
        targetOnScreen = selfOnScreen = null;
    }

    private static String json(float[] p) {
        return p == null ? "null" : String.format("[%.1f,%.1f]", p[0], p[1]);
    }

    // where the fighters' chests land on screen, with the world's own matrices
    @SubscribeEvent
    public void onWorldLast(RenderWorldLastEvent event) {
        if (!active()) return;
        GL11.glGetFloat(GL11.GL_MODELVIEW_MATRIX, (FloatBuffer) modelview.clear());
        GL11.glGetFloat(GL11.GL_PROJECTION_MATRIX, (FloatBuffer) projection.clear());
        GL11.glGetInteger(GL11.GL_VIEWPORT, (IntBuffer) viewport.clear());
        targetOnScreen = project(mc.theWorld.getEntityByID(target), event.partialTicks);
        Entity self = Replay.recorded();
        selfOnScreen = self == mc.getRenderViewEntity() ? null : project(self, event.partialTicks);
    }

    private float[] project(Entity e, float pt) {
        if (e == null) return null;
        Entity view = mc.getRenderViewEntity();
        double vx = view.lastTickPosX + (view.posX - view.lastTickPosX) * pt;
        double vy = view.lastTickPosY + (view.posY - view.lastTickPosY) * pt;
        double vz = view.lastTickPosZ + (view.posZ - view.lastTickPosZ) * pt;
        double x = e.lastTickPosX + (e.posX - e.lastTickPosX) * pt - vx;
        double y = e.lastTickPosY + (e.posY - e.lastTickPosY) * pt + 1.2 - vy;
        double z = e.lastTickPosZ + (e.posZ - e.lastTickPosZ) * pt - vz;
        out.clear();
        if (!GLU.gluProject((float) x, (float) y, (float) z, modelview, projection, viewport, out)) return null;
        if (out.get(2) < 0 || out.get(2) > 1) return null; // behind the camera
        return new float[]{out.get(0), HEIGHT - out.get(1)};
    }

    // the puppet shares the viewer's armour, so this dresses both it and the first person hud
    private void dress() {
        ItemStack[] armor = mc.thePlayer.inventory.armorInventory;
        for (String[] w : wear) {
            int slot = ARMOR.indexOf(w[1]);
            Entity from = mc.theWorld.getEntityByID(Integer.parseInt(w[2]));
            if (worn[slot] == null && from instanceof EntityPlayer && ((EntityPlayer) from).getCurrentArmor(slot) != null) {
                worn[slot] = ((EntityPlayer) from).getCurrentArmor(slot).copy();
            }
            if (armor[slot] == null && worn[slot] != null) armor[slot] = worn[slot].copy();
        }
    }

    // place the viewer for this frame; prev and last tick copies too, so nothing interpolates it elsewhere
    private void camera(float pt) {
        if (!active() || mc.theWorld == null || Replay.recorded() == null) return;
        // a grabbed mouse turns the viewer between here and the drawing: let it go
        if (mc.inGameHasFocus) mc.setIngameNotInFocus();
        dress();
        String[] s = shots.get(shot);
        Replay.Puppet self = Replay.recorded();
        if (s[4].equals("fp")) {
            if (mc.getRenderViewEntity() != self) mc.setRenderViewEntity(self);
            return;
        }
        EntityPlayerSP viewer = mc.thePlayer;
        if (mc.getRenderViewEntity() != viewer) mc.setRenderViewEntity(viewer);
        Entity other = mc.theWorld.getEntityByID(target);
        if (other != null && other.getDistanceToEntity(self) > 16) other = null; // killed and back at spawn, or fled
        double ax = lerp(self.lastTickPosX, self.posX, pt), ay = lerp(self.lastTickPosY, self.posY, pt), az = lerp(self.lastTickPosZ, self.posZ, pt);
        double bx = other != null ? lerp(other.lastTickPosX, other.posX, pt) : ax;
        double by = other != null ? lerp(other.lastTickPosY, other.posY, pt) : ay;
        double bz = other != null ? lerp(other.lastTickPosZ, other.posZ, pt) : az;
        // alone, angles count from where the recorded player faces
        double line = other != null ? Math.atan2(bz - az, bx - ax) : Math.toRadians(self.rotationYaw + 90);
        double seconds = (double) frame / FPS;
        double cx, cy, cz, lx, ly, lz;
        if (s[4].equals("orbit")) {
            double angle = line + Math.toRadians(Double.parseDouble(s[5]) + Double.parseDouble(s[6]) * seconds);
            double radius = Double.parseDouble(s[7]), height = Double.parseDouble(s[8]);
            lx = (ax + bx) / 2;
            ly = (ay + by) / 2 + 1.1;
            lz = (az + bz) / 2;
            // a wall in the way: swing round to the nearest clear side, and stay there or it flaps between the two
            double from = dodge;
            for (int i = 1; i <= 18 && blocked(lx, ly, lz, angle + dodge, radius, height); i++) {
                dodge = from + Math.toRadians(20) * (i % 2 == 1 ? (i + 1) / 2 : -(i / 2));
            }
            cx = lx + Math.cos(angle + dodge) * radius;
            cy = ly + height;
            cz = lz + Math.sin(angle + dodge) * radius;
        } else { // shoulder
            double side = Double.parseDouble(s[5]);
            lx = bx;
            ly = by + 1.2;
            lz = bz;
            cx = ax - Math.cos(line) * 2.8 - Math.sin(line) * side;
            cy = ay + 1.62 + Double.parseDouble(s[6]);
            cz = az - Math.sin(line) * 2.8 + Math.cos(line) * side;
        }
        double k = camSet ? 0.12 : 1; // a camera operator's lag, not a locked-on robot
        camX += (cx - camX) * k;
        camY += (cy - camY) * k;
        camZ += (cz - camZ) * k;
        lookX += (lx - lookX) * k;
        lookY += (ly - lookY) * k;
        lookZ += (lz - lookZ) * k;
        camSet = true;
        // keep the fighters in sight: stop short of a wall or slope in between, as vanilla's third person does.
        // Only for this frame, so the camera swings back out once past it
        double ex = camX, ey = camY, ez = camZ;
        Vec3 look = new Vec3(lookX, lookY, lookZ);
        MovingObjectPosition wall = mc.theWorld.rayTraceBlocks(look, new Vec3(camX, camY, camZ), false, true, false);
        if (wall != null && look.distanceTo(wall.hitVec) > 0.5) { // a look point inside a block blocks every ray
            double f = (look.distanceTo(wall.hitVec) - 0.3) / look.distanceTo(new Vec3(camX, camY, camZ));
            ex = lookX + (camX - lookX) * f;
            ey = lookY + (camY - lookY) * f;
            ez = lookZ + (camZ - lookZ) * f;
        }
        double dx = lookX - ex, dy = lookY - ey, dz = lookZ - ez;
        float yaw = (float) Math.toDegrees(Math.atan2(dz, dx)) - 90;
        float pitch = (float) -Math.toDegrees(Math.atan2(dy, MathHelper.sqrt_double(dx * dx + dz * dz)));
        double y = ey - viewer.getEyeHeight();
        viewer.setPosition(ex, y, ez);
        viewer.prevPosX = viewer.lastTickPosX = ex;
        viewer.prevPosY = viewer.lastTickPosY = y;
        viewer.prevPosZ = viewer.lastTickPosZ = ez;
        viewer.rotationYaw = viewer.prevRotationYaw = viewer.rotationYawHead = viewer.prevRotationYawHead = yaw;
        viewer.rotationPitch = viewer.prevRotationPitch = pitch;
        viewer.motionX = viewer.motionY = viewer.motionZ = 0;
    }

    private boolean blocked(double lx, double ly, double lz, double angle, double radius, double height) {
        return mc.theWorld.rayTraceBlocks(new Vec3(lx, ly, lz),
                new Vec3(lx + Math.cos(angle) * radius, ly + height, lz + Math.sin(angle) * radius), false, true, false) != null;
    }

    private static double lerp(double a, double b, double f) {
        return a + (b - a) * f;
    }
}
