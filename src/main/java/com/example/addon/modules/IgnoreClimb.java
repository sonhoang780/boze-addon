package com.example.addon.modules;

import dev.boze.api.addon.AddonModule;

/**
 * Forces LivingEntity#onClimbable() to false for the local player (MixinLivingEntity) --
 * the single real vanilla gate every climbable block (vines, ladders, scaffolding, all
 * BlockTags.CLIMBABLE) is checked against before applying climb velocity/gravity override
 * (verified via javap on LivingEntity, 26.1.2: public boolean onClimbable() feeds
 * handleOnClimbable(Vec3)). With it forced false, standing against any of them is treated
 * like normal terrain -- no climb-assist velocity, no auto-stick, walk/collide normally.
 */
public class IgnoreClimb extends AddonModule {
    public static final IgnoreClimb INSTANCE = new IgnoreClimb();

    public IgnoreClimb() {
        super("IgnoreClimb", "Ignores vines/ladders/scaffolding -- walk past them like normal blocks instead of climbing.");
    }
}
