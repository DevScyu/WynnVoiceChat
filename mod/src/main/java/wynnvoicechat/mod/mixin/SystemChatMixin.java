package wynnvoicechat.mod.mixin;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.network.protocol.game.ClientboundSystemChatPacket;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import wynnvoicechat.mod.VoiceMod;

/**
 * Hooks the packet rather than {@code ChatListener}: chat mods (Wynntils' chat tabs among them) replace
 * the listener path and re-emit messages themselves, so a hook there never sees Wynncraft's lines.
 */
@Mixin(ClientPacketListener.class)
abstract class SystemChatMixin {
    @Inject(method = "handleSystemChat", at = @At("HEAD"), cancellable = true)
    private void wynnvoicechat$trackSocial(ClientboundSystemChatPacket packet, CallbackInfo ci) {
        // The first call is on the network thread and only reschedules onto the render thread
        if (!Minecraft.getInstance().isSameThread() || packet.overlay()) return;
        if (VoiceMod.interceptSystemChat(packet.content())) ci.cancel();
    }
}
