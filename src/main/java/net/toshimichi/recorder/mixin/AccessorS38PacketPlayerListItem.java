package net.toshimichi.recorder.mixin;

import net.minecraft.network.play.server.S38PacketPlayerListItem;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import java.util.List;

// the only constructors take EntityPlayerMP; the client has NetworkPlayerInfo
@Mixin(S38PacketPlayerListItem.class)
public interface AccessorS38PacketPlayerListItem {

    @Accessor("action")
    void setAction(S38PacketPlayerListItem.Action action);

    @Accessor("players")
    List<S38PacketPlayerListItem.AddPlayerData> getPlayers();
}
