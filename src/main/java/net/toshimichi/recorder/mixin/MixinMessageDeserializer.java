package net.toshimichi.recorder.mixin;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import net.minecraft.util.MessageDeserializer;
import net.toshimichi.recorder.Recording;
import net.toshimichi.recorder.RecordingFile;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.List;

// after decrypt and decompress, before the packet object exists: the buffer is one whole frame, id + payload
@Mixin(MessageDeserializer.class)
public class MixinMessageDeserializer {

    @Inject(method = "decode", at = @At("HEAD"), remap = false)
    private void record(ChannelHandlerContext ctx, ByteBuf buf, List<Object> out, CallbackInfo ci) {
        Recording.record(ctx.channel(), RecordingFile.CLIENTBOUND, buf);
    }
}
