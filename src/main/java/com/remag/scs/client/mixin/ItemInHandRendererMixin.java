package com.remag.scs.client.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
import com.remag.scs.client.camera.SimpleCameraEntity;
import com.remag.scs.client.camera.SimpleCameraManager;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.ItemInHandRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ItemInHandRenderer.class)
public abstract class ItemInHandRendererMixin {

    @Inject(
            method = "renderItem",
            at = @At("HEAD"),
            cancellable = true
    )
    private void onRenderItem(LivingEntity entity,
                              ItemStack itemStack,
                              ItemDisplayContext displayContext,
                              boolean leftHand,
                              PoseStack poseStack,
                              MultiBufferSource buffer,
                              int seed,
                              CallbackInfo ci) {

        // Cancel the item rendering if the camera manager is active
        if (SimpleCameraManager.isActive() &&
                Minecraft.getInstance().getCameraEntity() instanceof SimpleCameraEntity) {
            ci.cancel();
        }
    }
}
