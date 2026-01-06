package com.remag.scs.client.camera;

import com.mojang.authlib.GameProfile;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.multiplayer.CommonListenerCookie;
import net.minecraft.client.player.KeyboardInput;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.protocol.Packet;
import net.minecraft.server.ServerLinks;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.HumanoidArm;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.flag.FeatureFlagSet;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.material.PushReaction;
import net.minecraft.world.phys.Vec3;

import java.util.Collections;
import java.util.UUID;

public class SimpleCameraEntity extends LocalPlayer {
    private static final Minecraft MC = Minecraft.getInstance();

    // Dummy connection that never sends packets.
    private static final ClientPacketListener DUMMY_NETWORK_HANDLER = new ClientPacketListener(
            MC,
            MC.getConnection().getConnection(),
            new CommonListenerCookie(
                    new GameProfile(UUID.randomUUID(), "CinematicCamera"),
                    MC.getTelemetryManager().createWorldSessionManager(false, null, null),
                    MC.player.registryAccess().freeze(),
                    FeatureFlagSet.of(),
                    null,
                    null,
                    null,
                    Collections.emptyMap(),
                    null,
                    true,
                    Collections.emptyMap(),
                    ServerLinks.EMPTY)) {
        @Override
        public void send(Packet<?> packet) {
            // No network traffic — this entity exists client-side only.
        }
    };

    public SimpleCameraEntity(int id) {
        super(MC, MC.level, DUMMY_NETWORK_HANDLER,
                MC.player.getStats(),
                MC.player.getRecipeBook(),
                false,
                false);

        setId(id);
        setPose(Pose.STANDING);
        // setClientLoaded(true);
        getAbilities().flying = true;
        input = new KeyboardInput(MC.options);
    }

    /* -------------------------------------------------------
     * Movement and control
     * ------------------------------------------------------- */

    public void setCameraRotation(float yaw, float pitch) {
        this.yRotO = getYRot();
        this.xRotO = getXRot();
        setYRot(yaw);
        setXRot(pitch);
        this.yHeadRot = yaw;
        this.yHeadRotO = yRotO;
    }

    public void setCameraPosition(double x, double y, double z) {
        this.xo = getX();
        this.yo = getY();
        this.zo = getZ();
        this.setPos(x, y, z);
    }

    public void apply(Vec3 pos, float yaw, float pitch) {
        setCameraPosition(pos.x, pos.y, pos.z);
        setCameraRotation(yaw, pitch);
    }

    /* -------------------------------------------------------
     * Client-only lifecycle
     * ------------------------------------------------------- */

    public void spawn() {
        ((ClientLevel) level()).addEntity(this);
    }

    public void despawn() {
        ((ClientLevel) level()).removeEntity(getId(), RemovalReason.DISCARDED);
    }

    /* -------------------------------------------------------
     * Disable unwanted behaviors
     * ------------------------------------------------------- */

    @Override
    public void aiStep() {
        // Disable player motion logic
    }

    @Override
    public boolean isInWater() {
        return false;
    }

    @Override
    public boolean onClimbable() {
        return false;
    }

    @Override
    public PushReaction getPistonPushReaction() {
        return PushReaction.IGNORE;
    }

    @Override
    public boolean canCollideWith(Entity other) {
        return false;
    }

    @Override
    public boolean isMovingSlowly() {
        return false;
    }

    @Override
    public void setPose(Pose pose) {
        // Force to standing to prevent rendering bugs
        super.setPose(Pose.STANDING);
    }

    @Override
    protected boolean updateIsUnderwater() {
        this.wasUnderwater = false;
        return false;
    }

    @Override
    protected void doWaterSplashEffect() {}

    @Override
    public HumanoidArm getMainArm() {
        return HumanoidArm.RIGHT;
    }

    @Override
    public ItemStack getMainHandItem() {
        return ItemStack.EMPTY; // ensures no item is rendered in hand
    }

    @Override
    public ItemStack getOffhandItem() {
        return ItemStack.EMPTY; // prevents offhand rendering
    }
}
