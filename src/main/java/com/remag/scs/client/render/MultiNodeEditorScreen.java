package com.remag.scs.client.render;

import com.mojang.blaze3d.systems.RenderSystem;
import com.remag.scs.client.camera.SimpleCameraManager;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.CycleButton;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.network.chat.Component;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

public class MultiNodeEditorScreen extends Screen {
    private static final int PANEL_WIDTH = 340;
    private static final int FIELD_WIDTH = 220;
    private static final int HALF_WIDTH = 106;
    private static final int ROW_SPACING = 24;
    private static final int PANEL_PADDING = 12;

    private final List<CameraPathRenderer.NodeData> nodes;

    private EditBox yawEdit;
    private EditBox pitchEdit;
    private EditBox rollEdit;
    private EditBox fovEdit;
    private EditBox pauseEdit;
    private EditBox speedOrDurationEdit;

    private String originalYawValue;
    private String originalPitchValue;
    private String originalRollValue;
    private String originalFovValue;
    private String originalPauseValue;
    private String originalSpeedValue;

    private boolean yawShared;
    private boolean pitchShared;
    private boolean rollShared;
    private boolean fovShared;
    private boolean pauseShared;
    private boolean speedShared;
    private boolean durationShared;
    private boolean allManual;
    private boolean allRecorded;

    private SimpleCameraManager.EasingType sharedEasing;
    private boolean easingShared;
    private String sharedMovement;
    private boolean movementShared;

    private int panelLeft;
    private int panelTop;
    private int panelRight;
    private int panelBottom;

    private final List<LabelLine> labels = new ArrayList<>();

    private record LabelLine(String text, int x, int y, int color) {}

    public MultiNodeEditorScreen(List<CameraPathRenderer.NodeData> selectedNodes) {
        super(Component.literal("Edit Selected Nodes"));
        this.nodes = selectedNodes != null ? new ArrayList<>(selectedNodes) : new ArrayList<>();
    }

    @Override
    protected void init() {
        labels.clear();

        if (nodes.size() < 2) {
            onClose();
            return;
        }

        computeSharedState();

        int centerX = width / 2;
        panelTop = Math.max(8, (height - 420) / 2);
        panelLeft = centerX - PANEL_WIDTH / 2;
        panelRight = panelLeft + PANEL_WIDTH;

        int labelX = panelLeft + PANEL_PADDING;
        int fieldX = panelRight - PANEL_PADDING - FIELD_WIDTH;
        int y = panelTop + PANEL_PADDING + font.lineHeight + 12;

        originalYawValue = yawShared ? fmt1(nodes.getFirst().yaw) : "Mixed";
        yawEdit = addNumericField(fieldX, y, "Yaw", originalYawValue);
        yawEdit.setEditable(yawShared);
        labels.add(new LabelLine("Yaw", labelX, y + 6, 0xAAAAAA));

        y += ROW_SPACING;
        originalPitchValue = pitchShared ? fmt1(nodes.getFirst().pitch) : "Mixed";
        pitchEdit = addNumericField(fieldX, y, "Pitch", originalPitchValue);
        pitchEdit.setEditable(pitchShared);
        labels.add(new LabelLine("Pitch", labelX, y + 6, 0xAAAAAA));

        y += ROW_SPACING;
        originalRollValue = rollShared ? fmt1(nodes.getFirst().roll) : "Mixed";
        rollEdit = addNumericField(fieldX, y, "Roll", originalRollValue);
        rollEdit.setEditable(rollShared);
        labels.add(new LabelLine("Roll", labelX, y + 6, 0xAAAAAA));

        y += ROW_SPACING;
        originalFovValue = fovShared ? fmt2(nodes.getFirst().fov) : "Mixed";
        fovEdit = addNumericField(fieldX, y, "FOV", originalFovValue);
        fovEdit.setEditable(fovShared);
        labels.add(new LabelLine("FOV", labelX, y + 6, 0xAAAAAA));

        y += ROW_SPACING;
        originalPauseValue = pauseShared ? String.valueOf(nodes.getFirst().pause) : "Mixed";
        pauseEdit = addNumericField(fieldX, y, "Pause", originalPauseValue);
        pauseEdit.setEditable(pauseShared);
        labels.add(new LabelLine("Pause", labelX, y + 6, 0xAAAAAA));

        y += ROW_SPACING;
        String speedLabel = allRecorded ? "Duration" : "Speed";
        originalSpeedValue = "Mixed";
        boolean speedEnabled = false;
        if (allManual) {
            speedEnabled = speedShared;
            originalSpeedValue = speedShared ? fmt2(nodes.getFirst().getSpeed() != null ? nodes.getFirst().getSpeed() : 0.0) : "Mixed";
        } else if (allRecorded) {
            speedEnabled = durationShared;
            originalSpeedValue = durationShared ? String.valueOf(nodes.getFirst().getDuration() != null ? nodes.getFirst().getDuration() : 0L) : "Mixed";
        }
        speedOrDurationEdit = addNumericField(fieldX, y, speedLabel, originalSpeedValue);
        speedOrDurationEdit.setEditable(speedEnabled);
        labels.add(new LabelLine(speedLabel, labelX, y + 6, 0xAAAAAA));

        y += ROW_SPACING;
        if (easingShared) {
            addRenderableWidget(CycleButton.builder((SimpleCameraManager.EasingType value) -> Component.literal(value.name()))
                    .withValues(SimpleCameraManager.EasingType.values())
                    .withInitialValue(sharedEasing)
                    .create(fieldX, y, FIELD_WIDTH, 20, Component.literal("Easing"), (button, value) -> sharedEasing = value));
            labels.add(new LabelLine("Easing", labelX, y + 6, 0xAAAAAA));
        } else {
            labels.add(new LabelLine("Easing", labelX, y + 6, 0xAAAAAA));
            labels.add(new LabelLine("Mixed (not editable)", fieldX + 4, y + 6, 0x888888));
        }

        y += ROW_SPACING;
        if (movementShared) {
            addRenderableWidget(CycleButton.builder((String value) -> Component.literal(value.toUpperCase()))
                    .withValues("linear", "curved")
                    .withInitialValue(sharedMovement)
                    .create(fieldX, y, FIELD_WIDTH, 20, Component.literal("Mode"), (button, value) -> sharedMovement = value));
            labels.add(new LabelLine("Mode", labelX, y + 6, 0xAAAAAA));
        } else {
            labels.add(new LabelLine("Mode", labelX, y + 6, 0xAAAAAA));
            labels.add(new LabelLine("Mixed (not editable)", fieldX + 4, y + 6, 0x888888));
        }

        y += ROW_SPACING + 4;
        addRenderableWidget(Button.builder(Component.literal("Apply"), b -> applyChanges())
                .bounds(fieldX, y, HALF_WIDTH, 20)
                .build());
        addRenderableWidget(Button.builder(Component.literal("Done"), b -> onClose())
                .bounds(fieldX + HALF_WIDTH + 8, y, HALF_WIDTH, 20)
                .build());

        y += ROW_SPACING;
        labels.add(new LabelLine("Only shared values are editable in multiselect.", panelLeft + PANEL_PADDING, y, 0xB0B0B0));

        panelBottom = y + PANEL_PADDING + font.lineHeight;
    }

