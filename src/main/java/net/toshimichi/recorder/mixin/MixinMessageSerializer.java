package net.toshimichi.recorder.mixin;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import net.minecraft.util.MessageSerializer;
import net.toshimichi.recorder.Recording;
import net.toshimichi.recorder.RecordingFile;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(MessageSerializer.class)
public class MixinMessageSerializer {

    // The real encode(ctx, Packet, buf) is obfuscated in production (and remap = false cannot follow its
    // descriptor there), so this targets the erased bridge encode(ctx, Object, buf) that javac generates for
    // MessageToByteEncoder<Packet>; it keeps its name on every side. Its RETURN is after the buffer is filled.
    // The annotation processor warns that it cannot see the bridge: javac's source model hides synthetic methods.
    @Inject(method = "encode(Lio/netty/channel/ChannelHandlerContext;Ljava/lang/Object;Lio/netty/buffer/ByteBuf;)V",
            at = @At("RETURN"), remap = false)
    private void record(ChannelHandlerContext ctx, Object packet, ByteBuf out, CallbackInfo ci) {
        Recording.record(ctx.channel(), RecordingFile.SERVERBOUND, out);
    }
}
