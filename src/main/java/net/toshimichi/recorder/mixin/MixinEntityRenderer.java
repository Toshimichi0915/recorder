package net.toshimichi.recorder.mixin;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.ActiveRenderInfo;
import net.minecraft.client.renderer.EntityRenderer;
import net.minecraft.entity.player.EntityPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

@Mixin(EntityRenderer.class)
public abstract class MixinEntityRenderer {

    // particles face the camera, not wherever the viewer's own body is looking
    // ponytail: players only, the render info takes an EntityPlayer; a watched mob's particles face the viewer
    @Redirect(method = "renderWorldPass", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/renderer/ActiveRenderInfo;updateRenderInfo(Lnet/minecraft/entity/player/EntityPlayer;Z)V"))
    private void faceView(EntityPlayer player, boolean thirdPerson) {
        Minecraft mc = Minecraft.getMinecraft();
        ActiveRenderInfo.updateRenderInfo(mc.getRenderViewEntity() instanceof EntityPlayer ? (EntityPlayer) mc.getRenderViewEntity() : player, thirdPerson);
    }
}
