package wynnvoice.mod.mixin;

import net.minecraft.client.multiplayer.chat.ChatListener;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import wynnvoice.mod.VoiceMod;

@Mixin(ChatListener.class)
abstract class ChatListenerMixin {
    @Inject(method = "handleSystemMessage(Lnet/minecraft/network/chat/Component;Z)V", at = @At("HEAD"), cancellable = true)
    private void wynnvoice$trackSocial(Component message, boolean overlay, CallbackInfo ci) {
        if (!overlay && VoiceMod.interceptSystemChat(message)) ci.cancel();
    }
}
