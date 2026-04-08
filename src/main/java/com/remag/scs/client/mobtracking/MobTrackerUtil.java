package com.remag.scs.client.mobtracking;

import com.remag.scs.client.camera.ScsCameraApi;
import net.minecraft.client.CameraType;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Random;

/**
 * Demo utility for tracking a random mob with the external camera.
 * Shows how to use the ScsCameraApi for custom camera control.
 */
public class MobTrackerUtil {
    private static final Minecraft MC = Minecraft.getInstance();
    private static final Random RANDOM = new Random();
    private static final double TRACKING_RADIUS = 50.0;
    private static final String OWNER_ID = "scs:demo_potato_tracker";

    private static boolean tracking = false;
    @Nullable
    private static CameraType previousCameraType = null;
    @Nullable
    private static LivingEntity trackedMob = null;

    public static boolean isTracking() {
        return tracking;
    }

    public static boolean shouldHideTrackedEntity(Entity entity) {
        return tracking && trackedMob != null && entity != null && entity.getId() == trackedMob.getId();
    }

    public static void toggleTracking() {
        if (tracking) {
            stopTracking();
        } else {
            startTracking();
        }
    }

    private static void startTracking() {
        if (MC.level == null || MC.player == null) {
            return;
        }

        List<LivingEntity> nearbyMobs = MC.level.getEntitiesOfClass(
                LivingEntity.class,
                MC.player.getBoundingBox().inflate(TRACKING_RADIUS),
                e -> e instanceof Mob && e != MC.player
        );

        if (nearbyMobs.isEmpty()) {
            return;
        }

        trackedMob = nearbyMobs.get(RANDOM.nextInt(nearbyMobs.size()));
        tracking = true;

        // Acquire external camera control
        if (!ScsCameraApi.acquireExternalCamera(OWNER_ID)) {
            tracking = false;
            trackedMob = null;
            previousCameraType = null;
            return;
        }

        // First-person hides the local player model. Switch to detached mode while tracking.
        previousCameraType = MC.options.getCameraType();
        if (previousCameraType == CameraType.FIRST_PERSON) {
            MC.options.setCameraType(CameraType.THIRD_PERSON_BACK);
        }
    }

    private static void stopTracking() {
        if (tracking) {
            ScsCameraApi.releaseExternalCamera(OWNER_ID);
            if (previousCameraType != null) {
                MC.options.setCameraType(previousCameraType);
                previousCameraType = null;
            }
            tracking = false;
            trackedMob = null;
        }
    }

    public static void tick() {
        if (!tracking || MC.player == null || MC.level == null) {
            if (tracking) {
                stopTracking();
            }
            return;
        }

        // Check if tracked mob is still alive and in range
        if (trackedMob == null || trackedMob.isRemoved() || !trackedMob.isAlive()) {
            stopTracking();
            return;
        }

        double distSq = MC.player.distanceToSqr(trackedMob);
        if (distSq > TRACKING_RADIUS * TRACKING_RADIUS) {
            stopTracking();
        }
    }

    public static void renderTick(float partialTick) {
        if (!tracking || trackedMob == null) {
            return;
        }

        float pt = Math.max(0.0f, Math.min(1.0f, partialTick));

        // Use the mob's actual camera/view pose (interpolated) so we match what the mob is looking at.
        Vec3 mobEyePos = trackedMob.getEyePosition(pt);
        float mobYaw = trackedMob.getViewYRot(pt);
        float mobPitch = trackedMob.getViewXRot(pt);
        Vec3 forward = trackedMob.getViewVector(pt).normalize();

        // Small nudge forward along look vector to keep camera out of model geometry.
        double eyeNudge = Math.max(0.08, trackedMob.getBbWidth() * 0.12);
        Vec3 cameraPos = mobEyePos.add(forward.scale(eyeNudge));

        ScsCameraApi.submitExternalPose(
            OWNER_ID,
            new ScsCameraApi.ExternalCameraPose(
                cameraPos,
                mobYaw,
                mobPitch,
                0.0f,
                1.0f
            )
        );
    }
}


