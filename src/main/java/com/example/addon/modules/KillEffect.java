package com.example.addon.modules;

import com.example.addon.render.KillEffectLogoRenderer;
import com.example.addon.render.KillEffectMemeRenderer;
import dev.boze.api.addon.AddonModule;
import dev.boze.api.event.EventTick;
import dev.boze.api.option.ColorOption;
import dev.boze.api.option.ModeOption;
import dev.boze.api.option.SliderOption;
import dev.boze.api.option.ToggleOption;
import dev.boze.api.render.ColorMaker;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.resources.Identifier;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.monster.Enemy;
import org.joml.Vector3f;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.BooleanSupplier;

/**
 * Replaces the vanilla death animation for players. The dying player's real body is
 * always routed into vanilla's ghost-translucent render branch (MixinAvatarRenderer sets
 * isInvisible while in {@link #exploding}) and its alpha is driven continuously down to 0
 * over Duration (getFadeAlpha, applied by MixinLivingEntityRendererGhost) -- a real fade,
 * not an instant vanish. What replaces it depends on Mode:
 *
 * Dust: MixinModelFeatureRenderer captures the dying player's posed cubes on the death
 *       frame and KillEffectParticleSystem bursts them outward as glowing motes that drag
 *       to a stop and fade (no gravity).
 * Logo: KillEffectLogoRenderer pops a spinning Boze logo card out of the chest, lets it
 *       fall/bounce on the ground a couple of times, then fades once settled.
 * Meme: KillEffectMemeRenderer blinks a red circle (3x) at the death position with a red
 *       arrow pointing into it, and vine.ogg plays once -- the reaction-highlight meme.
 *
 * Death is detected purely tick-side (no Boze death event exists): a false->true
 * transition of LivingEntity#isDeadOrDying() per player UUID.
 */
public class KillEffect extends AddonModule {
    public static final KillEffect INSTANCE = new KillEffect();

    private static final Identifier VINE_SOUND_ID = Identifier.fromNamespaceAndPath("example-addon", "vine");
    private static final SoundEvent VINE_SOUND = SoundEvent.createVariableRangeEvent(VINE_SOUND_ID);

    public enum Mode { Dust, Logo, Meme }

    public final ModeOption<Mode> mode = new ModeOption<>(this, "Mode",
            "Dust: burst into glowing motes shaped like the body. Logo: a spinning Boze logo "
            + "card pops out and bounces before fading. Meme: a blinking red circle + arrow "
            + "at the death spot, with a sound.",
            Mode.Dust);

    public final ToggleOption playerToggle = new ToggleOption(this, "Players", "Apply the death effect to other players.", true);
    public final ToggleOption monsterToggle = new ToggleOption(this, "Target Monster", "Apply the death effect to hostile mobs (zombies, etc.) -- for testing.", false);
    public final ToggleOption selfToggle = new ToggleOption(this, "Self", "Apply the death effect to your own death (third person only).", true);
    public final SliderOption range = new SliderOption(this, "Range", "Only trigger for players within this distance.", 48.0, 8.0, 128.0, 1.0);
    public final SliderOption particleCount = new SliderOption(this, "Density", "Ghost-dust motes spawned per body cube.", 60.0, 5.0, 200.0,
            5.0, (BooleanSupplier) (() -> mode.getValue() == Mode.Dust));
    public final SliderOption explodeSpeed = new SliderOption(this, "ExplodeSpeed", "Initial outward speed of the burst (blocks/s).", 2.5, 0.5, 8.0,
            0.1, (BooleanSupplier) (() -> mode.getValue() == Mode.Dust));
    public final SliderOption duration = new SliderOption(this, "Duration", "Seconds the body takes to fade, and the effect lasts.", 1.2, 0.3, 4.0, 0.1);
    public final ColorOption glowColor = new ColorOption(this, "Color", "Color of the ghost dust (Dust mode).", ColorMaker.staticColor(120, 230, 255),
            1.0f, (BooleanSupplier) (() -> mode.getValue() == Mode.Dust));

