package net.toshimichi.recorder.mixin;

import net.minecraft.client.gui.GuiButton;
import net.minecraft.client.gui.GuiIngameMenu;
import net.minecraft.client.gui.GuiScreen;
import net.toshimichi.recorder.Recording;
import net.toshimichi.recorder.Replay;
import net.toshimichi.recorder.gui.SeekSlider;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(GuiIngameMenu.class)
public abstract class MixinGuiIngameMenu extends GuiScreen {

    private static final int DISCONNECT_BUTTON = 1;
    private static final int RECORD_BUTTON = 0x5245_4331;
    private static final int PAUSE_BUTTON = 0x5245_4332;
    private static final int SEEK_SLIDER = 0x5245_4333;

    // the row under Disconnect, which Forge's layout leaves free
    @Inject(method = "initGui", at = @At("RETURN"))
    private void addRecorderButtons(CallbackInfo ci) {
        int y = height / 4 + 128;
        Replay replay = Replay.current();
        if (replay == null) {
            buttonList.add(new GuiButton(RECORD_BUTTON, width / 2 - 100, y, recordLabel()));
        } else {
            buttonList.add(new GuiButton(PAUSE_BUTTON, width / 2 - 100, y, 98, 20, pauseLabel(replay)));
            buttonList.add(new SeekSlider(SEEK_SLIDER, width / 2 + 2, y, 98, replay));
        }
    }

    @Inject(method = "actionPerformed", at = @At("HEAD"), cancellable = true)
    private void onRecorderButton(GuiButton button, CallbackInfo ci) {
        Replay replay = Replay.current();
        switch (button.id) {
            case RECORD_BUTTON:
                Recording.toggle(mc);
                button.displayString = recordLabel();
                break;
            case PAUSE_BUTTON:
                replay.togglePause();
                button.displayString = pauseLabel(replay);
                break;
            case DISCONNECT_BUTTON:
                // vanilla would hand a replay over to the multiplayer screen
                if (replay == null) return;
                replay.exit();
                ci.cancel();
                break;
        }
    }

    private static String recordLabel() {
        return Recording.isRecording() ? "Stop Recording" : "Start Recording";
    }

    private static String pauseLabel(Replay replay) {
        return replay.isPaused() ? "Resume" : "Pause";
    }
}
