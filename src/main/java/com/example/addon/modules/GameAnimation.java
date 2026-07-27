package com.example.addon.modules;

import dev.boze.api.addon.AddonModule;

import java.util.HashMap;
import java.util.Map;

/**
 * Ported from TrollHack-dev GameAnimation.kt (hotbarSelectionX ease-out-cubic tracker).
 * Generic keyed smoother: track(key, target) eases toward target over real time.
 * When disabled, tracking is skipped and callers get the raw target back (vanilla snap).
 */
public class GameAnimation extends AddonModule {
    public static final GameAnimation INSTANCE = new GameAnimation();

    private static class State {
        float current = Float.NaN;
        long lastNanos = 0L;
    }

    private final Map<Object, State> states = new HashMap<>();

    private GameAnimation() {
        super("GameAnimation", "Ease-out-cubic smoothing for UI value transitions (e.g. Whitelist hover highlight).");
    }

    @Override
    public void onDisable() {
        states.clear();
    }

    public float track(Object key, float target) {
        if (!getState()) return target;
        State s = states.computeIfAbsent(key, k -> new State());
        long now = System.nanoTime();
        if (!Float.isFinite(s.current) || Math.abs(s.current - target) > 240f) s.current = target;
        float delta = s.lastNanos == 0L ? 0f : clamp01((now - s.lastNanos) / 150_000_000.0f);
        s.lastNanos = now;
        float eased = 1f - (1f - delta) * (1f - delta) * (1f - delta);
        s.current += (target - s.current) * eased;
        return s.current;
    }

    public void clear(Object key) {
        states.remove(key);
    }

    private static float clamp01(double v) {
        return (float) Math.max(0.0, Math.min(1.0, v));
    }
}
