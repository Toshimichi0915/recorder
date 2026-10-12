package net.toshimichi.recorder.mixin;

import net.minecraft.client.renderer.entity.RendererLivingEntity;
import net.minecraft.entity.player.EntityPlayer;
import net.toshimichi.recorder.Replay;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

@Mixin(RendererLivingEntity.class)
public abstract class MixinRendererLivingEntity {

    // invisible to whoever recorded it, not see-through as to the spectating viewer; teammates stay see-through
    @ModifyArg(method = {"renderModel", "canRenderName"}, at = @At(value = "INVOKE",
            target = "Lnet/minecraft/entity/EntityLivingBase;isInvisibleToPlayer(Lnet/minecraft/entity/player/EntityPlayer;)Z"))
    private EntityPlayer recordedEyes(EntityPlayer player) {
        Replay.Puppet puppet = Replay.recorded();
        return puppet != null ? puppet : player;
    }
}
