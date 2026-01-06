package com.remag.scs.client.mixin;

import com.remag.scs.client.camera.SimpleCameraEntity;
import com.remag.scs.client.camera.SimpleCameraManager;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GameRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(GameRenderer.class)
public class GameRendererMixin {

    @Inject(method = "getFov", at = @At("RETURN"), cancellable = true)
    private void adjustFovForCutscene(net.minecraft.client.Camera camera, float partialTick, boolean useFOVSetting, CallbackInfoReturnable<Double> cir) {
        if (SimpleCameraManager.isActive()) {
            double baseFov = cir.getReturnValue();
            float zoomScale = SimpleCameraManager.getFov(); // 0.0 to 1.0
            
            // 1.0 = Player's FOV, 0.0 = 5.0 (Minimum FOV for high zoom)
            double targetFov = 5.0 + (baseFov - 5.0) * zoomScale;
            
            cir.setReturnValue(targetFov);
        }
    }

    @Inject(method = "shouldRenderBlockOutline", at = @At("HEAD"), cancellable = true)
    private void disableBlockOutlineWhileCameraActive(CallbackInfoReturnable<Boolean> cir) {
        // If your camera system has a static "isActive()" or similar flag
        if (SimpleCameraManager.isActive() &&
                Minecraft.getInstance().getCameraEntity() instanceof SimpleCameraEntity) {
            cir.setReturnValue(false);
            cir.cancel();
        }
    }
}
