package com.remag.scs.client;

import java.lang.reflect.Method;

/**
 * Optional bridge for demo mob tracking code that may be excluded from release jars.
 */
public final class OptionalMobTrackingBridge {
    private static final String IMPL_CLASS = "com.remag.scs.client.mobtracking.MobTrackerUtil";

    private static boolean resolved;
    private static boolean available;
    private static Method toggleTracking;
    private static Method tick;
    private static Method renderTick;

    private OptionalMobTrackingBridge() {}

    private static void resolve() {
        if (resolved) {
            return;
        }
        resolved = true;
        try {
            Class<?> clazz = Class.forName(IMPL_CLASS);
            toggleTracking = clazz.getMethod("toggleTracking");
            tick = clazz.getMethod("tick");
            renderTick = clazz.getMethod("renderTick", float.class);
            available = true;
        } catch (Throwable ignored) {
            available = false;
        }
    }

    public static void toggleTracking() {
        resolve();
        if (!available) {
            return;
        }
        try {
            toggleTracking.invoke(null);
        } catch (Throwable ignored) {
        }
    }

    public static void tick() {
        resolve();
        if (!available) {
            return;
        }
        try {
            tick.invoke(null);
        } catch (Throwable ignored) {
        }
    }

    public static void renderTick(float partialTick) {
        resolve();
        if (!available) {
            return;
        }
        try {
            renderTick.invoke(null, partialTick);
        } catch (Throwable ignored) {
        }
    }
}
