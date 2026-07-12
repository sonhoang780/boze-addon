package com.example.addon.util;

import java.util.Optional;

/**
 * Shared Baritone reflection helpers (no compile-time Baritone dependency exists in this
 * addon). Used by EBouncePlus's obstacle-passer, PathFinder, and AutoWalk -- anything that
 * needs to know "is Baritone currently driving movement" so it can get out of the way.
 */
public final class BaritoneUtils {
    private BaritoneUtils() {}

    public static Object getPrimaryBaritone() {
        try {
            Class<?> apiClass = Class.forName("baritone.api.BaritoneAPI");
            Object provider = apiClass.getMethod("getProvider").invoke(null);
            return provider.getClass().getMethod("getPrimaryBaritone").invoke(provider);
        } catch (Throwable t) {
            return null;
        }
    }

    public static boolean isAvailable() {
        return getPrimaryBaritone() != null;
    }

    /**
     * lambda's BaritoneHandler.isActive: `customGoalProcess?.isActive || pathingBehavior
     * ?.isPathing || pathingControlManager?.mostRecentInControl()?.isActive ||
     * elytraProcess?.isActive`. Checking only elytraProcess (an earlier version of this
     * check, EBouncePlus-local) reads false whenever Baritone is walking a GoalGetToBlock
     * goal instead of auto-flying -- exactly how EBounce+'s obstacle-passer and PathFinder
     * both drive it -- which caused a live bug: the goal got reissued from scratch every
     * single tick instead of Baritone ever being allowed to actually execute one.
     */
    public static boolean isActive() {
        Object baritone = getPrimaryBaritone();
        if (baritone == null) return false;
        try {
            Object pathingBehavior = baritone.getClass().getMethod("getPathingBehavior").invoke(baritone);
            if ((boolean) pathingBehavior.getClass().getMethod("isPathing").invoke(pathingBehavior)) return true;
        } catch (Throwable ignored) {}
        try {
            Object customGoalProcess = baritone.getClass().getMethod("getCustomGoalProcess").invoke(baritone);
            if ((boolean) customGoalProcess.getClass().getMethod("isActive").invoke(customGoalProcess)) return true;
        } catch (Throwable ignored) {}
        try {
            Object elytraProcess = baritone.getClass().getMethod("getElytraProcess").invoke(baritone);
            if (elytraProcess != null
                    && (boolean) elytraProcess.getClass().getMethod("isActive").invoke(elytraProcess)) return true;
        } catch (Throwable ignored) {}
        try {
            Object pcm = baritone.getClass().getMethod("getPathingControlManager").invoke(baritone);
            Optional<?> mostRecent = (Optional<?>) pcm.getClass().getMethod("mostRecentInControl").invoke(pcm);
            if (mostRecent.isPresent()) {
                Object proc = mostRecent.get();
                if ((boolean) proc.getClass().getMethod("isActive").invoke(proc)) return true;
            }
        } catch (Throwable ignored) {}
        return false;
    }
}
