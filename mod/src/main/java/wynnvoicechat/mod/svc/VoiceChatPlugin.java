package wynnvoicechat.mod.svc;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import de.maxhenkel.voicechat.api.VoicechatPlugin;
import de.maxhenkel.voicechat.api.events.EventRegistration;
import de.maxhenkel.voicechat.api.events.NameTagIconRenderEvent;
import de.maxhenkel.voicechat.events.RenderEvents;
import net.fabricmc.fabric.api.event.Event;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.entity.state.AvatarRenderState;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.client.renderer.state.CameraRenderState;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.resources.Identifier;
import wynnvoicechat.mod.VoiceMod;
import wynnvoicechat.protocol.Peer;

/** Loaded by Simple Voice Chat through the {@code voicechat} entrypoint; the only class that touches SVC's own classes. */
public final class VoiceChatPlugin implements VoicechatPlugin {
    private static final Identifier AFTER_VOICECHAT = Identifier.fromNamespaceAndPath(VoiceMod.MOD_ID, "after_voicechat");
    private static final int SEE_THROUGH_ALPHA = 127;

    // Set while SVC's icon for the current nameplate is cancelled, consumed by our listener right after; render thread only
    private Identifier pendingIcon;

    @Override
    public String getPluginId() {
        return VoiceMod.MOD_ID;
    }

    @Override
    public void registerEvents(EventRegistration registration) {
        registration.registerEvent(NameTagIconRenderEvent.class, this::onIconRender);
        RenderEvents.RENDER_NAMEPLATE.addPhaseOrdering(Event.DEFAULT_PHASE, AFTER_VOICECHAT);
        RenderEvents.RENDER_NAMEPLATE.register(AFTER_VOICECHAT, this::onNameplate);
    }

    // SVC fires this only when it is about to draw an icon, so its hide-icons settings gate ours too
    private void onIconRender(NameTagIconRenderEvent event) {
        Peer peer = VoiceMod.peer(event.getEntityId());
        if (peer == null) return;
        pendingIcon = IconPicker.iconFor(peer);
        event.cancel();
    }

    private void onNameplate(EntityRenderState s, CameraRenderState camera, PoseStack stack, SubmitNodeCollector collector) {
        Identifier icon = pendingIcon;
        pendingIcon = null;
        if (icon == null || !(s instanceof AvatarRenderState state) || s.nameTag == null || s.nameTagAttachment == null) return;

        stack.pushPose();
        stack.translate(s.nameTagAttachment);
        stack.translate(0D, 0.5D, 0D);
        stack.mulPose(camera.orientation);
        stack.scale(0.025F, -0.025F, 0.025F);
        float x = Minecraft.getInstance().font.width(s.nameTag) / 2F + 2F;
        int light = state.lightCoords;
        collector.submitCustomGeometry(stack, RenderTypes.text(icon), (pose, buffer) ->
                quad(buffer, pose, x, state.isDiscrete ? SEE_THROUGH_ALPHA : 255, light));
        if (!state.isDiscrete) {
            collector.submitCustomGeometry(stack, RenderTypes.textSeeThrough(icon), (pose, buffer) ->
                    quad(buffer, pose, x, SEE_THROUGH_ALPHA, light));
        }
        stack.popPose();
    }

    private static void quad(VertexConsumer buffer, PoseStack.Pose pose, float x, int alpha, int light) {
        vertex(buffer, pose, x, 9F, 0F, 1F, alpha, light);
        vertex(buffer, pose, x + 10F, 9F, 1F, 1F, alpha, light);
        vertex(buffer, pose, x + 10F, -1F, 1F, 0F, alpha, light);
        vertex(buffer, pose, x, -1F, 0F, 0F, alpha, light);
    }

    private static void vertex(VertexConsumer buffer, PoseStack.Pose pose, float x, float y, float u, float v, int alpha, int light) {
        buffer.addVertex(pose.pose(), x, y, 0F)
                .setColor(255, 255, 255, alpha)
                .setUv(u, v)
                .setOverlay(OverlayTexture.NO_OVERLAY)
                .setLight(light)
                .setNormal(pose, 0F, 0F, -1F);
    }
}