    public final SliderOption logoSize = new SliderOption(this, "LogoSize", "Half-size of the spinning logo card, in blocks.", 0.6, 0.2, 2.0,
            0.1, (BooleanSupplier) (() -> mode.getValue() == Mode.Logo));
    public final SliderOption logoSpinSpeed = new SliderOption(this, "LogoSpin", "Spin speed of the logo card, degrees/second.", 240.0, 30.0, 720.0,
            10.0, (BooleanSupplier) (() -> mode.getValue() == Mode.Logo));

    public final SliderOption memeSize = new SliderOption(this, "MemeSize", "Half-size of the circle/arrow, in blocks.", 1.0, 0.3, 3.0,
            0.1, (BooleanSupplier) (() -> mode.getValue() == Mode.Meme));

    // TEMPORARY -- pinpointing why the ghost-fade doesn't visually apply. Prints at every
    // stage of the chain (track() sets exploding, extractRenderState sets isInvisible, the
    // ARGB redirect sees the ghost branch and computes alpha) so we can see exactly which
    // one stops firing instead of guessing further. Remove once the real bug is found.
    public final ToggleOption debug = new ToggleOption(this, "Debug", "Log the fade pipeline to chat for diagnosis.", false);

    // Per-UUID liveness, so we fire exactly once on the alive->dead edge.
    private final Map<UUID, Boolean> deadLastTick = new HashMap<>();
    // Players whose model must stay hidden/fading right now (death in progress).
    private final Set<UUID> exploding = new HashSet<>();
    // Players still owed a one-shot cube capture (cleared by the mixin once it fires; Dust only).
    private final Set<UUID> pendingCapture = new HashSet<>();
    // Death timestamp per UUID, driving the body's fade-out alpha.
    private final Map<UUID, Long> deathNanos = new HashMap<>();
    // Effective fade duration per UUID (usually == Duration, but capped for mobs -- see track()).
    private final Map<UUID, Float> fadeDurationSec = new HashMap<>();

    // Real vanilla mob death removal: LivingEntity.tickDeath() -- deathTime++, and once
    // deathTime >= 20 (exactly, verified via decompile) the SERVER removes the entity
    // outright, regardless of anything a client mixin does. Players are never auto-removed
    // on death (they sit in the "dead" state until respawn), so this ceiling is mob-only.
    // A Duration longer than this window means the body gets yanked out of the world
    // mid-fade -- looked exactly like an instant vanish even though the fade math itself
    // was correct the whole time (confirmed via debug log: alpha 253 -> 59 then the entity
    // was just gone). 0.9s gives a small margin under the hard 1.0s cutoff.
    private static final float MOB_MAX_FADE_SEC = 0.9f;

    public KillEffect() {
        super("KillEffect", "Bursts dying players into glowing ghost dust instead of the vanilla death animation.");
    }

