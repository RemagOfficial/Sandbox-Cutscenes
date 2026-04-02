package com.remag.scs.client.render;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.blaze3d.systems.RenderSystem;
import com.remag.scs.client.camera.SimpleCameraManager;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractSliderButton;
import net.minecraft.client.gui.components.CommandSuggestions;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.CycleButton;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.util.Mth;
import net.minecraft.world.level.GameRules;
import org.jetbrains.annotations.NotNull;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;

public class EventEditorScreen extends Screen {
    private static final List<String> SUPPORTED_EVENT_TYPES = List.of("command", "fade", "intro_fade", "texture", "time", "weather");
    private static final int PANEL_WIDTH = 320;
    private static final int FULL_WIDTH = 220;
    private static final int HALF_WIDTH = 106;
    private static final int ROW_SPACING = 24;
    private static final int PANEL_PADDING = 12;
    private static final int COMMAND_MAX_LENGTH_FALLBACK = 32767;

    private final CameraPathRenderer.NodeData node;
    private final boolean timedMode;
    private final String requestedType;
    private String selectedEventName;
    private String selectedType = "command";
    private String workingDataJson = "{}";

    private final List<LabelLine> labels = new ArrayList<>();

    private EditBox nameEdit;
    private EditBox commandEdit;
    private EditBox colorEdit;
    private EditBox durationEdit;
    private EditBox texturePathEdit;
    private EditBox textureXEdit;
    private EditBox textureYEdit;
    private EditBox textureScaleEdit;
    private EditBox timeValueEdit;
    private EditBox rawDataEdit;
    private TimeSlider timestampSlider;
    private CommandSuggestions commandSuggestions;

    private long timedEventMaxMs;
    private long timedEventTimeMs;

    private String fadeMode = "in";
    private String textureFade = "none";
    private boolean textureCentered;
    private String weatherType = "clear";

    private int panelLeft;
    private int panelTop;
    private int panelRight;
    private int panelBottom;
    private List<FormattedCharSequence> descriptionLines = List.of();
    private List<FormattedCharSequence> footerLines = List.of();

    private record LabelLine(String text, int x, int y) {}

    public EventEditorScreen(CameraPathRenderer.NodeData node, String initialEventName) {
        this(node, initialEventName, null, false);
    }

    public EventEditorScreen(CameraPathRenderer.NodeData node, String initialEventName, boolean timedMode) {
        this(node, initialEventName, null, timedMode);
    }

    public EventEditorScreen(String initialTimedEventName) {
        this(null, initialTimedEventName, null, true);
    }

    public EventEditorScreen(CameraPathRenderer.NodeData node, String initialEventName, String requestedType) {
        this(node, initialEventName, requestedType, false);
    }

    private EventEditorScreen(CameraPathRenderer.NodeData node, String initialEventName, String requestedType, boolean timedMode) {
        super(Component.literal(timedMode ? "Edit Timed Events" : "Edit Node Events"));
        this.node = node;
        this.selectedEventName = initialEventName;
        this.requestedType = requestedType;
        this.timedMode = timedMode;
    }

