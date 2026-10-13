package net.toshimichi.recorder.mixin;

import net.minecraft.util.Timer;
import net.toshimichi.recorder.Render;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Timer.class)
public abstract class MixinTimer {

    @Inject(method = "updateTimer", at = @At("RETURN"))
    private void virtualTicks(CallbackInfo ci) {
        Render.timer((Timer) (Object) this);
    }
}
