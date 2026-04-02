package com.remag.scs.client.render;

import net.minecraft.client.Minecraft;
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
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;

public class NodeEditorScreen extends Screen {
    private static final int PANEL_WIDTH = 320;
    private static final int FIELD_WIDTH = 220;
    private static final int HALF_WIDTH = 106;
    private static final int ROW_SPACING = 24;
    private static final int PANEL_PADDING = 12;

    private final CameraPathRenderer.NodeData node;
    private EditBox xEdit, yEdit, zEdit, yawEdit, pitchEdit, rollEdit, fovEdit, speedEdit, pauseEdit, lookAtEdit;
    private final List<LabelLine> labels = new ArrayList<>();

    private int panelLeft;
    private int panelTop;
    private int panelRight;
    private int panelBottom;

    private record LabelLine(String text, int x, int y) {}

    public NodeEditorScreen(CameraPathRenderer.NodeData node) {
        super(Component.literal("Edit Node " + node.index));
        this.node = node;
    }

    @Override
    protected void init() {
        labels.clear();

        int centerX = width / 2;

        int estimatedPanelHeight = estimatePanelHeight();
        panelTop = Math.max(8, (height - estimatedPanelHeight) / 2);
        panelLeft = centerX - PANEL_WIDTH / 2;
        panelRight = panelLeft + PANEL_WIDTH;

        int labelX = panelLeft + PANEL_PADDING;
        int fieldX = panelRight - PANEL_PADDING - FIELD_WIDTH;
        int y = panelTop + PANEL_PADDING + font.lineHeight + 12;

        xEdit = addRenderableWidget(new EditBox(font, fieldX, y, FIELD_WIDTH, 20, Component.literal("X")));
        xEdit.setValue(String.format("%.2f", node.pos.x));
        labels.add(new LabelLine("X", labelX, y + 6));

        y += ROW_SPACING;
        yEdit = addRenderableWidget(new EditBox(font, fieldX, y, FIELD_WIDTH, 20, Component.literal("Y")));
        yEdit.setValue(String.format("%.2f", node.pos.y));
        labels.add(new LabelLine("Y", labelX, y + 6));

        y += ROW_SPACING;
        zEdit = addRenderableWidget(new EditBox(font, fieldX, y, FIELD_WIDTH, 20, Component.literal("Z")));
        zEdit.setValue(String.format("%.2f", node.pos.z));
        labels.add(new LabelLine("Z", labelX, y + 6));

        y += ROW_SPACING;
        yawEdit = addRenderableWidget(new EditBox(font, fieldX, y, FIELD_WIDTH, 20, Component.literal("Yaw")));
        yawEdit.setValue(String.format("%.1f", node.yaw));
        labels.add(new LabelLine("Yaw", labelX, y + 6));

        y += ROW_SPACING;
        pitchEdit = addRenderableWidget(new EditBox(font, fieldX, y, FIELD_WIDTH, 20, Component.literal("Pitch")));
        pitchEdit.setValue(String.format("%.1f", node.pitch));
        labels.add(new LabelLine("Pitch", labelX, y + 6));

        y += ROW_SPACING;
        rollEdit = addRenderableWidget(new EditBox(font, fieldX, y, FIELD_WIDTH, 20, Component.literal("Roll")));
        rollEdit.setValue(String.format("%.1f", node.roll));
        labels.add(new LabelLine("Roll", labelX, y + 6));

        y += ROW_SPACING;
        fovEdit = addRenderableWidget(new EditBox(font, fieldX, y, FIELD_WIDTH, 20, Component.literal("FOV")));
        fovEdit.setValue(String.format("%.2f", node.fov));
        labels.add(new LabelLine("FOV", labelX, y + 6));

        y += ROW_SPACING;
        addRenderableWidget(Button.builder(Component.literal("Copy Yaw"), b -> {
            if (minecraft != null && minecraft.player != null) {
                yawEdit.setValue(String.format("%.1f", minecraft.player.getYRot()));
            }
        }).bounds(fieldX, y, HALF_WIDTH, 20).build());

        addRenderableWidget(Button.builder(Component.literal("Copy Pitch"), b -> {
            if (minecraft != null && minecraft.player != null) {
                pitchEdit.setValue(String.format("%.1f", minecraft.player.getXRot()));
            }
        }).bounds(fieldX + HALF_WIDTH + 8, y, HALF_WIDTH, 20).build());

        y += ROW_SPACING;
        speedEdit = addRenderableWidget(new EditBox(font, fieldX, y, FIELD_WIDTH, 20, Component.literal("Speed")));
        speedEdit.setValue(node.speed != null ? String.format("%.2f", node.speed) : "0.00");
        labels.add(new LabelLine("Speed", labelX, y + 6));

        y += ROW_SPACING;
        pauseEdit = addRenderableWidget(new EditBox(font, fieldX, y, FIELD_WIDTH, 20, Component.literal("Pause")));
        pauseEdit.setValue(String.valueOf(node.pause));
        labels.add(new LabelLine("Pause", labelX, y + 6));

        y += ROW_SPACING;
        lookAtEdit = addRenderableWidget(new EditBox(font, fieldX, y, FIELD_WIDTH, 20, Component.literal("Look At X Y Z")));
        configureLookAtPlaceholder();
        labels.add(new LabelLine("Look At", labelX, y + 6));

        List<CameraPathRenderer.NodeData> currentPath = CameraPathRenderer.getPathNodes();
        Vec3 pathOrigin = currentPath.isEmpty() ? Vec3.ZERO : currentPath.getFirst().pos;

        if (node.lookAt != null) {
            lookAtEdit.setValue(String.format("%.1f %.1f %.1f", 
                node.lookAt.x - pathOrigin.x, 
                node.lookAt.y - pathOrigin.y, 
                node.lookAt.z - pathOrigin.z));
        }

        y += ROW_SPACING;
        addRenderableWidget(Button.builder(Component.literal("Set Look At"), b -> {
            String[] parts = lookAtEdit.getValue().split(" ");
            if (parts.length == 3) {
                try {
                    double rx = Double.parseDouble(parts[0]);
                    double ry = Double.parseDouble(parts[1]);
                    double rz = Double.parseDouble(parts[2]);

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
        }).bounds(fieldX, y, FIELD_WIDTH, 20).build());

        y += ROW_SPACING;
        addRenderableWidget(CycleButton.builder((SimpleCameraManager.EasingType value) ->
                Component.literal(value.name()))
                .withValues(SimpleCameraManager.EasingType.values())
                .withInitialValue(node.easing)
                .create(fieldX, y, FIELD_WIDTH, 20, Component.literal("Easing"), (button, value) -> node.easing = value));
        labels.add(new LabelLine("Easing", labelX, y + 6));

        y += ROW_SPACING;
        addRenderableWidget(CycleButton.builder((String value) -> Component.literal(value.toUpperCase()))
                .withValues("linear", "curved")
                .withInitialValue(node.movement)
                .create(fieldX, y, FIELD_WIDTH, 20, Component.literal("Mode"), (button, value) -> node.movement = value));
        labels.add(new LabelLine("Mode", labelX, y + 6));

        y += ROW_SPACING + 4;
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
        }).bounds(fieldX, y, HALF_WIDTH, 20).build());