    @Override
    protected void init() {
        labels.clear();

        int centerX = width / 2;

        List<String> eventNames = timedMode
                ? SimpleCameraManager.getTimedEventNames()
                : SimpleCameraManager.getNodeEventNames(node);
        if (eventNames.isEmpty()) {
            selectedEventName = timedMode ? SimpleCameraManager.addTimedEvent() : SimpleCameraManager.addEventToNode(node);
            eventNames = timedMode
                    ? SimpleCameraManager.getTimedEventNames()
                    : SimpleCameraManager.getNodeEventNames(node);
        }
        if (selectedEventName == null || selectedEventName.isBlank() || !eventNames.contains(selectedEventName)) {
            selectedEventName = eventNames.getFirst();
        }

        SimpleCameraManager.EventDefinition definition = SimpleCameraManager.getEventDefinition(selectedEventName);
        selectedType = resolveSelectedType(definition.type());
        workingDataJson = buildWorkingDataJson(definition);
        JsonObject data = parseDataObject(workingDataJson);

        descriptionLines = font.split(Component.literal(getTypeDescription()), PANEL_WIDTH - PANEL_PADDING * 2);
        footerLines = SUPPORTED_EVENT_TYPES.contains(selectedType)
                ? List.of()
                : font.split(Component.literal("Custom type: " + selectedType), PANEL_WIDTH - PANEL_PADDING * 2);

        int estimatedPanelHeight = estimatePanelHeight();
        panelTop = Math.max(8, (height - estimatedPanelHeight) / 2);
        panelLeft = centerX - PANEL_WIDTH / 2;
        panelRight = panelLeft + PANEL_WIDTH;

        int labelX = panelLeft + PANEL_PADDING;
        int fieldX = panelRight - PANEL_PADDING - FULL_WIDTH;
        int cursorY = panelTop + PANEL_PADDING;
        cursorY += font.lineHeight + 6;
        cursorY += descriptionLines.size() * font.lineHeight;
        if (!footerLines.isEmpty()) {
            cursorY += 6 + footerLines.size() * font.lineHeight;
        }
        cursorY += 10;

        addRenderableWidget(CycleButton.builder(Component::literal)
                .withValues(eventNames)
                .withInitialValue(selectedEventName)
                .create(fieldX, cursorY, FULL_WIDTH, 20, Component.literal("Event"), (button, value) ->
                        openScreen(new EventEditorScreen(node, value, null, timedMode))));
        labels.add(new LabelLine(timedMode ? "Timed Event" : "Event", labelX, cursorY + 6));

        cursorY += ROW_SPACING;

        nameEdit = addRenderableWidget(new EditBox(font, fieldX, cursorY, FULL_WIDTH, 20, Component.literal("Name")));
        nameEdit.setValue(definition.name());
        labels.add(new LabelLine("Name", labelX, cursorY + 6));

        if (timedMode) {
            cursorY += ROW_SPACING;
            timedEventMaxMs = Math.max(0L, SimpleCameraManager.getActiveCutsceneLengthMs());
            timedEventTimeMs = Mth.clamp(SimpleCameraManager.getTimedEventTimeMs(selectedEventName), 0L, timedEventMaxMs);
            timestampSlider = addRenderableWidget(new TimeSlider(fieldX, cursorY, FULL_WIDTH, 20));
            labels.add(new LabelLine("Time", labelX, cursorY + 6));
        }

        List<String> availableTypes = new ArrayList<>(SUPPORTED_EVENT_TYPES);
        if (!availableTypes.contains(selectedType)) {
            availableTypes.add(selectedType);
        }
        cursorY += ROW_SPACING;
        addRenderableWidget(CycleButton.builder((String value) -> Component.literal(formatTypeName(value)))
                .withValues(availableTypes)
                .withInitialValue(selectedType)
                .create(fieldX, cursorY, FULL_WIDTH, 20, Component.literal("Type"), (button, value) ->
                        openScreen(new EventEditorScreen(node, selectedEventName, value, timedMode))));
        labels.add(new LabelLine("Type", labelX, cursorY + 6));

        int contentEndY = buildTypeControls(fieldX, labelX, cursorY + ROW_SPACING + 8, data);
        int buttonY = contentEndY + 16;

        addRenderableWidget(Button.builder(Component.literal("Save Event"), b -> {
            String dataJson = buildDataJson();
            if (dataJson == null) {
                return;
            }

            boolean saved;
            if (timedMode) {
                saved = SimpleCameraManager.saveTimedEventDefinition(selectedEventName, nameEdit.getValue(), timedEventTimeMs, selectedType, dataJson);
            } else {
                saved = SimpleCameraManager.saveEventDefinition(node, selectedEventName, nameEdit.getValue(), selectedType, dataJson);
            }

            if (saved) {
                selectedEventName = nameEdit.getValue().isBlank() ? selectedEventName : nameEdit.getValue();
                openScreen(new EventEditorScreen(node, selectedEventName, null, timedMode));
            }
        }).bounds(fieldX, buttonY, HALF_WIDTH, 20).build());

        addRenderableWidget(Button.builder(Component.literal("Add Event"), b -> {
            String created = timedMode ? SimpleCameraManager.addTimedEvent() : SimpleCameraManager.addEventToNode(node);
            openScreen(new EventEditorScreen(node, created, null, timedMode));
        }).bounds(fieldX + HALF_WIDTH + 8, buttonY, HALF_WIDTH, 20).build());

        addRenderableWidget(Button.builder(Component.literal("Remove Event"), b -> {
            if (timedMode) {
                SimpleCameraManager.RemoveTimedEventResult result = SimpleCameraManager.removeTimedEvent(selectedEventName);
                if (!result.removed()) {
                    return;
                }

                if (result.nextEventName() == null) {
                    onClose();
                    return;
                }

                openScreen(new EventEditorScreen(node, result.nextEventName(), null, true));
                return;
            }

            SimpleCameraManager.RemoveEventResult result = SimpleCameraManager.removeEventFromNode(node, selectedEventName);
            if (!result.removed()) {
                return;
            }

            if (result.nodeRemoved()) {
                onClose();
                return;
            }

            if (result.nextEventName() == null) {
                if (node != null) {
                    openScreen(new NodeEditorScreen(node));
                } else {
                    onClose();
                }
                return;
            }

            openScreen(new EventEditorScreen(node, result.nextEventName()));
        }).bounds(fieldX, buttonY + ROW_SPACING, HALF_WIDTH, 20).build());

        if (node != null) {
            addRenderableWidget(Button.builder(Component.literal("Back to Node"), b -> openScreen(new NodeEditorScreen(node)))
                    .bounds(fieldX + HALF_WIDTH + 8, buttonY + ROW_SPACING, HALF_WIDTH, 20).build());
        } else {
            addRenderableWidget(Button.builder(Component.literal("Close"), b -> onClose())
                    .bounds(fieldX + HALF_WIDTH + 8, buttonY + ROW_SPACING, HALF_WIDTH, 20).build());
        }

        addRenderableWidget(Button.builder(Component.literal("Done"), b -> onClose())
                .bounds(centerX - 48, buttonY + ROW_SPACING * 2, 96, 20).build());

        panelBottom = buttonY + ROW_SPACING * 2 + 20 + PANEL_PADDING;
    }

