package net.toshimichi.recorder;

import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.common.event.FMLInitializationEvent;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.InputEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;
import net.minecraftforge.fml.common.network.FMLNetworkEvent;
import org.lwjgl.input.Keyboard;
import org.lwjgl.input.Mouse;

@Mod(modid = "recorder", name = "Recorder", version = "1.0", clientSideOnly = true)
public class Recorder {

    @Mod.EventHandler
    public void init(FMLInitializationEvent event) {
        MinecraftForge.EVENT_BUS.register(this);
        Render.init();
        if (Render.current() != null) MinecraftForge.EVENT_BUS.register(Render.current());
    }

    @SubscribeEvent
    public void onTick(TickEvent.ClientTickEvent event) {
        Replay replay = Replay.current();
        if (event.phase == TickEvent.Phase.START && replay != null) replay.tick();
    }

    // Forge fires these inside Minecraft's input loops, while the LWJGL event accessors still point at the event
    @SubscribeEvent
    public void onKey(InputEvent.KeyInputEvent event) {
        Recording.recordInput(RecordingFile.KEYBOARD, Keyboard.getEventKey(), Keyboard.getEventKeyState());
    }

    @SubscribeEvent
    public void onMouse(InputEvent.MouseInputEvent event) {
        // -1 is motion or scroll, which fires far too often to be worth a row
        if (Mouse.getEventButton() >= 0) Recording.recordInput(RecordingFile.MOUSE, Mouse.getEventButton(), Mouse.getEventButtonState());
    }

    // a recording must not outlive its connection, or the next server's packets land in the same file
    @SubscribeEvent
    public void onDisconnect(FMLNetworkEvent.ClientDisconnectionFromServerEvent event) {
        Recording.stop();
    }
}