        addRenderableWidget(Button.builder(Component.literal("Edit Events"), b -> {
            String initialEvent = (node.events != null && !node.events.isEmpty()) ? node.events.getFirst() : null;
            openScreen(new EventEditorScreen(node, initialEvent));
        }).bounds(fieldX + HALF_WIDTH + 8, y, HALF_WIDTH, 20).build());

        y += ROW_SPACING;
        addRenderableWidget(Button.builder(Component.literal("Timed Events"), b -> {
            List<String> timedNames = SimpleCameraManager.getTimedEventNames();
            String initialTimed = timedNames.isEmpty() ? null : timedNames.getFirst();
            openScreen(new EventEditorScreen(node, initialTimed, true));
        }).bounds(fieldX, y, FIELD_WIDTH, 20).build());

        y += ROW_SPACING;
        addRenderableWidget(Button.builder(Component.literal("Done"), b -> onClose())
                .bounds(centerX - 48, y, 96, 20).build());

        panelBottom = y + 20 + PANEL_PADDING;
    }

    @Override
    public void render(@NotNull GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        graphics.fill(0, 0, this.width, this.height, 0x88000000);
        RenderSystem.setShader(GameRenderer::getPositionColorShader);
        graphics.fill(panelLeft, panelTop, panelRight, panelBottom, 0x66000000);
        graphics.drawCenteredString(font, this.title, width / 2, panelTop + PANEL_PADDING, 0xFFFFFF);

        for (LabelLine label : labels) {
            graphics.drawString(font, label.text(), label.x(), label.y(), 0xAAAAAA);
        }

        super.render(graphics, mouseX, mouseY, partialTick);
    }

    private int estimatePanelHeight() {
        int rows = 16;
        int contentHeight = rows * 20 + (rows - 1) * (ROW_SPACING - 20);
        return PANEL_PADDING + font.lineHeight + 12 + contentHeight + PANEL_PADDING;
    }

    private void configureLookAtPlaceholder() {
        updateLookAtPlaceholder();
        lookAtEdit.setResponder(value -> updateLookAtPlaceholder());
    }

    private void updateLookAtPlaceholder() {
        lookAtEdit.setSuggestion(lookAtEdit.getValue().isEmpty() ? "Target X Y Z" : null);
    }

    private void openScreen(Screen screen) {
        Minecraft mc = minecraft;
        if (mc != null) {
            mc.setScreen(screen);
        }
    }

    @Override
    public boolean isPauseScreen() {
        return false; // Prevents the world from pausing/blurring further
    }

    @Override
    public void renderBackground(@NotNull GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        // Do nothing here to prevent the default blur logic from firing
    }
}