    private int buildTypeControls(int fieldX, int labelX, int startY, JsonObject data) {
        return switch (selectedType) {
            case "command" -> buildCommandControls(fieldX, labelX, startY, data);
            case "fade" -> buildFadeControls(fieldX, labelX, startY, data, true);
            case "intro_fade" -> buildFadeControls(fieldX, labelX, startY, data, false);
            case "texture" -> buildTextureControls(fieldX, labelX, startY, data);
            case "time" -> buildTimeControls(fieldX, labelX, startY, data);
            case "weather" -> buildWeatherControls(fieldX, labelX, startY, data);
            default -> buildRawJsonControls(fieldX, labelX, startY, data);
        };
    }

    private int buildCommandControls(int fieldX, int labelX, int startY, JsonObject data) {
        commandEdit = addRenderableWidget(new EditBox(font, fieldX, startY, FULL_WIDTH, 20, Component.literal("Command")));
        commandEdit.setMaxLength(resolveMaxCommandLength());
        commandEdit.setValue(getString(data, "cmd", ""));
        initializeCommandSuggestions();
        configurePlaceholder(commandEdit, "say hello", value -> refreshCommandSuggestions());
        labels.add(new LabelLine("Command", labelX, startY + 6));
        return startY + 20;
    }

    private int buildFadeControls(int fieldX, int labelX, int startY, JsonObject data, boolean allowMode) {
        colorEdit = addRenderableWidget(new EditBox(font, fieldX, startY, FULL_WIDTH, 20, Component.literal("Color")));
        colorEdit.setValue(getString(data, "color", "#000000"));
        configurePlaceholder(colorEdit, "#000000");
        labels.add(new LabelLine("Color", labelX, startY + 6));

        durationEdit = addRenderableWidget(new EditBox(font, fieldX, startY + ROW_SPACING, FULL_WIDTH, 20, Component.literal("Duration (ms)")));
        durationEdit.setValue(String.valueOf(getInt(data, "duration", 1000)));
        configurePlaceholder(durationEdit, "1000");
        labels.add(new LabelLine("Duration", labelX, startY + ROW_SPACING + 6));

        if (!allowMode) {
            return startY + ROW_SPACING + 20;
        }

        fadeMode = getString(data, "mode", data.has("reverse") && data.get("reverse").getAsBoolean() ? "out" : "in");
        addRenderableWidget(CycleButton.builder((String value) -> Component.literal(value.toUpperCase(Locale.ROOT)))
                .withValues("in", "out")
                .withInitialValue(fadeMode)
                .create(fieldX, startY + ROW_SPACING * 2, FULL_WIDTH, 20, Component.literal("Mode"), (button, value) -> fadeMode = value));
        labels.add(new LabelLine("Mode", labelX, startY + ROW_SPACING * 2 + 6));
        return startY + ROW_SPACING * 2 + 20;
    }

