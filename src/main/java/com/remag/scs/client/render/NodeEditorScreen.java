package com.remag.scs.client.render;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import com.remag.scs.client.camera.SimpleCameraManager;
import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.gui.components.CycleButton;
import net.minecraft.world.phys.Vec3;

import java.util.List;

public class NodeEditorScreen extends Screen {
    private final CameraPathRenderer.NodeData node;
    private EditBox xEdit, yEdit, zEdit, yawEdit, pitchEdit, rollEdit, fovEdit, speedEdit, pauseEdit, lookAtEdit;

    public NodeEditorScreen(CameraPathRenderer.NodeData node) {
        super(Component.literal("Edit Node " + node.index));
        this.node = node;
    }

    @Override
    protected void init() {
        int centerX = width / 2;
        int centerY = height / 2;
        int fieldWidth = 100;
        int fieldHeight = 20;
        int spacing = 22; // Reduced spacing

        // Start from a centered Y and offset upwards to start the list
        int startY = centerY - 100;

        xEdit = addRenderableWidget(new EditBox(font, centerX - 50, startY, fieldWidth, fieldHeight, Component.literal("X")));
        xEdit.setValue(String.format("%.2f", node.pos.x));
        
        yEdit = addRenderableWidget(new EditBox(font, centerX - 50, startY + spacing, fieldWidth, fieldHeight, Component.literal("Y")));
        yEdit.setValue(String.format("%.2f", node.pos.y));

        zEdit = addRenderableWidget(new EditBox(font, centerX - 50, startY + spacing * 2, fieldWidth, fieldHeight, Component.literal("Z")));
        zEdit.setValue(String.format("%.2f", node.pos.z));

        yawEdit = addRenderableWidget(new EditBox(font, centerX - 50, startY + spacing * 3, fieldWidth, fieldHeight, Component.literal("Yaw")));
        yawEdit.setValue(String.format("%.1f", node.yaw));

        pitchEdit = addRenderableWidget(new EditBox(font, centerX - 50, startY + spacing * 4, fieldWidth, fieldHeight, Component.literal("Pitch")));
        pitchEdit.setValue(String.format("%.1f", node.pitch));

        rollEdit = addRenderableWidget(new EditBox(font, centerX - 50, startY + spacing * 5, fieldWidth, fieldHeight, Component.literal("Roll")));
        rollEdit.setValue(String.format("%.1f", node.roll));

        fovEdit = addRenderableWidget(new EditBox(font, centerX - 50, startY + spacing * 6, fieldWidth, fieldHeight, Component.literal("FOV")));
        fovEdit.setValue(String.format("%.2f", node.fov));

        // Copy Yaw Button
        addRenderableWidget(Button.builder(Component.literal("Copy Yaw"), b -> {
            if (minecraft.player != null) {
                yawEdit.setValue(String.format("%.1f", minecraft.player.getYRot()));
            }
        }).bounds(centerX + 60, startY + spacing * 3, 80, 20).build());

        // Copy Pitch Button
        addRenderableWidget(Button.builder(Component.literal("Copy Pitch"), b -> {
            if (minecraft.player != null) {
                pitchEdit.setValue(String.format("%.1f", minecraft.player.getXRot()));
            }
        }).bounds(centerX + 60, startY + spacing * 4, 80, 20).build());

        speedEdit = addRenderableWidget(new EditBox(font, centerX - 50, startY + spacing * 7, fieldWidth, fieldHeight, Component.literal("Speed")));
        speedEdit.setValue(String.format("%.2f", node.speed));

        pauseEdit = addRenderableWidget(new EditBox(font, centerX - 50, startY + spacing * 8, fieldWidth, fieldHeight, Component.literal("Pause")));
        pauseEdit.setValue(String.valueOf(node.pause));

        // Look At Target Field
        // Look At Target Field
        lookAtEdit = addRenderableWidget(new EditBox(font, centerX + 60, startY + spacing * 8, 110, 20, Component.literal("Look At X Y Z")));
        lookAtEdit.setSuggestion("Target X Y Z");
        
        // Fetch start node to calculate relative display string
        List<CameraPathRenderer.NodeData> currentPath = CameraPathRenderer.getPathNodes();
        Vec3 pathOrigin = currentPath.isEmpty() ? Vec3.ZERO : currentPath.get(0).pos;

        if (node.lookAt != null) {
            lookAtEdit.setValue(String.format("%.1f %.1f %.1f", 
                node.lookAt.x - pathOrigin.x, 
                node.lookAt.y - pathOrigin.y, 
                node.lookAt.z - pathOrigin.z));
        }

        // Set Look At Button
        addRenderableWidget(Button.builder(Component.literal("Set Look At"), b -> {
            String[] parts = lookAtEdit.getValue().split(" ");
            if (parts.length == 3) {
                try {
                    double rx = Double.parseDouble(parts[0]);
                    double ry = Double.parseDouble(parts[1]);
                    double rz = Double.parseDouble(parts[2]);
                    
                    // Convert relative input to world pos for rendering/internal use
                    Vec3 worldLookAt = new Vec3(rx + pathOrigin.x, ry + pathOrigin.y, rz + pathOrigin.z);
                    node.setLookAt(worldLookAt);
                    CameraPathRenderer.setDirty(true);

                    double dx = worldLookAt.x - Double.parseDouble(xEdit.getValue());
                    double dy = worldLookAt.y - Double.parseDouble(yEdit.getValue());
                    double dz = worldLookAt.z - Double.parseDouble(zEdit.getValue());
                    double dh = Math.sqrt(dx * dx + dz * dz);

                    float yaw = (float) Math.toDegrees(Math.atan2(-dx, dz));
                    float pitch = (float) -Math.toDegrees(Math.atan2(dy, dh));

                    yawEdit.setValue(String.format("%.1f", yaw));
                    pitchEdit.setValue(String.format("%.1f", pitch));
                } catch (Exception ignored) {}
            }
        }).bounds(centerX + 60, startY + spacing * 8, 110, 20).build());

        // Fixed Easing Cycle Button - placed next to the pause field or below
        addRenderableWidget(CycleButton.builder((SimpleCameraManager.EasingType value) -> 
                Component.literal(value.name()))
                .withValues(SimpleCameraManager.EasingType.values())
                .withInitialValue(node.easing)
                .create(centerX - 50, startY + spacing * 9, fieldWidth, fieldHeight, Component.literal("Easing"), (button, value) -> {
                    node.easing = value;
                }));

        // Movement Type Toggle
        addRenderableWidget(CycleButton.builder((String value) -> Component.literal(value.toUpperCase()))
                .withValues("linear", "curved")
                .withInitialValue(node.movement)
                .create(centerX + 60, startY + spacing * 7, 110, 20, Component.literal("Mode"), (button, value) -> {
                    node.movement = value;
                }));

        // Save Button at the bottom
        addRenderableWidget(Button.builder(Component.literal("Save"), b -> {
            try {
                node.pos = new net.minecraft.world.phys.Vec3(
                        Double.parseDouble(xEdit.getValue()),
                        Double.parseDouble(yEdit.getValue()),
                        Double.parseDouble(zEdit.getValue())
                );
                node.yaw = Float.parseFloat(yawEdit.getValue());
                node.pitch = Float.parseFloat(pitchEdit.getValue());
                node.roll = Float.parseFloat(rollEdit.getValue());
                node.fov = Float.parseFloat(fovEdit.getValue());
                node.speed = Double.parseDouble(speedEdit.getValue());
                node.pause = Long.parseLong(pauseEdit.getValue());
                CameraPathRenderer.setDirty(true);
                this.onClose();
            } catch (Exception ignored) {}
        }).bounds(centerX - 50, startY + spacing * 10 + 5, 100, 20).build());
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        graphics.fill(0, 0, this.width, this.height, 0x88000000);
        
        RenderSystem.setShader(GameRenderer::getPositionColorShader);
        graphics.drawCenteredString(font, this.title, width / 2, height / 2 - 115, 0xFFFFFF);
        
        int labelX = width / 2 - 70;
        int centerY = height / 2;
        int startY = centerY - 100;
        int spacing = 22;

        graphics.drawString(font, "X:", labelX, startY + 5, 0xAAAAAA);
        graphics.drawString(font, "Y:", labelX, startY + spacing + 5, 0xAAAAAA);
        graphics.drawString(font, "Z:", labelX, startY + spacing * 2 + 5, 0xAAAAAA);
        graphics.drawString(font, "Yaw:", labelX - 10, startY + spacing * 3 + 5, 0xAAAAAA);
        graphics.drawString(font, "Pitch:", labelX - 15, startY + spacing * 4 + 5, 0xAAAAAA);
        graphics.drawString(font, "Roll:", labelX - 15, startY + spacing * 5 + 5, 0xAAAAAA);
        graphics.drawString(font, "FOV:", labelX - 15, startY + spacing * 6 + 5, 0xAAAAAA);
        graphics.drawString(font, "Speed:", labelX - 20, startY + spacing * 7 + 5, 0xAAAAAA);
        graphics.drawString(font, "Pause:", labelX - 20, startY + spacing * 8 + 5, 0xAAAAAA);
        
        super.render(graphics, mouseX, mouseY, partialTick);
    }

    @Override
    public boolean isPauseScreen() {
        return false; // Prevents the world from pausing/blurring further
    }

    @Override
    public void renderBackground(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        // Do nothing here to prevent the default blur logic from firing
    }
}
