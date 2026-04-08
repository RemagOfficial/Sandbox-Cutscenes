package com.remag.scs.client.camera;

import net.minecraft.world.phys.Vec3;

/**
 * Minimal client API for mods that want to drive the SCS camera entity directly.
 */
public final class ScsCameraApi {
    private ScsCameraApi() {}

    public record ExternalCameraPose(Vec3 position, float yaw, float pitch, float roll, float fovScale) {}

    public static boolean acquireExternalCamera(String ownerId) {
        return SimpleCameraManager.acquireExternalCamera(ownerId);
    }

    public static boolean submitExternalPose(String ownerId, ExternalCameraPose pose) {
        if (pose == null || pose.position() == null) {
            return false;
        }
        return SimpleCameraManager.updateExternalCameraPose(
                ownerId,
                pose.position(),
                pose.yaw(),
                pose.pitch(),
                pose.roll(),
                pose.fovScale()
        );
    }

    public static boolean releaseExternalCamera(String ownerId) {
        return SimpleCameraManager.releaseExternalCamera(ownerId);
    }

    public static boolean isExternalCameraActive() {
        return SimpleCameraManager.isExternalCameraActive();
    }
}

