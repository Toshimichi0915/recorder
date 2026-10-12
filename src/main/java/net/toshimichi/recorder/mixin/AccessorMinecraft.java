package net.toshimichi.recorder.mixin;

import net.minecraft.client.Minecraft;
import net.minecraft.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

// setRenderViewEntity also resets the entity shader, which a swap for one draw must not do
@Mixin(Minecraft.class)
public interface AccessorMinecraft {

    @Accessor("renderViewEntity")
    void setViewEntity(Entity entity);
}
