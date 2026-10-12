package net.toshimichi.recorder.mixin;

import net.minecraft.client.multiplayer.PlayerControllerMP;
import net.minecraft.world.WorldSettings;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

// setGameType also rewrites the player's abilities, which a swap for one frame must not do
@Mixin(PlayerControllerMP.class)
public interface AccessorPlayerControllerMP {

    @Accessor("currentGameType")
    void setCurrentGameType(WorldSettings.GameType type);
}