    @EventHandler
    private void onTick(EventTick.Pre event) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || mc.player == null) {
            deadLastTick.clear();
            exploding.clear();
            pendingCapture.clear();
            deathNanos.clear();
            return;
        }

        Set<UUID> present = new HashSet<>();
        for (AbstractClientPlayer p : mc.level.players()) {
            present.add(p.getUUID());
            track(p, shouldApply(p));
        }

        // Hostile mobs (zombies etc.) -- Enemy marker interface, LivingEntity only.
        if (monsterToggle.getValue()) {
            for (Entity e : mc.level.entitiesForRendering()) {
                if (!(e instanceof LivingEntity mob) || !(e instanceof Enemy)) continue;
                present.add(mob.getUUID());
                track(mob, isInRange(mob));
            }
        }

        // Drop bookkeeping for entities that left the world entirely.
        deadLastTick.keySet().retainAll(present);
        exploding.retainAll(present);
        pendingCapture.retainAll(present);
        deathNanos.keySet().retainAll(present);
        fadeDurationSec.keySet().retainAll(present);
    }

    /** Fire the alive->dead edge once; clear hiding when the entity revives. */
    private void track(LivingEntity e, boolean apply) {
        UUID id = e.getUUID();
        boolean dead = e.isDeadOrDying();
        boolean wasDead = deadLastTick.getOrDefault(id, false);
        deadLastTick.put(id, dead);
        if (dead && !wasDead && apply) {
            exploding.add(id);
            deathNanos.put(id, System.nanoTime());
            fadeDurationSec.put(id, e instanceof Enemy ? Math.min(getDuration(), MOB_MAX_FADE_SEC) : getDuration());
            if (debug.getValue()) dev.boze.api.utility.ChatHelper.sendMsg("KillEffect", "§etrack(): exploding.add " + id + " mode=" + mode.getValue());
            Vector3f chestPos = new Vector3f((float) e.getX(), (float) e.getY() + (float) e.getBbHeight() * 0.5f, (float) e.getZ());
            switch (mode.getValue()) {
                case Logo -> KillEffectLogoRenderer.INSTANCE.spawn(chestPos, (float) e.getY(),
                        (float) (double) logoSize.getValue(), (float) (double) logoSpinSpeed.getValue(), getDuration());
                case Meme -> {
                    KillEffectMemeRenderer.INSTANCE.spawn(chestPos, (float) (double) memeSize.getValue());
                    Minecraft mc = Minecraft.getInstance();
                    if (mc.getSoundManager() != null) mc.getSoundManager().play(SimpleSoundInstance.forUI(VINE_SOUND, 1.0f, 1.0f));
                }
                default -> pendingCapture.add(id); // Dust: mixin captures posed cubes, then spawns the burst
            }
        }
        if (!dead && exploding.contains(id) && debug.getValue()) {
            dev.boze.api.utility.ChatHelper.sendMsg("KillEffect", "§ctrack(): exploding.remove " + id + " (dead=false)");
        }
        if (!dead) {
            exploding.remove(id);
            pendingCapture.remove(id);
            deathNanos.remove(id);
            fadeDurationSec.remove(id);
        }
    }

    private boolean shouldApply(AbstractClientPlayer p) {
        Minecraft mc = Minecraft.getInstance();
        if (p == mc.player) {
            if (!selfToggle.getValue()) return false;
            // Self body only renders in third person; nothing to hide/burst in first person.
            return !mc.options.getCameraType().isFirstPerson();
        }
        if (!playerToggle.getValue()) return false;
        return isInRange(p);
    }

    public boolean isInRange(Entity entity) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) return false;
        double r = range.getValue();
        return mc.player.distanceToSqr(entity) <= r * r;
    }

    /** MixinAvatarRenderer: is this player's model currently hidden/fading from the effect? */
    public boolean isExploding(UUID id) {
        return getState() && exploding.contains(id);
    }

    /**
     * MixinLivingEntityRendererGhost: how opaque should this dying player's real body be
     * right now, 1 (just died) down to 0 (fully faded), over Duration. 1 if this UUID
     * isn't mid-death (caller only uses this while isExploding(id) is already true).
     */
    public float getFadeAlpha(UUID id) {
        Long t0 = deathNanos.get(id);
        if (t0 == null) return 1f;
        float dur = fadeDurationSec.getOrDefault(id, getDuration());
        float elapsed = (System.nanoTime() - t0) / 1.0e9f;
        float frac = dur <= 0f ? 1f : Math.min(elapsed / dur, 1f);
        return 1f - frac;
    }

    /** MixinModelFeatureRenderer: does this player still owe a one-shot cube capture? (Dust only) */
    public boolean needsCapture(UUID id) {
        return getState() && pendingCapture.contains(id);
    }

    /** Mixin calls this once it has captured + spawned, so the burst never doubles up. */
    public void markCaptured(UUID id) {
        pendingCapture.remove(id);
    }

    public int getParticleCount() { return (int) Math.round(particleCount.getValue()); }
    public float getExplodeSpeed() { return (float) (double) explodeSpeed.getValue(); }
    public float getDuration() { return (float) (double) duration.getValue(); }
}
