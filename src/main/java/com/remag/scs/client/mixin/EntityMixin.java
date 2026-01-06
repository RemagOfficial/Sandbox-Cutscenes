package com.remag.scs.client.mixin;

import com.remag.scs.client.camera.SimpleCameraManager;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Entity.class)
public class EntityMixin {
    @Inject(method = "push(Lnet/minecraft/world/entity/Entity;)V", at = @At("HEAD"), cancellable = true)
    private void onPushAwayFrom(Entity other, CallbackInfo ci) {
        if (SimpleCameraManager.isActive() && (other.equals(SimpleCameraManager.getCamera()) || this.equals(SimpleCameraManager.getCamera()))) {
            ci.cancel();
        }
    }
}
