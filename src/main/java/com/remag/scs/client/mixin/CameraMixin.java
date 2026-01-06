package com.remag.scs.client.mixin;

import com.remag.scs.client.camera.SimpleCameraManager;
import net.minecraft.client.Camera;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.joml.Quaternionf;

@Mixin(Camera.class)
public abstract class CameraMixin {
    @Shadow protected abstract void setRotation(float yaw, float pitch);
    
    @Inject(method = "setup", at = @At("RETURN"))
    private void applyRoll(net.minecraft.world.level.BlockGetter level, net.minecraft.world.entity.Entity entity, boolean detached, boolean mirrored, float partialTick, CallbackInfo ci) {
        if (SimpleCameraManager.isActive()) {
            float roll = SimpleCameraManager.getRoll();
            if (roll != 0) {
                Camera self = (Camera)(Object)this;
                Quaternionf q = self.rotation();
                q.rotateZ((float) Math.toRadians(roll));
            }
        }
    }
}
