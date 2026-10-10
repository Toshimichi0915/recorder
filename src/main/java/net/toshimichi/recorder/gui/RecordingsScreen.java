package net.toshimichi.recorder.gui;

import net.minecraft.client.gui.GuiButton;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.gui.GuiSlot;
import net.minecraft.client.gui.GuiYesNo;
import net.minecraft.client.gui.GuiYesNoCallback;
import net.minecraft.util.EnumChatFormatting;
import net.toshimichi.recorder.Recording;
import net.toshimichi.recorder.Replay;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;

public class RecordingsScreen extends GuiScreen implements GuiYesNoCallback {

    private static final Logger log = LogManager.getLogger();
    private static final int REPLAY = 0;
    private static final int DELETE = 1;
    private static final int DONE = 2;
    private static final int CONVERT = 3;

    private final GuiScreen parent;
    private List<File> files = new ArrayList<>();
    private int selected = -1;
    private RecordingList list;
    private GuiButton replayButton;
    private GuiButton deleteButton;
    private GuiButton convertButton;
    private String status = "";

    public RecordingsScreen(GuiScreen parent) {
        this.parent = parent;
    }

    @Override
    public void initGui() {
        refresh();
        list = new RecordingList();
        buttonList.add(replayButton = new GuiButton(REPLAY, width / 2 - 154, height - 52, 100, 20, "Replay"));
        buttonList.add(convertButton = new GuiButton(CONVERT, width / 2 - 50, height - 52, 100, 20, "Convert to World"));
        buttonList.add(deleteButton = new GuiButton(DELETE, width / 2 + 54, height - 52, 100, 20, "Delete"));
        buttonList.add(new GuiButton(DONE, width / 2 - 100, height - 28, 200, 20, "Done"));
    }

    // timestamped names sort chronologically, so reversed is newest first
    private void refresh() {
        File[] found = Recording.directory().listFiles((dir, name) -> name.endsWith(".mcrc"));
        files = found == null ? new ArrayList<>() : new ArrayList<>(Arrays.asList(found));
        files.sort(Comparator.comparing(File::getName).reversed());
        selected = -1;
    }

    @Override
    public void updateScreen() {
        replayButton.enabled = convertButton.enabled = deleteButton.enabled = selected >= 0;
    }

    @Override
    protected void actionPerformed(GuiButton button) {
        switch (button.id) {
            case REPLAY:
                replay();
                break;
            case CONVERT:
                convert();
                break;
            case DELETE:
                mc.displayGuiScreen(new GuiYesNo(this, "Delete this recording?", files.get(selected).getName(), DELETE));
                break;
            case DONE:
                mc.displayGuiScreen(parent);
                break;
        }
    }

    @Override
    public void confirmClicked(boolean result, int id) {
        if (result && !files.get(selected).delete()) status = EnumChatFormatting.RED + "Could not delete the file";
        mc.displayGuiScreen(this); // re-runs initGui, which refreshes the list
    }

    private void replay() {
        if (selected < 0) return;
        File file = files.get(selected);
        try {
            Replay.start(file, this);
        } catch (IOException e) {
            log.error("Failed to open {}", file, e);
            status = EnumChatFormatting.RED + e.getMessage();
        }
    }

    private void convert() {
        File file = files.get(selected);
        try {
            status = "Saved as world " + Replay.convert(file, this);
        } catch (IOException e) {
            log.error("Failed to convert {}", file, e);
            status = EnumChatFormatting.RED + e.getMessage();
        }
    }

    @Override
    public void handleMouseInput() throws IOException {
        super.handleMouseInput();
        list.handleMouseInput();
    }

    @Override
    public void drawScreen(int mouseX, int mouseY, float partialTicks) {
        list.drawScreen(mouseX, mouseY, partialTicks);
        drawCenteredString(fontRendererObj, "Recordings", width / 2, 16, 0xFFFFFF);
        drawCenteredString(fontRendererObj, status, width / 2, height - 66, 0xA0A0A0);
        super.drawScreen(mouseX, mouseY, partialTicks);
    }

    private class RecordingList extends GuiSlot {

        RecordingList() {
            super(RecordingsScreen.this.mc, RecordingsScreen.this.width, RecordingsScreen.this.height,
                    32, RecordingsScreen.this.height - 76, 24);
        }

        @Override
        protected int getSize() {
            return files.size();
        }

        @Override
        protected void elementClicked(int index, boolean doubleClick, int mouseX, int mouseY) {
            selected = index;
            if (doubleClick) replay();
        }

        @Override
        protected boolean isSelected(int index) {
            return index == selected;
        }

        @Override
        protected void drawBackground() {
            drawDefaultBackground();
        }

        @Override
        protected void drawSlot(int index, int x, int y, int rowHeight, int mouseX, int mouseY) {
            File file = files.get(index);
            drawString(fontRendererObj, file.getName(), x + 2, y + 1, 0xFFFFFF);
            drawString(fontRendererObj, String.format("%.1f MB", file.length() / 1048576.0), x + 2, y + 12, 0x808080);
        }
    }
}
