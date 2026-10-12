package net.toshimichi.recorder.mixin;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiIngame;
import net.minecraft.client.renderer.ActiveRenderInfo;
import net.minecraft.client.renderer.EntityRenderer;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.world.WorldSettings;
import net.toshimichi.recorder.Replay;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(EntityRenderer.class)
public abstract class MixinEntityRenderer {

    @Unique
    private WorldSettings.GameType viewerGameType; // set only while a frame is drawn as the recorded player's

    // particles face the camera, not wherever the viewer's own body is looking
    // ponytail: players only, the render info takes an EntityPlayer; a watched mob's particles face the viewer
    @Redirect(method = "renderWorldPass", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/renderer/ActiveRenderInfo;updateRenderInfo(Lnet/minecraft/entity/player/EntityPlayer;Z)V"))
    private void faceView(EntityPlayer player, boolean thirdPerson) {
        Minecraft mc = Minecraft.getMinecraft();
        ActiveRenderInfo.updateRenderInfo(mc.getRenderViewEntity() instanceof EntityPlayer ? (EntityPlayer) mc.getRenderViewEntity() : player, thirdPerson);
    }

    // through the recorded player's eyes, draw the frame for their game mode: hand, hotbar, bars and crosshair
    // where the spectating viewer would get none. Only for the frame, so the viewer's input keeps their own
    @Inject(method = "updateCameraAndRender", at = @At("HEAD"))
    private void asRecorded(float partialTicks, long nanoTime, CallbackInfo ci) {
        Replay.Puppet puppet = Replay.possessed();
        if (puppet == null) return;
        Minecraft mc = Minecraft.getMinecraft();
        viewerGameType = mc.playerController.getCurrentGameType();
        ((AccessorPlayerControllerMP) mc.playerController).setCurrentGameType(puppet.getGameType());
    }

    @Inject(method = "updateCameraAndRender", at = @At("RETURN"))
    private void asViewer(float partialTicks, long nanoTime, CallbackInfo ci) {
        if (viewerGameType == null) return;
        ((AccessorPlayerControllerMP) Minecraft.getMinecraft().playerController).setCurrentGameType(viewerGameType);
        viewerGameType = null;
    }

    // the bars read the viewing entity, but the recorded health, food, experience and effects all land on the
    // viewer's own player, as they did on the recorder's
    // ponytail: air and mount health read the viewer's own position and ride; forward them if missed
    @Redirect(method = "updateCameraAndRender", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/gui/GuiIngame;renderGameOverlay(F)V"))
    private void recordedOverlay(GuiIngame gui, float partialTicks) {
        Minecraft mc = Minecraft.getMinecraft();
        Entity view = mc.getRenderViewEntity();
        if (viewerGameType != null) ((AccessorMinecraft) mc).setViewEntity(mc.thePlayer);
        try {
            gui.renderGameOverlay(partialTicks);
        } finally {
            ((AccessorMinecraft) mc).setViewEntity(view);
        }
    }
}