    private int buildTextureControls(int fieldX, int labelX, int startY, JsonObject data) {
        texturePathEdit = addRenderableWidget(new EditBox(font, fieldX, startY, FULL_WIDTH, 20, Component.literal("Texture Path")));
        texturePathEdit.setValue(getString(data, "path", ""));
        configurePlaceholder(texturePathEdit, "scm:textures/gui/example.png");
        labels.add(new LabelLine("Path", labelX, startY + 6));

        textureXEdit = addRenderableWidget(new EditBox(font, fieldX, startY + ROW_SPACING, FULL_WIDTH, 20, Component.literal("X")));
        textureXEdit.setValue(String.valueOf(getInt(data, "x", 0)));
        configurePlaceholder(textureXEdit, "0");
        labels.add(new LabelLine("X", labelX, startY + ROW_SPACING + 6));

        textureYEdit = addRenderableWidget(new EditBox(font, fieldX, startY + ROW_SPACING * 2, FULL_WIDTH, 20, Component.literal("Y")));
        textureYEdit.setValue(String.valueOf(getInt(data, "y", 0)));
        configurePlaceholder(textureYEdit, "0");
        labels.add(new LabelLine("Y", labelX, startY + ROW_SPACING * 2 + 6));

        textureScaleEdit = addRenderableWidget(new EditBox(font, fieldX, startY + ROW_SPACING * 3, FULL_WIDTH, 20, Component.literal("Scale")));
        textureScaleEdit.setValue(trimFloat(getTextureScale(data)));
        configurePlaceholder(textureScaleEdit, "1.0");
        labels.add(new LabelLine("Scale", labelX, startY + ROW_SPACING * 3 + 6));

        durationEdit = addRenderableWidget(new EditBox(font, fieldX, startY + ROW_SPACING * 4, FULL_WIDTH, 20, Component.literal("Duration (ms)")));
        durationEdit.setValue(String.valueOf(getInt(data, "duration", 1500)));
        configurePlaceholder(durationEdit, "1500");
        labels.add(new LabelLine("Duration", labelX, startY + ROW_SPACING * 4 + 6));

        textureFade = getString(data, "fade", "none");
        addRenderableWidget(CycleButton.builder((String value) -> Component.literal(value.toUpperCase(Locale.ROOT)))
                .withValues("none", "in", "out")
                .withInitialValue(textureFade)
                .create(fieldX, startY + ROW_SPACING * 5, FULL_WIDTH, 20, Component.literal("Fade"), (button, value) -> textureFade = value));
        labels.add(new LabelLine("Fade", labelX, startY + ROW_SPACING * 5 + 6));

        textureCentered = isTextureCentered(data);
        addRenderableWidget(CycleButton.builder((Boolean value) -> Component.literal(value ? "CENTERED" : "MANUAL"))
                .withValues(Boolean.FALSE, Boolean.TRUE)
                .withInitialValue(textureCentered)
                .create(fieldX, startY + ROW_SPACING * 6, FULL_WIDTH, 20, Component.literal("Anchor"), (button, value) -> textureCentered = value));
        labels.add(new LabelLine("Anchor", labelX, startY + ROW_SPACING * 6 + 6));

        return startY + ROW_SPACING * 6 + 20;
    }

