package com.example.addon.modules;

import dev.boze.api.addon.AddonModule;
import dev.boze.api.option.ToggleOption;

/**
 * NoPush toggles only -- real physics overrides via MixinEntity/MixinLocalPlayer, no packet
 * spoofing, no counter-input trickery. The counter-strafe knockback-reduction mechanism this
 * module used to carry (Strength/RespectInput/SafetyHits) was removed -- not effective enough
 * in practice to keep.
 */
public class Velocity extends AddonModule {
    public static final Velocity INSTANCE = new Velocity();

    public final ToggleOption noPushEntities = new ToggleOption(this, "NoPush-Entities",
            "Cancel the vanilla entity-collision bump (real physics, via MixinEntity#push).", true);
    public final ToggleOption noPushFluids = new ToggleOption(this, "NoPush-Liquids",
            "Ignore flowing-liquid drag (real physics, via MixinEntity#isPushedByFluid).", true);
    public final ToggleOption noPushBlocks = new ToggleOption(this, "NoPush-Blocks",
            "Cancel the nudge LocalPlayer applies when squeezed inside a solid block (real "
            + "Mojmap name moveTowardsClosestSpace, Yarn's pushOutOfBlocks -- verified present "
            + "on 26.1.2 via javap/strings, not a guess).", true);

    public Velocity() {
        super("Velocity", "NoPush toggles -- cancels entity/fluid/block push via real physics overrides.");
    }
}