    private void computeSharedState() {
        CameraPathRenderer.NodeData first = nodes.getFirst();

        yawShared = allEqualFloat(node -> node.yaw);
        pitchShared = allEqualFloat(node -> node.pitch);
        rollShared = allEqualFloat(node -> node.roll);
        fovShared = allEqualFloat(node -> node.fov);
        pauseShared = allEqualLong(node -> node.pause);

        allManual = nodes.stream().allMatch(node -> node.getDuration() == null);
        allRecorded = nodes.stream().allMatch(node -> node.getDuration() != null);

        speedShared = allManual && allEqualObject(node -> node.getSpeed());
        durationShared = allRecorded && allEqualObject(node -> node.getDuration());

        easingShared = allEqualObject(node -> node.easing);
        sharedEasing = easingShared ? first.easing : SimpleCameraManager.EasingType.LINEAR;

        movementShared = allEqualObject(node -> node.movement);
        sharedMovement = movementShared ? first.movement : "linear";
    }

    private void applyChanges() {
         // Only parse values if the text has actually changed from the original
         Float parsedYaw = (yawShared && !yawEdit.getValue().trim().equals(originalYawValue)) ? parseFloatField(yawEdit, "Yaw") : null;
         Float parsedPitch = (pitchShared && !pitchEdit.getValue().trim().equals(originalPitchValue)) ? parseFloatField(pitchEdit, "Pitch") : null;
         Float parsedRoll = (rollShared && !rollEdit.getValue().trim().equals(originalRollValue)) ? parseFloatField(rollEdit, "Roll") : null;
         Float parsedFov = (fovShared && !fovEdit.getValue().trim().equals(originalFovValue)) ? parseFloatField(fovEdit, "FOV") : null;
         Long parsedPause = (pauseShared && !pauseEdit.getValue().trim().equals(originalPauseValue)) ? parseLongField(pauseEdit, "Pause") : null;
         Double parsedSpeed = (allManual && speedShared && !speedOrDurationEdit.getValue().trim().equals(originalSpeedValue)) ? parseDoubleField(speedOrDurationEdit, "Speed") : null;
         Long parsedDuration = (allRecorded && durationShared && !speedOrDurationEdit.getValue().trim().equals(originalSpeedValue)) ? parseLongField(speedOrDurationEdit, "Duration") : null;

         // Check for parse errors only if a value was edited
         if ((parsedYaw == null && !originalYawValue.equals(yawEdit.getValue().trim()))
                 || (parsedPitch == null && !originalPitchValue.equals(pitchEdit.getValue().trim()))
                 || (parsedRoll == null && !originalRollValue.equals(rollEdit.getValue().trim()))
                 || (parsedFov == null && !originalFovValue.equals(fovEdit.getValue().trim()))
                 || (parsedPause == null && !originalPauseValue.equals(pauseEdit.getValue().trim()))
                 || (parsedSpeed == null && !originalSpeedValue.equals(speedOrDurationEdit.getValue().trim()) && allManual && speedShared)
                 || (parsedDuration == null && !originalSpeedValue.equals(speedOrDurationEdit.getValue().trim()) && allRecorded && durationShared)) {
             return;
         }

         for (CameraPathRenderer.NodeData node : nodes) {
             // Apply yaw if it was edited
             if (parsedYaw != null) {
                 node.yaw = parsedYaw;
             }
             // Apply pitch if it was edited
             if (parsedPitch != null) {
                 node.pitch = parsedPitch;
             }
             // Apply roll if it was edited
             if (parsedRoll != null) {
                 node.roll = parsedRoll;
             }
             // Apply fov if it was edited
             if (parsedFov != null) {
                 node.fov = parsedFov;
             }
             // Apply pause if it was edited
             if (parsedPause != null) {
                 node.pause = parsedPause;
             }

             // Apply speed if it was edited
             if (parsedSpeed != null) {
                 node.setSpeed(parsedSpeed);
                 node.setDuration(null);
             }
             // Apply duration if it was edited
             if (parsedDuration != null) {
                 node.setDuration(parsedDuration);
                 node.setSpeed(null);
             }

             // Only apply easing if it changed
             if (easingShared && node.easing != sharedEasing) {
                 node.easing = sharedEasing;
             }
             // Only apply movement if it changed
             if (movementShared && !node.movement.equals(sharedMovement)) {
                 node.movement = sharedMovement;
             }
         }
         CameraPathRenderer.setDirty(true);
         Minecraft mc = minecraft;
         if (mc != null && mc.player != null) {
             mc.player.displayClientMessage(Component.literal("§aApplied edits to " + nodes.size() + " selected nodes."), true);
         }
         onClose();
     }