    private int buildTimeControls(int fieldX, int labelX, int startY, JsonObject data) {
        timeValueEdit = addRenderableWidget(new EditBox(font, fieldX, startY, FULL_WIDTH, 20, Component.literal("Time Value")));
        timeValueEdit.setValue(String.valueOf(getTimeValue(data)));
        configurePlaceholder(timeValueEdit, "6000");
        labels.add(new LabelLine("Day Time", labelX, startY + 6));
        return startY + 20;
    }

    private int buildWeatherControls(int fieldX, int labelX, int startY, JsonObject data) {
        weatherType = getString(data, "type", "clear");
        addRenderableWidget(CycleButton.builder((String value) -> Component.literal(value.toUpperCase(Locale.ROOT)))
                .withValues("clear", "rain", "thunder")
                .withInitialValue(weatherType)
                .create(fieldX, startY, FULL_WIDTH, 20, Component.literal("Weather"), (button, value) -> weatherType = value));
        labels.add(new LabelLine("Weather", labelX, startY + 6));
        return startY + 20;
    }

    private int buildRawJsonControls(int fieldX, int labelX, int startY, JsonObject data) {
        rawDataEdit = addRenderableWidget(new EditBox(font, fieldX, startY, FULL_WIDTH, 20, Component.literal("Data JSON")));
        rawDataEdit.setValue(data.toString());
        configurePlaceholder(rawDataEdit, "{\"cmd\":\"say hello\"}");
        labels.add(new LabelLine("Data", labelX, startY + 6));
        return startY + 20;
    }

    private void configurePlaceholder(EditBox box, String placeholder) {
        configurePlaceholder(box, placeholder, null);
    }

    private void configurePlaceholder(EditBox box, String placeholder, Consumer<String> onChange) {
        updatePlaceholder(box, placeholder);
        box.setResponder(value -> {
            updatePlaceholder(box, placeholder);
            if (onChange != null) {
                onChange.accept(value);
            }
        });
    }

    private void updatePlaceholder(EditBox box, String placeholder) {
        box.setSuggestion(box.getValue().isEmpty() ? placeholder : null);
    }

    private int resolveMaxCommandLength() {
        if (minecraft == null || minecraft.level == null) {
            return COMMAND_MAX_LENGTH_FALLBACK;
        }

        int ruleValue = minecraft.level.getGameRules().getInt(GameRules.RULE_MAX_COMMAND_CHAIN_LENGTH);
        return Mth.clamp(ruleValue, 1, COMMAND_MAX_LENGTH_FALLBACK);
    }

    private void initializeCommandSuggestions() {
        if (minecraft == null || commandEdit == null) {
            return;
        }

        commandSuggestions = new CommandSuggestions(minecraft, this, commandEdit, font, true, false, 0, 10, false, 0x80000000);
        // Disable suggestion popup UI, but keep Brigadier completion data available for tab-complete.
        commandSuggestions.setAllowSuggestions(false);
        commandSuggestions.setAllowHiding(false);
        commandSuggestions.updateCommandInfo();
    }

    private void refreshCommandSuggestions() {
        if (commandSuggestions != null) {
            commandSuggestions.updateCommandInfo();
        }
    }

    private boolean isCommandTabCompleteActive() {
        return commandSuggestions != null && commandEdit != null && commandEdit.isFocused();
    }

