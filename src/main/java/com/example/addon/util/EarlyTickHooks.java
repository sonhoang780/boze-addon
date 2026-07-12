package com.example.addon.util;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Registry backing MixinLocalPlayer's tick-HEAD hook. Lives outside com.example.addon.mixin
 * on purpose -- that whole package is reserved by example-addon.mixins.json ("package":
 * "com.example.addon.mixin"), so Sponge Mixin throws IllegalClassLoadError ("is in a
 * defined mixin package ... and cannot be referenced directly") for any plain class placed
 * there and called from outside the mixin transform itself.
 */
public final class EarlyTickHooks {
    private EarlyTickHooks() {}

    private static final List<Runnable> callbacks = new CopyOnWriteArrayList<>();

    public static void register(Runnable callback) {
        callbacks.add(callback);
    }

    public static void unregister(Runnable callback) {
        callbacks.remove(callback);
    }

    public static void dispatch() {
        for (Runnable callback : callbacks) {
            callback.run();
        }
    }
}
