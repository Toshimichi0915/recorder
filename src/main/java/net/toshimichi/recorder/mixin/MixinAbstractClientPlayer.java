package net.toshimichi.recorder.mixin;

import net.minecraft.client.entity.AbstractClientPlayer;
import net.minecraft.client.entity.EntityPlayerSP;
import net.minecraft.world.WorldSettings;
import net.toshimichi.recorder.Replay;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(AbstractClientPlayer.class)
public abstract class MixinAbstractClientPlayer {

    // vanilla asks the tab list, which holds the recorded player's mode, not the viewer's; noclip and
    // scroll-to-change-fly-speed both hang off this
    @Inject(method = "isSpectator", at = @At("HEAD"), cancellable = true)
    private void viewerSpectator(CallbackInfoReturnable<Boolean> cir) {
        Replay replay = Replay.current();
        if (replay != null && (Object) this instanceof EntityPlayerSP) {
            cir.setReturnValue(replay.getGameType() == WorldSettings.GameType.SPECTATOR);
        }
    }
}