    private int estimatePanelHeight() {
        int panelHeight = PANEL_PADDING
                + font.lineHeight
                + 6
                + descriptionLines.size() * font.lineHeight
                + (footerLines.isEmpty() ? 0 : 6 + footerLines.size() * font.lineHeight)
                + 10;
        panelHeight += rowsHeight(timedMode ? 4 : 3);
        panelHeight += 8;
        panelHeight += switch (selectedType) {
            case "fade" -> rowsHeight(3);
            case "intro_fade" -> rowsHeight(2);
            case "texture" -> rowsHeight(7);
            default -> rowsHeight(1);
        };
        panelHeight += 16;
        panelHeight += rowsHeight(3);
        panelHeight += PANEL_PADDING;
        return panelHeight;
    }

    private int rowsHeight(int rows) {
        return rows <= 0 ? 0 : (rows - 1) * ROW_SPACING + 20;
    }

    private void adjustTimedEventTime(long deltaMs) {
        if (!timedMode || timestampSlider == null) {
            return;
        }
        timedEventTimeMs = Mth.clamp(timedEventTimeMs + deltaMs, 0L, timedEventMaxMs);
        timestampSlider.syncFromTime();
    }

    private long getTimedNudgeStepMs() {
        if (Screen.hasControlDown()) {
            return 1000L;
        }
        if (Screen.hasShiftDown()) {
            return 100L;
        }
        return 10L;
    }

    private String formatTimeMs(long timeMs) {
        long minutes = timeMs / 60000L;
        double seconds = (timeMs % 60000L) / 1000.0;
        return String.format(Locale.ROOT, "%d:%05.2f", minutes, seconds);
    }

    private String buildDataJson() {
        JsonObject data = parseDataObject(workingDataJson);

        switch (selectedType) {
            case "command" -> data.addProperty("cmd", commandEdit != null ? commandEdit.getValue() : "");
            case "fade" -> {
                data.addProperty("color", getBoxValue(colorEdit, "#000000"));
                Integer duration = parseIntField(durationEdit, "Duration", 1000);
                if (duration == null) return null;
                data.addProperty("duration", duration);
                data.addProperty("mode", fadeMode);
                data.remove("reverse");
            }
            case "intro_fade" -> {
                data.addProperty("color", getBoxValue(colorEdit, "#000000"));
                Integer duration = parseIntField(durationEdit, "Duration", 1000);
                if (duration == null) return null;
                data.addProperty("duration", duration);
                data.remove("mode");
                data.remove("reverse");
            }
            case "texture" -> {
                String path = getBoxValue(texturePathEdit, "");
                if (path.isBlank()) {
                    showValidationError("Texture path cannot be empty.");
                    return null;
                }

                Integer x = parseIntField(textureXEdit, "X", 0);
                Integer y = parseIntField(textureYEdit, "Y", 0);
                Float scale = parseScaleField(textureScaleEdit);
                Integer duration = parseIntField(durationEdit, "Duration", 1500);
                if (x == null || y == null || scale == null || duration == null) return null;

                data.addProperty("path", path);
                data.addProperty("x", x);
                data.addProperty("y", y);
                data.addProperty("scale", scale);
                data.addProperty("duration", duration);
                data.addProperty("fade", textureFade);
                data.addProperty("centered", textureCentered);
            }
            case "time" -> {
                Long value = parseDayTimeField(timeValueEdit);
                if (value == null) return null;
                data.addProperty("value", value);
            }
            case "weather" -> data.addProperty("type", weatherType);
            default -> {
                JsonObject rawData = parseRawJsonInput();
                if (rawData == null) return null;
                data = rawData;
            }
        }

        return data.toString();
    }

    private JsonObject parseRawJsonInput() {
        try {
            JsonElement parsed = JsonParser.parseString(rawDataEdit == null || rawDataEdit.getValue().isBlank() ? "{}" : rawDataEdit.getValue());
            if (!parsed.isJsonObject()) {
                showValidationError("Event data must be a JSON object.");
                return null;
            }
            return parsed.getAsJsonObject();
        } catch (Exception ignored) {
            showValidationError("Event data must be valid JSON.");
            return null;
        }
    }

