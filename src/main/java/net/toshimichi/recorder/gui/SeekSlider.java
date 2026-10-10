package net.toshimichi.recorder.gui;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiButton;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.util.MathHelper;
import net.toshimichi.recorder.Replay;

// GuiOptionSlider without the GameSettings coupling; jumps on release so dragging does not reload the world per pixel
public class SeekSlider extends GuiButton {

    private final Replay replay;
    private float value;
    private boolean dragging;

    public SeekSlider(int id, int x, int y, int width, Replay replay) {
        super(id, x, y, width, 20, "");
        this.replay = replay;
    }

    @Override
    protected int getHoverState(boolean mouseOver) {
        return 0;
    }

    // GuiButton.drawButton calls this every frame, before it draws the label
    @Override
    protected void mouseDragged(Minecraft mc, int mouseX, int mouseY) {
        if (!visible) return;

        if (dragging) {
            value = MathHelper.clamp_float((mouseX - (xPosition + 4)) / (float) (width - 8), 0, 1);
        } else {
            value = replay.duration() == 0 ? 0 : (float) replay.elapsed() / replay.duration();
        }
        displayString = format(target()) + " / " + format(replay.duration());

        mc.getTextureManager().bindTexture(buttonTextures);
        GlStateManager.color(1, 1, 1, 1);
        int knob = xPosition + (int) (value * (width - 8));
        drawTexturedModalRect(knob, yPosition, 0, 66, 4, 20);
        drawTexturedModalRect(knob + 4, yPosition, 196, 66, 4, 20);
    }

    @Override
    public boolean mousePressed(Minecraft mc, int mouseX, int mouseY) {
        if (!super.mousePressed(mc, mouseX, mouseY)) return false;
        dragging = true;
        return true;
    }

    @Override
    public void mouseReleased(int mouseX, int mouseY) {
        if (!dragging) return;
        dragging = false;
        replay.jump(target());
    }

    private long target() {
        return (long) (value * replay.duration());
    }

    private static String format(long millis) {
        long seconds = millis / 1000;
        return String.format("%d:%02d", seconds / 60, seconds % 60);
    }
}
