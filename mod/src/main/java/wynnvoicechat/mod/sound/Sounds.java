package wynnvoicechat.mod.sound;

import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.sounds.AbstractTickableSoundInstance;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.resources.Identifier;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import wynnvoicechat.mod.VoiceMod;
import wynnvoicechat.mod.session.Cue;

/** Plays the cues from {@code assets/wynnvoicechat/sounds.json} on the Voice/Speech slider; at most one ring loop at a time. Render thread only. */
public final class Sounds {
    // ponytail: invites expire after 30 s on the relay and only the caller is told; cap the loops instead of adding a packet
    private static final int MAX_LOOP_TICKS = 35 * 20;

    private Loop loop;

    public void play(Cue cue) {
        if (cue.call) stop();
        if (cue.loop) {
            loop = new Loop(cue);
            Minecraft.getInstance().getSoundManager().play(loop);
        } else {
            Minecraft.getInstance().getSoundManager().play(new SimpleSoundInstance(id(cue), SoundSource.VOICE, 1F, 1F,
                    SoundInstance.createUnseededRandom(), false, 0, SoundInstance.Attenuation.NONE, 0, 0, 0, true));
        }
    }

    public void stop() {
        if (loop == null) return;
        loop.end();
        loop = null;
    }

    private static Identifier id(Cue cue) {
        return Identifier.fromNamespaceAndPath(VoiceMod.MOD_ID, cue.event);
    }

    private static final class Loop extends AbstractTickableSoundInstance {
        private int ticks;

        Loop(Cue cue) {
            super(SoundEvent.createVariableRangeEvent(id(cue)), SoundSource.VOICE, SoundInstance.createUnseededRandom());
            looping = true;
            relative = true;
            attenuation = Attenuation.NONE;
        }

        @Override
        public void tick() {
            if (++ticks >= MAX_LOOP_TICKS) stop();
        }

        void end() {
            stop();
        }
    }
}
