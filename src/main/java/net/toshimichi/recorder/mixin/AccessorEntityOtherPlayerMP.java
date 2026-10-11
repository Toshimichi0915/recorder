package net.toshimichi.recorder.mixin;

import net.minecraft.client.entity.EntityOtherPlayerMP;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

// where setPositionAndRotation2 told the player to glide to
@Mixin(EntityOtherPlayerMP.class)
public interface AccessorEntityOtherPlayerMP {

    @Accessor("otherPlayerMPX")
    double getTargetX();

    @Accessor("otherPlayerMPY")
    double getTargetY();

    @Accessor("otherPlayerMPZ")
    double getTargetZ();

    @Accessor("otherPlayerMPYaw")
    double getTargetYaw();

    @Accessor("otherPlayerMPPitch")
    double getTargetPitch();
}
