package net.toshimichi.recorder.mixin;

import net.minecraft.entity.EntityTrackerEntry;
import net.minecraft.network.Packet;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

// the server's own spawn packet choice: object type ids, data fields and hanging positions are all in here
@Mixin(EntityTrackerEntry.class)
public interface InvokerEntityTrackerEntry {

    @Invoker("createSpawnPacket")
    Packet<?> invokeCreateSpawnPacket();
}