    private String resolveSelectedType(String currentType) {
        String fallback = currentType == null || currentType.isBlank() ? "command" : currentType.toLowerCase(Locale.ROOT);
        return requestedType == null || requestedType.isBlank() ? fallback : requestedType.toLowerCase(Locale.ROOT);
    }

    private String buildWorkingDataJson(SimpleCameraManager.EventDefinition definition) {
        if (requestedType == null || requestedType.isBlank() || requestedType.equalsIgnoreCase(definition.type())) {
            return definition.dataJson();
        }
        return createDefaultData(selectedType).toString();
    }

    private JsonObject createDefaultData(String type) {
        JsonObject data = new JsonObject();
        switch (type) {
            case "command" -> data.addProperty("cmd", "say hello");
            case "fade" -> {
                data.addProperty("color", "#000000");
                data.addProperty("duration", 1000);
                data.addProperty("mode", "in");
            }
            case "intro_fade" -> {
                data.addProperty("color", "#000000");
                data.addProperty("duration", 1000);
            }
            case "texture" -> {
                data.addProperty("path", "scm:textures/gui/example.png");
                data.addProperty("x", 0);
                data.addProperty("y", 0);
                data.addProperty("scale", 1.0f);
                data.addProperty("duration", 1500);
                data.addProperty("fade", "none");
                data.addProperty("centered", false);
            }
            case "time" -> data.addProperty("value", 6000L);
            case "weather" -> data.addProperty("type", "clear");
        }
        return data;
    }

    private JsonObject parseDataObject(String dataJson) {
        try {
            JsonElement parsed = JsonParser.parseString(dataJson == null || dataJson.isBlank() ? "{}" : dataJson);
            if (parsed.isJsonObject()) {
                return parsed.getAsJsonObject().deepCopy();
            }
        } catch (Exception ignored) {
        }
        return new JsonObject();
    }

    private String getString(JsonObject data, String key, String fallback) {
        return data.has(key) ? data.get(key).getAsString() : fallback;
    }

    private int getInt(JsonObject data, String key, int fallback) {
        try {
            return data.has(key) ? data.get(key).getAsInt() : fallback;
        } catch (Exception ignored) {
            return fallback;
        }
    }

    private long getTimeValue(JsonObject data) {
        try {
            return data.has("value") ? data.get("value").getAsLong() : 6000L;
        } catch (Exception ignored) {
            return 6000L;
        }
    }

    private float getTextureScale(JsonObject data) {
        try {
            return data.has("scale") ? data.get("scale").getAsFloat() : 1.0f;
        } catch (Exception ignored) {
            return 1.0f;
        }
    }

    private boolean isTextureCentered(JsonObject data) {
        try {
            return data.has("centered") && data.get("centered").getAsBoolean();
        } catch (Exception ignored) {
            return false;
        }
    }

    private Integer parseIntField(EditBox box, String label, int fallback) {
        try {
            String value = getBoxValue(box, String.valueOf(fallback));
            return value.isBlank() ? fallback : Integer.parseInt(value);
        } catch (Exception ignored) {
            showValidationError(label + " must be a whole number.");
            return null;
        }
    }

    private Long parseDayTimeField(EditBox box) {
        try {
            String value = getBoxValue(box, "6000");
            return value.isBlank() ? 6000L : Long.parseLong(value);
        } catch (Exception ignored) {
            showValidationError("Day Time must be a whole number.");
            return null;
        }
    }

    private Float parseScaleField(EditBox box) {
        try {
            String value = getBoxValue(box, "1.0");
            return value.isBlank() ? 1.0f : Float.parseFloat(value);
        } catch (Exception ignored) {
            showValidationError("Scale must be a number.");
            return null;
        }
    }

    private String getBoxValue(EditBox box, String fallback) {
        if (box == null) return fallback;
        return box.getValue().trim();
    }

    private String trimFloat(float value) {
        if (Math.abs(value - Math.round(value)) < 0.0001f) {
            return String.valueOf(Math.round(value));
        }
        return String.format(Locale.ROOT, "%.2f", value);
    }

