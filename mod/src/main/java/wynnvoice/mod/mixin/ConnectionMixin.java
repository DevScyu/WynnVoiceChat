package wynnvoice.mod.mixin;

import io.netty.channel.ChannelFutureListener;
import net.minecraft.network.Connection;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.common.ServerboundCustomPayloadPacket;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import wynnvoice.mod.VoiceMod;

@Mixin(Connection.class)
abstract class ConnectionMixin {
    @Inject(
            method = "sendPacket(Lnet/minecraft/network/protocol/Packet;Lio/netty/channel/ChannelFutureListener;Z)V",
            at = @At("HEAD"),
            cancellable = true)
    private void wynnvoice$interceptVoicechat(Packet<?> packet, ChannelFutureListener listener, boolean flush, CallbackInfo ci) {
        if (packet instanceof ServerboundCustomPayloadPacket payload && VoiceMod.interceptServerbound(payload)) ci.cancel();
    }
}
