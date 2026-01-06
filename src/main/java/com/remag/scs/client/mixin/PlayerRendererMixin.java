package com.remag.scs.client.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
import com.remag.scs.client.camera.SimpleCameraEntity;
import com.remag.scs.client.camera.SimpleCameraManager;
import net.minecraft.client.Minecraft;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.player.PlayerRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(PlayerRenderer.class)
public class PlayerRendererMixin {
    @Inject(method = "renderHand", at = @At("HEAD"), cancellable = true)
    private void onRenderHand(PoseStack poseStack, MultiBufferSource buffer, int combinedLight,
                              AbstractClientPlayer player, ModelPart rendererArm, ModelPart rendererArmwear,
                              CallbackInfo ci) {
        if (SimpleCameraManager.isActive() &&
                Minecraft.getInstance().getCameraEntity() instanceof SimpleCameraEntity) {
            ci.cancel(); // skip rendering the arm
        }
    }
}
