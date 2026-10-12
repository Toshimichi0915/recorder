package net.toshimichi.recorder.mixin;

import net.minecraft.client.Minecraft;
import net.minecraft.client.audio.SoundHandler;
import net.minecraft.entity.player.EntityPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

@Mixin(Minecraft.class)
public abstract class MixinMinecraft {

    // hear from the eyes being looked through; vanilla relies on the server keeping the spectator on its target
    // ponytail: players only, the listener must be an EntityPlayer; a watched mob is heard from the viewer
    @Redirect(method = "runGameLoop", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/audio/SoundHandler;setListener(Lnet/minecraft/entity/player/EntityPlayer;F)V"))
    private void listenFromView(SoundHandler handler, EntityPlayer player, float partialTicks) {
        Minecraft mc = (Minecraft) (Object) this;
        handler.setListener(mc.getRenderViewEntity() instanceof EntityPlayer ? (EntityPlayer) mc.getRenderViewEntity() : player, partialTicks);
    }
}
