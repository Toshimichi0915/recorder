package net.toshimichi.recorder.mixin;

import net.minecraft.client.gui.GuiButton;
import net.minecraft.client.gui.GuiMainMenu;
import net.minecraft.client.gui.GuiScreen;
import net.toshimichi.recorder.gui.RecordingsScreen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(GuiMainMenu.class)
public abstract class MixinGuiMainMenu extends GuiScreen {

    private static final int RECORDINGS_BUTTON = 0x5245_4331;

    // left of the Multiplayer button
    @Inject(method = "initGui", at = @At("RETURN"))
    private void addRecordingsButton(CallbackInfo ci) {
        buttonList.add(new GuiButton(RECORDINGS_BUTTON, width / 2 - 124, height / 4 + 72, 20, 20, "R"));
    }

    @Inject(method = "actionPerformed", at = @At("HEAD"))
    private void openRecordings(GuiButton button, CallbackInfo ci) {
        if (button.id == RECORDINGS_BUTTON) mc.displayGuiScreen(new RecordingsScreen(this));
    }
}
