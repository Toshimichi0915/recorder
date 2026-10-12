package net.toshimichi.recorder.mixin;

import net.minecraft.client.Minecraft;
import net.minecraft.client.entity.AbstractClientPlayer;
import net.minecraft.client.entity.EntityPlayerSP;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.renderer.ItemRenderer;
import net.minecraft.entity.Entity;
import net.toshimichi.recorder.Replay;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import org.spongepowered.asm.mixin.injection.Redirect;

// through the recorded player's eyes, the hand is theirs: its swing, the item in use, their light and skin.
// The held item itself already is, as their inventory is the viewer's
@Mixin(ItemRenderer.class)
public abstract class MixinItemRenderer {

    @Shadow
    private void rotateWithPlayerRotations(EntityPlayerSP player, float partialTicks) {
    }

    @ModifyVariable(method = "renderItemInFirstPerson", at = @At("STORE"))
    private AbstractClientPlayer recordedHand(AbstractClientPlayer player) {
        Replay.Puppet puppet = Replay.possessed();
        return puppet != null ? puppet : player;
    }

    // the one use that casts to EntityPlayerSP; the sway below swaps it back right after
    @ModifyVariable(method = "renderItemInFirstPerson", at = @At(value = "INVOKE", shift = At.Shift.AFTER,
            target = "Lnet/minecraft/client/renderer/ItemRenderer;setLightMapFromPlayer(Lnet/minecraft/client/entity/AbstractClientPlayer;)V"))
    private AbstractClientPlayer viewerForCast(AbstractClientPlayer player) {
        return Replay.possessed() != null ? Minecraft.getMinecraft().thePlayer : player;
    }

    // like rotateWithPlayerRotations, with the recorded player's lagging arm rather than the viewer's mouse
    @Redirect(method = "renderItemInFirstPerson", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/renderer/ItemRenderer;rotateWithPlayerRotations(Lnet/minecraft/client/entity/EntityPlayerSP;F)V"))
    private void recordedSway(ItemRenderer renderer, EntityPlayerSP player, float partialTicks) {
        Replay.Puppet puppet = Replay.possessed();
        if (puppet == null) {
            rotateWithPlayerRotations(player, partialTicks);
            return;
        }
        float pitch = puppet.prevArmPitch + (puppet.armPitch - puppet.prevArmPitch) * partialTicks;
        float yaw = puppet.prevArmYaw + (puppet.armYaw - puppet.prevArmYaw) * partialTicks;
        // the look interpolated as the camera has it: the recorder's own changed every frame, the puppet's every tick
        float lookPitch = puppet.prevRotationPitch + (puppet.rotationPitch - puppet.prevRotationPitch) * partialTicks;
        float lookYaw = puppet.prevRotationYaw + (puppet.rotationYaw - puppet.prevRotationYaw) * partialTicks;
        GlStateManager.rotate((lookPitch - pitch) * 0.1F, 1.0F, 0.0F, 0.0F);
        GlStateManager.rotate((lookYaw - yaw) * 0.1F, 0.0F, 1.0F, 0.0F);
    }

    @ModifyVariable(method = "renderItemInFirstPerson", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/renderer/GlStateManager;enableRescaleNormal()V"))
    private AbstractClientPlayer recordedHandAgain(AbstractClientPlayer player) {
        return recordedHand(player);
    }

    // the bare arm: their model (slim or not), sleeves and skin
    @ModifyArg(method = "renderPlayerArm", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/renderer/entity/RenderManager;getEntityRenderObject(Lnet/minecraft/entity/Entity;)Lnet/minecraft/client/renderer/entity/Render;"))
    private Entity recordedArmModel(Entity entity) {
        Replay.Puppet puppet = Replay.possessed();
        return puppet != null ? puppet : entity;
    }

    @ModifyArg(method = "renderPlayerArm", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/renderer/entity/RenderPlayer;renderRightArm(Lnet/minecraft/client/entity/AbstractClientPlayer;)V"))
    private AbstractClientPlayer recordedArm(AbstractClientPlayer player) {
        return recordedHand(player);
    }
}
