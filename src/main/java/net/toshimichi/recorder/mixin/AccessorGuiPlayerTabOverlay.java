package net.toshimichi.recorder.mixin;

import net.minecraft.client.gui.GuiPlayerTabOverlay;
import net.minecraft.util.IChatComponent;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(GuiPlayerTabOverlay.class)
public interface AccessorGuiPlayerTabOverlay {

    @Accessor("header")
    IChatComponent getHeader();

    @Accessor("footer")
    IChatComponent getFooter();
}
