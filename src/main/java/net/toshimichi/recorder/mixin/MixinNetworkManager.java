package net.toshimichi.recorder.mixin;

import io.netty.channel.ChannelHandlerContext;
import net.minecraft.network.NetworkManager;
import net.minecraft.network.Packet;
import net.toshimichi.recorder.Recording;
import net.toshimichi.recorder.RecordingFile;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

// The integrated server talks over a netty LocalChannel, which carries packet objects and never runs the
// serializer codecs, so singleplayer is captured here at the packet level instead. Remote connections are
// left to the codec mixins, which see the bytes after compression and encryption are peeled off.
@Mixin(NetworkManager.class)
public class MixinNetworkManager {

    @Inject(method = "sendPacket(Lnet/minecraft/network/Packet;)V", at = @At("HEAD"))
    private void recordSent(Packet<?> packet, CallbackInfo ci) {
        NetworkManager self = (NetworkManager) (Object) this;
        if (self.isLocalChannel()) Recording.record(self.channel(), RecordingFile.SERVERBOUND, packet);
    }

    // same bridge-method trick as MixinMessageSerializer: SimpleChannelInboundHandler<Packet> erases to Object
    @Inject(method = "channelRead0(Lio/netty/channel/ChannelHandlerContext;Ljava/lang/Object;)V", at = @At("HEAD"), remap = false)
    private void recordReceived(ChannelHandlerContext ctx, Object packet, CallbackInfo ci) {
        NetworkManager self = (NetworkManager) (Object) this;
        if (self.isLocalChannel()) Recording.record(self.channel(), RecordingFile.CLIENTBOUND, (Packet<?>) packet);
    }
}