    private Float parseFloatField(EditBox field, String label) {
        try {
            return Float.parseFloat(field.getValue().trim());
        } catch (Exception e) {
            showParseError(label);
            return null;
        }
    }

    private Double parseDoubleField(EditBox field, String label) {
        try {
            return Double.parseDouble(field.getValue().trim());
        } catch (Exception e) {
            showParseError(label);
            return null;
        }
    }

    private Long parseLongField(EditBox field, String label) {
        try {
            return Long.parseLong(field.getValue().trim());
        } catch (Exception e) {
            showParseError(label);
            return null;
        }
    }

    private void showParseError(String label) {
        Minecraft mc = minecraft;
        if (mc != null && mc.player != null) {
            mc.player.displayClientMessage(Component.literal("§cInvalid " + label + " value."), true);
        }
    }

    private EditBox addNumericField(int x, int y, String name, String value) {
        EditBox edit = addRenderableWidget(new EditBox(font, x, y, FIELD_WIDTH, 20, Component.literal(name)));
        edit.setValue(value);
        return edit;
    }

    private boolean allEqualFloat(FloatGetter getter) {
        float first = getter.get(nodes.getFirst());
        for (int i = 1; i < nodes.size(); i++) {
            if (Math.abs(getter.get(nodes.get(i)) - first) > 0.0001f) return false;
        }
        return true;
    }

    private boolean allEqualLong(LongGetter getter) {
        long first = getter.get(nodes.getFirst());
        for (int i = 1; i < nodes.size(); i++) {
            if (getter.get(nodes.get(i)) != first) return false;
        }
        return true;
    }

    private <T> boolean allEqualObject(ObjectGetter<T> getter) {
        T first = getter.get(nodes.getFirst());
        for (int i = 1; i < nodes.size(); i++) {
            if (!Objects.equals(getter.get(nodes.get(i)), first)) return false;
        }
        return true;
    }

    private static String fmt1(float value) { return String.format("%.1f", value); }
    private static String fmt2(double value) { return String.format("%.2f", value); }

    @Override
    public void render(@NotNull GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        graphics.fill(0, 0, this.width, this.height, 0x88000000);
        RenderSystem.setShader(GameRenderer::getPositionColorShader);
        graphics.fill(panelLeft, panelTop, panelRight, panelBottom, 0x66000000);
        graphics.drawCenteredString(font, this.title, width / 2, panelTop + PANEL_PADDING, 0xFFFFFF);

        for (LabelLine label : labels) {
            graphics.drawString(font, label.text(), label.x(), label.y(), label.color(), false);
        }

        super.render(graphics, mouseX, mouseY, partialTick);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    @Override
    public void renderBackground(@NotNull GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        // no-op
    }

    private interface FloatGetter { float get(CameraPathRenderer.NodeData node); }
    private interface LongGetter { long get(CameraPathRenderer.NodeData node); }
    private interface ObjectGetter<T> { T get(CameraPathRenderer.NodeData node); }
}