    private String formatTypeName(String value) {
        return "intro_fade".equals(value) ? "INTRO FADE" : value.toUpperCase(Locale.ROOT);
    }

    private void openScreen(Screen screen) {
        Minecraft mc = minecraft;
        if (mc != null) {
            mc.setScreen(screen);
        }
    }

    private String getTypeDescription() {
        if (timedMode) {
            return "Runs the selected event type at the configured timestamp in milliseconds.";
        }
        return switch (selectedType) {
            case "command" -> "Runs a command when this node is reached.";
            case "fade" -> "Controls a regular fade in or fade out.";
            case "intro_fade" -> "Starts the preview with a fixed intro fade.";
            case "texture" -> "Shows a texture overlay on screen.";
            case "time" -> "Sets the world day time instantly.";
            case "weather" -> "Changes the current weather state.";
            default -> "Unknown/custom event type. Edit the raw JSON data directly.";
        };
    }

    private void showValidationError(String message) {
        if (minecraft != null && minecraft.player != null) {
            minecraft.player.displayClientMessage(Component.literal("§c" + message), true);
        }
    }

    @Override
    public void render(@NotNull GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        graphics.fill(0, 0, width, height, 0x88000000);
        RenderSystem.setShader(GameRenderer::getPositionColorShader);

        int centerX = width / 2;
        graphics.fill(panelLeft, panelTop, panelRight, panelBottom, 0x66000000);

        int textY = panelTop + PANEL_PADDING;
        graphics.drawCenteredString(font, title, centerX, textY, 0xFFFFFF);
        textY += font.lineHeight + 6;

        for (FormattedCharSequence line : descriptionLines) {
            graphics.drawString(font, line, panelLeft + PANEL_PADDING, textY, 0xB8C7D9);
            textY += font.lineHeight;
        }

        if (!footerLines.isEmpty()) {
            textY += 6;
            for (FormattedCharSequence line : footerLines) {
                graphics.drawString(font, line, panelLeft + PANEL_PADDING, textY, 0xE0C070);
                textY += font.lineHeight;
            }
        }

        for (LabelLine label : labels) {
            graphics.drawString(font, label.text(), label.x(), label.y(), 0xAAAAAA);
        }

        super.render(graphics, mouseX, mouseY, partialTick);
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (timedMode && timestampSlider != null && timestampSlider.isFocused()) {
            long step = getTimedNudgeStepMs();
            if (keyCode == GLFW.GLFW_KEY_LEFT) {
                adjustTimedEventTime(-step);
                return true;
            }
            if (keyCode == GLFW.GLFW_KEY_RIGHT) {
                adjustTimedEventTime(step);
                return true;
            }
        }

        if (isCommandTabCompleteActive() && keyCode == 258) {
            refreshCommandSuggestions();
            commandSuggestions.showSuggestions(false);
            boolean handled = commandSuggestions.keyPressed(keyCode, scanCode, modifiers);
            commandSuggestions.hide();
            commandEdit.setSuggestion(null);
            return handled;
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    @Override
    public void renderBackground(@NotNull GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        // Keep world visible behind editor.
    }

    private class TimeSlider extends AbstractSliderButton {
        private TimeSlider(int x, int y, int width, int height) {
            super(x, y, width, height, Component.empty(), 0.0D);
            syncFromTime();
        }

        private void syncFromTime() {
            this.value = timedEventMaxMs <= 0L ? 0.0D : (double) timedEventTimeMs / (double) timedEventMaxMs;
            updateMessage();
        }

        @Override
        protected void updateMessage() {
            setMessage(Component.literal(formatTimeMs(timedEventTimeMs) + " / " + formatTimeMs(timedEventMaxMs)));
        }

        @Override
        protected void applyValue() {
            timedEventTimeMs = timedEventMaxMs <= 0L
                    ? 0L
                    : Mth.clamp((long) Math.round(this.value * timedEventMaxMs), 0L, timedEventMaxMs);
            updateMessage();
        }
    }
}

