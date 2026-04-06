package com.remag.scs.client.camera;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.annotations.SerializedName;
import com.remag.scs.SandboxCutscenesClient;
import com.remag.scs.client.render.CameraPathRenderer;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.neoforged.neoforge.common.NeoForge;
import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.annotation.Nullable;
import java.io.IOException;
import java.io.BufferedWriter;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.*;

import java.util.function.Function;
import java.io.File;
import java.io.FileWriter;
import java.util.HashMap;
import java.util.Map;
import com.google.gson.JsonObject;
import com.remag.scs.Config;
import net.minecraft.client.gui.GuiGraphics;
import com.mojang.blaze3d.systems.RenderSystem;

public class SimpleCameraManager {

    private static final Minecraft MC = Minecraft.getInstance();
    private static final Logger LOGGER = LoggerFactory.getLogger("Sandbox Cutscenes");
    private static final Gson GSON = new Gson(); // Reverted to default (ignores nulls)

    private static final ExecutorService SAVE_EXECUTOR = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "SCS-Save");
        t.setDaemon(true);
        return t;
    });
    private static volatile boolean saveInProgress = false;

    private static boolean recording = false;
    private static int nextRecordTick = -1;
    private static boolean instantMovementEnabled = false;

    // Last recorded position and rotation for threshold checks
    private static Vec3 lastRecordedPos = Vec3.ZERO;
    private static float lastRecordedYaw = 0;
    private static float lastRecordedPitch = 0;
    private static boolean hasLastRecorded = false;
    private static long lastRecordSampleNanos = -1L;

    private static SimpleCameraEntity camera;
    private static boolean active = false;
    private static final int CAMERA_ENTITY_ID = -420;
    private static final int PROP_CAMERA_ENTITY_ID = -421;
    private static boolean previewPlaybackActive = false;
    private static int previewPlaybackStartTick = -1;
    private static long previewPlaybackTotalMs = 0L;

    private static ResourceLocation currentPreviewLocation;
    private static Vec3 previewBaseOffset = Vec3.ZERO;
    private static Vec3 activeBaseOffset = Vec3.ZERO; // Track world origin of running cutscene
    private static JsonObject originalCutsceneJson;
    private static JsonElement originalStartJson;
    private static List<CutsceneData.Event> originalEvents; // Cache for saving
    private static List<String> originalStartEvents; // Updated to List
    private static List<String> originalEndEvents; // Updated to List

    // Last run state
    private static ResourceLocation lastRunLocation;
    private static Vec3 lastRunOrigin;
    private static CutsceneFinishedEvent.RunSource currentRunSource = CutsceneFinishedEvent.RunSource.API;

    // Movement state
    private static boolean isMoving = false;
    private static boolean pendingDisable = false;

    // Queue system
    private static final BlockingQueue<QueuedMove> MOVE_QUEUE = new LinkedBlockingQueue<>();
    private static final Object MOVE_LOCK = new Object();

    // Smooth movement interpolation
    private static Vec3 moveStartPos;
    private static float moveStartYaw;
    private static float moveStartPitch;
    private static float moveStartRoll;
    private static float moveStartFov; // New field
    private static Vec3 moveTargetPos;
    private static float moveTargetYaw;
    private static float moveTargetPitch;
    private static float moveTargetRoll;
    private static float moveTargetFov; // New field
    private static int moveStartTick;
    private static int moveDurationTicks;
    private static EasingType moveEasing;
    private static String moveType;
    private static Vec3 moveP0, moveP3;
    private static Vec3 moveCurveControl;

    // Helper
    private static float currentYaw;
    private static float currentPitch;
    private static float currentRoll;
    private static float currentFov; // New field
    private static int pauseEndTick = 0;

    // Event system state
    private static final Map<String, CutsceneData.Event> NAMED_EVENTS = new HashMap<>();
    private static final List<CutsceneData.Event> PENDING_TIMED_EVENTS = new ArrayList<>();
    private static final int TIMELINE_MARKER_GREEN = 0xFF00FF64;
    private static final int TIMELINE_MARKER_PURPLE = 0xFFC832FF;
    private static int cutsceneStartTick;
    private static List<String> activeMoveEvents; // Updated to List
    private static net.minecraft.world.entity.player.ChatVisiblity originalChatVisibility;
    private static int chatRestoreTick = -1;
    private static int startDelayTick = -1;
    private static CutsceneData pendingData;
    private static Vec3 pendingOrigin;

    // Fade state
    private static int fadeStartTick = -1;
    private static int fadeDurationTicks = 0;
    private static int fadeColor = 0; // ARGB
    private static boolean fadeReverse = false;

    // Texture event state
    private static final List<ActiveTexture> ACTIVE_TEXTURES = new ArrayList<>();
    
    // Thresholds come from config so users can tune recording sensitivity.
    
    // Error handling
    public static void sendError(String message) {
        if (MC.player != null) {
            MC.player.displayClientMessage(Component.literal("§c[Sandbox Cutscenes] " + message), false);
        } else {
            LOGGER.error("[Sandbox Cutscenes] {}", message);
        }
    }
    private record ActiveTexture(ResourceLocation texture, int x, int y, float scale, int startTick, int durationTicks, String fadeMode, boolean centered) {}
    private record OverlayEntry(Component text, int color) {}
    private record DurationMarker(long timeMs, int order, int color, int count) {}

    private record QueuedMove(Vec3 pos, float yaw, float pitch, float roll, float fov, 
                            double speed, // For manual movement speed (blocks per second)
                            Long duration, // For recorded points (in milliseconds)
                            long postDelay, 
                            EasingType easing, 
                            String movement, 
                            @Nullable Vec3 p0, 
                            @Nullable Vec3 p3, 
                            @Nullable Vec3 curveControl,
                            @Nullable List<String> eventNames) {
        
        // Constructor for manual movement with speed
        public static QueuedMove withSpeed(Vec3 pos, float yaw, float pitch, float roll, float fov,
                                         double speed, long postDelay, EasingType easing,
                                         String movement, Vec3 p0, Vec3 p3, Vec3 curveControl, List<String> eventNames) {
            return new QueuedMove(pos, yaw, pitch, roll, fov, speed, null, postDelay, easing, movement, p0, p3, curveControl, eventNames);
        }
        
        // Constructor for recorded movement with duration
        public static QueuedMove withDuration(Vec3 pos, float yaw, float pitch, float roll, float fov,
                                            long durationMs, long postDelay, EasingType easing,
                                            String movement, Vec3 p0, Vec3 p3, Vec3 curveControl, List<String> eventNames) {
            return new QueuedMove(pos, yaw, pitch, roll, fov, 0, durationMs, postDelay, easing, movement, p0, p3, curveControl, eventNames);
        }
    }

    private static List<String> parseEventList(JsonElement element) {
        List<String> list = new ArrayList<>();
        if (element == null || element.isJsonNull()) return list;
        if (element.isJsonPrimitive() && element.getAsJsonPrimitive().isString()) {
            list.add(element.getAsString());
        } else if (element.isJsonArray()) {
            element.getAsJsonArray().forEach(e -> {
                if (e.isJsonPrimitive() && e.getAsJsonPrimitive().isString()) {
                    list.add(e.getAsString());
                }
            });
        }
        return list;
    }

    /* ---------------- JSON Data Structures ---------------- */

    private static class CutsceneData {
        @SerializedName("start")
        public JsonElement start;

        @SerializedName("start_event")
        public JsonElement startEvent; // Can be String or JsonArray

        @SerializedName("end_event")
        public JsonElement endEvent; // Can be String or JsonArray

        // Root rotation fields for "start_at_player" mode
        public float yaw = 0;
        public float pitch = 0;
        public float roll = 0;
        public float fov = 1.0f; // Default to 1.0 (Player's FOV)

        @SerializedName("points")
        public List<Point> points;

        @SerializedName("events")
        public List<Event> events;

        public static class Point {
            public double x, y, z;
            public float yaw, pitch, roll;
            public float fov = 1.0f; // Default to 1.0
            private Double speed = null; // Changed initialization to null
            private Long duration = null; // Used only for recorded points (duration in milliseconds)
            public long pause = 0;
            public EasingType easing = EasingType.LINEAR;
            public String movement = "linear";
            public Double lookX, lookY, lookZ;
            public Double curveX, curveY, curveZ;
            public JsonElement event; // Can be String or JsonArray

            // Getter for speed (returns null for recorded points)
            public Double getSpeed() {
                return speed;
            }

            // Setter for speed (only for manual points)
            public void setSpeed(Double speed) {
                this.speed = speed;
            }

            // Getter for duration (returns null for manual points)
            public Long getDuration() {
                return duration;
            }

            // Setter for duration (only for recorded points)
            public void setDuration(Long duration) {
                this.duration = duration;
            }
        }

        public static class Event {
            public String name;
            public String type; // "command", "fade", "time", "weather"
            public Long time;   // ms from start (for timed events)
            public JsonObject data;

            @SerializedName("event_data") // Helper for serialization
            public JsonObject eventData;
        }
    }

    /* ---------------- Public API ---------------- */

    public static void runCutscene(ResourceLocation location, Vec3 origin) {
        runCutscene(location, origin, CutsceneFinishedEvent.RunSource.API);
    }

    public static void runCutscene(ResourceLocation location, Vec3 origin, CutsceneFinishedEvent.RunSource source) {
        // Check if we are on the Render Thread. If not, this is likely the Integrated Server thread.
        if (!RenderSystem.isOnRenderThread()) {
            return;
        }

        if (MC.level == null || !MC.level.isClientSide) return;

        synchronized (MOVE_LOCK) {
            if (active || pendingData != null) {
                LOGGER.warn("Trigger at {} tried to run cutscene [{}] while one is already running.", origin, location);
                return;
            }
        }

        lastRunLocation = location;
        lastRunOrigin = origin;
        currentRunSource = source;

        if (!location.getPath().endsWith(".json")) {
            location = ResourceLocation.fromNamespaceAndPath(location.getNamespace(), location.getPath() + ".json");
        }
        try {
            Optional<Resource> resource = MC.getResourceManager().getResource(location);
            if (resource.isEmpty()) {
                active = false; // Reset if failed
                sendError("Could not find cutscene file: " + location);
                return;
            }

            try (InputStreamReader reader = new InputStreamReader(resource.get().open())) {
                CutsceneData data = GSON.fromJson(reader, CutsceneData.class);
                if (data == null || data.points == null || data.points.isEmpty()) {
                    sendError("Cutscene file is empty or missing points: " + location);
                    return;
                }

                executeCutscene(data, origin);
            }
        } catch (Exception e) {
            LOGGER.error("Failed to load cutscene: {}", location, e);
            sendError("Failed to parse cutscene: " + e.getMessage());
        }
    }

    public static void rerunLastCutscene() {
        if (lastRunLocation != null) {
            Vec3 origin = MC.player != null ? MC.player.position() : lastRunOrigin;
            runCutscene(lastRunLocation, origin, CutsceneFinishedEvent.RunSource.HOTKEY);
        } else {
            sendError("No cutscene has been run yet to rerun.");
        }
    }

    public static boolean hasActivePaths() {
        return !CameraPathRenderer.getAllPaths().isEmpty();
    }

    public static boolean isRecording() {
        return recording;
    }

    public static void setRecording(boolean enabled) {
        if (recording == enabled) return;
        
        recording = enabled;
        
        if (enabled) {
            // Reset last recorded position and rotation when starting a new recording
            hasLastRecorded = false;
            lastRecordSampleNanos = -1L;
            nextRecordTick = 0; // Record immediately on next tick
            
            if (MC.player != null) {
                MC.player.displayClientMessage(Component.literal("§aStarted recording camera path"), true);
            }
        } else {
            if (MC.player != null) {
                MC.player.displayClientMessage(Component.literal("§aStopped recording camera path"), true);
            }
            nextRecordTick = -1;
            lastRecordSampleNanos = -1L;
        }
    }

    public static void toggleInstantMovement() {
        instantMovementEnabled = !instantMovementEnabled;
        if (MC.player != null) {
            MC.player.displayClientMessage(Component.literal(instantMovementEnabled
                ? "§aInstant movement §bENABLED"
                : "§aInstant movement §cDISABLED"), true);
        }
    }

    public static boolean isInstantMovementEnabled() {
        return instantMovementEnabled;
    }

    public record EventInfo(String type, String data) {}

    public record EventDefinition(String name, String type, String dataJson) {}

    public record RemoveEventResult(boolean removed, boolean nodeRemoved, String nextEventName) {}

    public record RemoveTimedEventResult(boolean removed, String nextEventName) {}

    private static List<CutsceneData.Event> ensureEventStore() {
        if (originalEvents == null) {
            originalEvents = new ArrayList<>();
        }
        return originalEvents;
    }

    public static List<String> getNodeEventNames(CameraPathRenderer.NodeData node) {
        if (node == null || node.events == null) return new ArrayList<>();
        return new ArrayList<>(node.events);
    }

    public static EventDefinition getEventDefinition(String name) {
        if (name == null || name.isBlank()) {
            return new EventDefinition("", "command", "{}");
        }
        for (CutsceneData.Event e : ensureEventStore()) {
            if (name.equals(e.name)) {
                JsonObject dataObj = e.data != null ? e.data : e.eventData;
                return new EventDefinition(
                        e.name,
                        e.type != null ? e.type : "command",
                        dataObj != null ? GSON.toJson(dataObj) : "{}"
                );
            }
        }
        return new EventDefinition(name, "command", "{}");
    }

    public static String addEventToNode(CameraPathRenderer.NodeData node) {
        if (node == null) return "";
        if (node.events == null) {
            node.events = new ArrayList<>();
        }

        String base = "event_" + node.index + "_" + (node.events.size() + 1);
        String candidate = base;
        int suffix = 1;
        while (findEventByName(candidate) != null) {
            candidate = base + "_" + suffix;
            suffix++;
        }

        CutsceneData.Event created = new CutsceneData.Event();
        created.name = candidate;
        created.type = "command";
        created.data = new JsonObject();
        ensureEventStore().add(created);
        node.events.add(candidate);
        CameraPathRenderer.setDirty(true);
        return candidate;
    }

    public static List<String> getTimedEventNames() {
        List<String> names = new ArrayList<>();
        for (CutsceneData.Event e : ensureEventStore()) {
            if (e.time != null && e.name != null && !e.name.isBlank()) {
                names.add(e.name);
            }
        }
        return names;
    }

    public static String addTimedEvent() {
        String base = "timed_event_";
        int idx = 1;
        String candidate = base + idx;
        while (findEventByName(candidate) != null) {
            idx++;
            candidate = base + idx;
        }

        CutsceneData.Event created = new CutsceneData.Event();
        created.name = candidate;
        created.type = "command";
        created.time = 0L;
        created.data = new JsonObject();
        ensureEventStore().add(created);
        CameraPathRenderer.setDirty(true);
        refreshTimedEventVisuals();
        return candidate;
    }

    public static long getTimedEventTimeMs(String name) {
        CutsceneData.Event e = findEventByName(name);
        if (e == null || e.time == null) {
            return 0L;
        }
        return Math.max(0L, e.time);
    }

    public static boolean saveTimedEventDefinition(String originalName, String newName, long timeMs, String type, String dataJson) {
        if (originalName == null || originalName.isBlank()) {
            return false;
        }

        String safeNewName = newName == null || newName.isBlank() ? originalName : newName.trim();
        String safeType = type == null || type.isBlank() ? "command" : type.trim().toLowerCase();
        long safeTime = Math.max(0L, timeMs);

        CutsceneData.Event existing = findEventByName(safeNewName);
        if (!safeNewName.equals(originalName) && existing != null) {
            sendError("Event name already exists: " + safeNewName);
            return false;
        }

        JsonObject parsedData;
        try {
            parsedData = GSON.fromJson(dataJson == null || dataJson.isBlank() ? "{}" : dataJson, JsonObject.class);
            if (parsedData == null) parsedData = new JsonObject();
        } catch (Exception e) {
            sendError("Event data must be valid JSON object.");
            return false;
        }

        CutsceneData.Event event = findEventByName(originalName);
        if (event == null) {
            event = new CutsceneData.Event();
            ensureEventStore().add(event);
        }

        event.name = safeNewName;
        event.type = safeType;
        event.time = safeTime;
        event.data = parsedData;
        event.eventData = null;

        CameraPathRenderer.setDirty(true);
        refreshTimedEventVisuals();
        return true;
    }

    public static RemoveTimedEventResult removeTimedEvent(String eventName) {
        if (eventName == null || eventName.isBlank()) {
            return new RemoveTimedEventResult(false, null);
        }

        List<String> timedNames = getTimedEventNames();
        int removedIndex = timedNames.indexOf(eventName);
        if (removedIndex == -1 || originalEvents == null) {
            return new RemoveTimedEventResult(false, null);
        }

        originalEvents.removeIf(event -> eventName.equals(event.name) && event.time != null);
        CameraPathRenderer.setDirty(true);
        refreshTimedEventVisuals();

        List<String> remaining = getTimedEventNames();
        if (remaining.isEmpty()) {
            return new RemoveTimedEventResult(true, null);
        }

        int nextIndex = Math.min(removedIndex, remaining.size() - 1);
        return new RemoveTimedEventResult(true, remaining.get(nextIndex));
    }

    public static long getActiveCutsceneLengthMs() {
        List<CameraPathRenderer.NodeData> nodes = CameraPathRenderer.getPathNodes();
        if (nodes.size() < 2) {
            return 0L;
        }

        long totalTicks = 0L;
        for (int i = 0; i < nodes.size() - 1; i++) {
            CameraPathRenderer.NodeData start = nodes.get(i);
            CameraPathRenderer.NodeData end = nodes.get(i + 1);

            totalTicks += getSegmentDurationTicks(start, end);
            totalTicks += getPauseTicks(end.pause);
        }

        return Math.max(0L, totalTicks * 50L);
    }

    private static int getSegmentDurationTicks(CameraPathRenderer.NodeData start, CameraPathRenderer.NodeData end) {
        if (end.getDuration() != null) {
            return Math.max(1, (int) (end.getDuration() / 50L));
        }

        double speed = end.getSpeed() != null ? end.getSpeed() : 1.0;
        double dist = start.pos.distanceTo(end.pos);
        return Math.max(1, (int) ((dist / speed) * 20.0));
    }

    private static int getPauseTicks(long pauseMs) {
        return Math.max(0, (int) (pauseMs / 50L));
    }

    private static int getMarkerColor(int eventCount) {
        return eventCount > 1 ? TIMELINE_MARKER_PURPLE : TIMELINE_MARKER_GREEN;
    }

    public static boolean isPreviewPlaybackActive() {
        return previewPlaybackActive;
    }

    public static long getPreviewPlaybackTotalMs() {
        long total = previewPlaybackActive ? previewPlaybackTotalMs : getActiveCutsceneLengthMs();
        return Math.max(0L, total);
    }

    public static long getPreviewPlaybackElapsedMs() {
        if (!previewPlaybackActive || MC.gui == null || previewPlaybackStartTick < 0) {
            return 0L;
        }

        float partial = MC.getTimer().getGameTimeDeltaPartialTick(false);
        long elapsed = Math.round(Math.max(0.0f, ((MC.gui.getGuiTicks() + partial) - previewPlaybackStartTick) * 50.0f));
        if (previewPlaybackTotalMs > 0L) {
            elapsed = Math.min(previewPlaybackTotalMs, elapsed);
        }
        return elapsed;
    }

    public static float getPreviewPlaybackProgress() {
        long total = getPreviewPlaybackTotalMs();
        if (total <= 0L) {
            return 0.0f;
        }
        return Mth.clamp((float) getPreviewPlaybackElapsedMs() / (float) total, 0.0f, 1.0f);
    }

    public static void togglePreviewPlayback() {
        if (active || pendingData != null) {
            sendError("Stop the active cutscene before starting prop-camera playback.");
            return;
        }

        if (previewPlaybackActive) {
            stopPreviewPlayback(false);
            if (MC.player != null) {
                MC.player.displayClientMessage(Component.literal("§eStopped prop-camera playback."), true);
            }
            return;
        }

        startPreviewPlayback();
    }

    public static boolean saveEventDefinition(CameraPathRenderer.NodeData node, String originalName, String newName, String type, String dataJson) {
        if (node == null || originalName == null || originalName.isBlank()) return false;

        String safeNewName = newName == null || newName.isBlank() ? originalName : newName.trim();
        String safeType = type == null || type.isBlank() ? "command" : type.trim().toLowerCase();

        if (!safeNewName.equals(originalName) && findEventByName(safeNewName) != null) {
            sendError("Event name already exists: " + safeNewName);
            return false;
        }

        JsonObject parsedData;
        try {
            parsedData = GSON.fromJson(dataJson == null || dataJson.isBlank() ? "{}" : dataJson, JsonObject.class);
            if (parsedData == null) parsedData = new JsonObject();
        } catch (Exception e) {
            sendError("Event data must be valid JSON object.");
            return false;
        }

        CutsceneData.Event event = findEventByName(originalName);
        if (event == null) {
            event = new CutsceneData.Event();
            ensureEventStore().add(event);
        }

        event.name = safeNewName;
        event.type = safeType;
        event.data = parsedData;
        event.eventData = null;

        if (node.events != null) {
            for (int i = 0; i < node.events.size(); i++) {
                if (originalName.equals(node.events.get(i))) {
                    node.events.set(i, safeNewName);
                }
            }
        }

        CameraPathRenderer.setDirty(true);
        return true;
    }

    public static RemoveEventResult removeEventFromNode(CameraPathRenderer.NodeData node, String eventName) {
        if (node == null || node.events == null || node.events.isEmpty() || eventName == null || eventName.isBlank()) {
            return new RemoveEventResult(false, false, null);
        }

        int removedIndex = node.events.indexOf(eventName);
        if (removedIndex == -1) {
            return new RemoveEventResult(false, false, null);
        }

        node.events.remove(removedIndex);
        pruneUnusedEventDefinition(eventName);
        CameraPathRenderer.setDirty(true);

        if (node.events.isEmpty()) {
            // Keep the camera position node; only remove the event assignment.
            return new RemoveEventResult(true, false, null);
        }

        int nextIndex = Math.min(removedIndex, node.events.size() - 1);
        return new RemoveEventResult(true, false, node.events.get(nextIndex));
    }

    private static void refreshTimedEventVisuals() {
        CameraPathRenderer.clearTimedEventVisuals();

        List<CameraPathRenderer.NodeData> nodes = CameraPathRenderer.getPathNodes();
        if (nodes.isEmpty()) {
            return;
        }

        for (CutsceneData.Event e : ensureEventStore()) {
            if (e.time == null) {
                continue;
            }

            Vec3 pathPos = estimatePositionAtTime(nodes, e.time);
            JsonObject dataObj = e.data != null ? e.data : e.eventData;
            String info = dataObj != null ? dataObj.toString() : "{}";
            CameraPathRenderer.addTimedEventVisual(pathPos.add(0, 0.5, 0), e.name != null ? e.name : "Timed", e.type, info);
        }
    }

    private static CutsceneData.Event findEventByName(String name) {
        if (name == null) return null;
        for (CutsceneData.Event e : ensureEventStore()) {
            if (name.equals(e.name)) return e;
        }
        return null;
    }

    private static void pruneUnusedEventDefinition(String eventName) {
        if (eventName == null || eventName.isBlank() || originalEvents == null) {
            return;
        }

        for (List<CameraPathRenderer.NodeData> path : CameraPathRenderer.getAllPaths().values()) {
            for (CameraPathRenderer.NodeData pathNode : path) {
                if (pathNode.events != null && pathNode.events.contains(eventName)) {
                    return;
                }
            }
        }

        if (originalStartEvents != null && originalStartEvents.contains(eventName)) {
            return;
        }
        if (originalEndEvents != null && originalEndEvents.contains(eventName)) {
            return;
        }

        originalEvents.removeIf(event -> eventName.equals(event.name) && event.time == null);
    }

    public static EventInfo getEventInfo(String name) {
        if (originalEvents != null) {
            for (CutsceneData.Event e : originalEvents) {
                if (name.equals(e.name)) {
                    String type = e.type != null ? e.type : "unknown";
                    JsonObject dataObj = e.data != null ? e.data : e.eventData;
                    String dataStr = "";
                    if (dataObj != null) {
                        if (type.equals("command") && dataObj.has("cmd")) dataStr = dataObj.get("cmd").getAsString();
                        else if (type.equals("fade") && dataObj.has("color")) dataStr = dataObj.get("color").getAsString() + " (" + dataObj.get("duration").getAsInt() + "ms)";
                        else if (type.equals("time") && dataObj.has("value")) dataStr = "Set to " + dataObj.get("value").getAsLong();
                        else if (type.equals("weather") && dataObj.has("type")) dataStr = dataObj.get("type").getAsString();
                        else dataStr = dataObj.toString();
                    }
                    return new EventInfo(type, dataStr);
                }
            }
        }
        return new EventInfo("point_trigger", "No additional data");
    }

    public static void previewCutscene(ResourceLocation location, Vec3 origin) {
        if (previewPlaybackActive) {
            stopPreviewPlayback(false);
        }

        if (location == null) {
            return;
        }

        if (!location.getPath().endsWith(".json")) {
            location = ResourceLocation.fromNamespaceAndPath(location.getNamespace(), location.getPath() + ".json");
        }

        currentPreviewLocation = location;
        originalCutsceneJson = null;

        try {
            Optional<Resource> resource = MC.getResourceManager().getResource(location);
            if (resource.isEmpty()) {
                sendError("Could not find cutscene file: " + location);
                return;
            }

            try (InputStreamReader reader = new InputStreamReader(resource.get().open())) {
                JsonElement sourceJson = GSON.fromJson(reader, JsonElement.class);
                originalCutsceneJson = sourceJson != null && sourceJson.isJsonObject() ? sourceJson.getAsJsonObject().deepCopy() : null;
                CutsceneData data = GSON.fromJson(sourceJson, CutsceneData.class);
                if (data == null || data.points == null) return;

                originalStartJson = data.start;
                originalEvents = data.events;
                originalStartEvents = parseEventList(data.startEvent);
                originalEndEvents = parseEventList(data.endEvent);
                List<CameraPathRenderer.NodeData> nodes = new ArrayList<>();

                float startYaw = data.yaw;
                float startPitch = data.pitch;
                float startRoll = data.roll;
                Vec3 baseOffset = Vec3.ZERO;

                if (data.start != null) {
                    if (data.start.isJsonPrimitive() && data.start.getAsString().equals("relative")) {
                        baseOffset = origin;
                    } else if (data.start.isJsonObject()) {
                        CutsceneData.Point p = GSON.fromJson(data.start, CutsceneData.Point.class);
                        baseOffset = new Vec3(p.x, p.y, p.z);
                        startYaw = p.yaw;
                        startPitch = p.pitch;
                        startRoll = p.roll;
                    }
                }
                previewBaseOffset = baseOffset; // Tracking for refresh

                nodes.add(new CameraPathRenderer.NodeData(baseOffset, startYaw, startPitch, startRoll, 1, 0D, 0, 0, EasingType.LINEAR, "linear"));

                for (int i = 0; i < data.points.size(); i++) {
                    CutsceneData.Point p = data.points.get(i);
                    Vec3 pos = new Vec3(p.x + baseOffset.x, p.y + baseOffset.y, p.z + baseOffset.z);
                    CameraPathRenderer.NodeData node = new CameraPathRenderer.NodeData(pos, p.yaw, p.pitch, p.roll, p.fov, p.speed, i + 1, p.pause, p.easing, p.movement);
                    node.events = parseEventList(p.event); // Ensure node data tracks multiple events
                    if (p.lookX != null && p.lookY != null && p.lookZ != null) {
                        // Apply baseOffset to the saved relative lookAt coordinates
                        node.setLookAt(new Vec3(p.lookX + baseOffset.x, p.lookY + baseOffset.y, p.lookZ + baseOffset.z));
                    }
                    if (p.curveX != null && p.curveY != null && p.curveZ != null) {
                        node.setCurveControl(new Vec3(p.curveX + baseOffset.x, p.curveY + baseOffset.y, p.curveZ + baseOffset.z));
                    }
                    nodes.add(node);
                }

                CameraPathRenderer.setPath(location, nodes);

                // Calculate visual positions for timed events (Adjusted to 0.2 offset)
                CameraPathRenderer.clearTimedEventVisuals();
                if (data.events != null) {
                    for (CutsceneData.Event e : data.events) {
                        if (e.time == null) continue;

                        Vec3 pathPos = estimatePositionAtTime(nodes, e.time);
                        JsonObject dataObj = e.data != null ? e.data : e.eventData;
                        String info = dataObj != null ? dataObj.toString() : "{}";

                        CameraPathRenderer.addTimedEventVisual(pathPos.add(0, 0.5, 0), e.name != null ? e.name : "Timed", e.type, info);
                    }
                }
            }
        } catch (Exception e) {
            LOGGER.error("Failed to preview cutscene: {}", location, e);
            sendError("Failed to parse cutscene: " + e.getMessage());
        }
    }

    private static Vec3 estimatePositionAtTime(List<CameraPathRenderer.NodeData> nodes, long timeMs) {
        if (nodes.isEmpty()) return Vec3.ZERO;
        int targetTicks = Math.max(0, (int) (timeMs / 50L));
        int currentTicks = 0;

        for (int i = 0; i < nodes.size() - 1; i++) {
            CameraPathRenderer.NodeData start = nodes.get(i);
            CameraPathRenderer.NodeData end = nodes.get(i + 1);

            int durationTicks = getSegmentDurationTicks(start, end);

            if (currentTicks + durationTicks >= targetTicks) {
                double rawT = Mth.clamp((double) (targetTicks - currentTicks) / durationTicks, 0.0, 1.0);
                SimpleCameraManager.EasingType easing = end.easing != null ? end.easing : EasingType.LINEAR;
                float t = (float) easing.ease(rawT);

                if ("curved".equals(end.movement) && nodes.size() > 2) {
                    Vec3 p0 = nodes.get(Math.max(0, i - 1)).pos;
                    Vec3 p1 = start.pos;
                    Vec3 p2 = end.pos;
                    Vec3 p3 = nodes.get(Math.min(nodes.size() - 1, i + 2)).pos;
                    return CameraPathRenderer.calculateCurve(p0, p1, p2, p3, end.curveControl, t);
                }

                return start.pos.lerp(end.pos, t);
            }
            currentTicks += durationTicks + getPauseTicks(end.pause);
        }
        return nodes.getLast().pos;
    }

    private static List<DurationMarker> buildDurationMarkers() {
        List<DurationMarker> markers = new ArrayList<>();
        List<CameraPathRenderer.NodeData> nodes = CameraPathRenderer.getPathNodes();

        if (nodes.isEmpty()) {
            return markers;
        }

        if (originalStartEvents != null && !originalStartEvents.isEmpty()) {
            markers.add(new DurationMarker(0L, 0, getMarkerColor(originalStartEvents.size()), originalStartEvents.size()));
        }

        CameraPathRenderer.NodeData firstNode = nodes.getFirst();
        if (firstNode.events != null && !firstNode.events.isEmpty()) {
            markers.add(new DurationMarker(0L, 1, getMarkerColor(firstNode.events.size()), firstNode.events.size()));
        }

        long currentTicks = 0L;
        for (int i = 1; i < nodes.size(); i++) {
            CameraPathRenderer.NodeData start = nodes.get(i - 1);
            CameraPathRenderer.NodeData end = nodes.get(i);
            currentTicks += getSegmentDurationTicks(start, end);

            if (end.events != null && !end.events.isEmpty()) {
                markers.add(new DurationMarker(currentTicks * 50L, 1, getMarkerColor(end.events.size()), end.events.size()));
            }

            currentTicks += getPauseTicks(end.pause);
        }

        Map<Long, Integer> timedCounts = new HashMap<>();
        for (CutsceneData.Event event : ensureEventStore()) {
            if (event.time != null) {
                timedCounts.merge(Math.max(0L, event.time), 1, Integer::sum);
            }
        }
        for (Map.Entry<Long, Integer> entry : timedCounts.entrySet()) {
            markers.add(new DurationMarker(entry.getKey(), 2, TIMELINE_MARKER_GREEN, entry.getValue()));
        }

        if (originalEndEvents != null && !originalEndEvents.isEmpty()) {
            markers.add(new DurationMarker(getActiveCutsceneLengthMs(), 3, getMarkerColor(originalEndEvents.size()), originalEndEvents.size()));
        }

        markers.sort((left, right) -> {
            int byTime = Long.compare(left.timeMs(), right.timeMs());
            if (byTime != 0) {
                return byTime;
            }
            return Integer.compare(left.order(), right.order());
        });
        return markers;
    }

    private static String buildDurationMarkerSummary(List<DurationMarker> markers) {
        if (markers.isEmpty()) {
            return "none";
        }

        List<String> parts = new ArrayList<>();
        for (DurationMarker marker : markers) {
            String part = formatDurationMs(marker.timeMs());
            if (marker.count() > 1) {
                part += " x" + marker.count();
            }
            parts.add(part);
        }
        return String.join(" | ", parts);
    }

    private static void startPreviewPlayback() {
        if (MC.level == null || MC.player == null || MC.gui == null) {
            return;
        }

        List<CameraPathRenderer.NodeData> nodes = CameraPathRenderer.getPathNodes();
        if (nodes.size() < 2) {
            sendError("Preview at least two camera nodes before starting prop-camera playback.");
            return;
        }

        if (previewPlaybackActive) {
            stopPreviewPlayback(false);
        }

        synchronized (MOVE_LOCK) {
            MOVE_QUEUE.clear();
            PENDING_TIMED_EVENTS.clear();
            activeMoveEvents = null;
            isMoving = false;
            pendingDisable = false;
            pauseEndTick = 0;
            moveTargetPos = null;
            previewPlaybackActive = true;
        }

        NAMED_EVENTS.clear();
        for (CutsceneData.Event event : ensureEventStore()) {
            if (event.name != null) {
                NAMED_EVENTS.put(event.name, event);
            }
        }

        CameraPathRenderer.NodeData startNode = nodes.getFirst();
        camera = new SimpleCameraEntity(PROP_CAMERA_ENTITY_ID);
        camera.apply(startNode.pos, startNode.yaw, startNode.pitch);
        currentYaw = startNode.yaw;
        currentPitch = startNode.pitch;
        currentRoll = startNode.roll;
        currentFov = Mth.clamp(startNode.fov, 0.0f, 1.0f);
        camera.spawn();

        previewPlaybackStartTick = MC.gui.getGuiTicks();
        cutsceneStartTick = previewPlaybackStartTick;
        previewPlaybackTotalMs = getActiveCutsceneLengthMs();

        for (String eventName : originalStartEvents != null ? originalStartEvents : List.<String>of()) {
            announcePreviewEvent(NAMED_EVENTS.get(eventName), "Start", 0L);
        }

        for (CutsceneData.Event event : ensureEventStore()) {
            if (event.time != null) {
                PENDING_TIMED_EVENTS.add(event);
            }
        }

        for (int i = 1; i < nodes.size(); i++) {
            CameraPathRenderer.NodeData node = nodes.get(i);
            Vec3 p0 = nodes.get(Math.max(0, i - 2)).pos;
            Vec3 p3 = nodes.get(Math.min(nodes.size() - 1, i + 1)).pos;

            if (node.getDuration() != null) {
                MOVE_QUEUE.add(QueuedMove.withDuration(
                        node.pos,
                        node.yaw,
                        node.pitch,
                        node.roll,
                        node.fov,
                        Math.max(1L, node.getDuration()),
                        node.pause,
                        node.easing != null ? node.easing : EasingType.LINEAR,
                        node.movement != null ? node.movement : "linear",
                        p0,
                        p3,
                        node.curveControl,
                        node.events != null ? new ArrayList<>(node.events) : new ArrayList<>()
                ));
            } else {
                MOVE_QUEUE.add(QueuedMove.withSpeed(
                        node.pos,
                        node.yaw,
                        node.pitch,
                        node.roll,
                        node.fov,
                        node.getSpeed() != null ? node.getSpeed() : 1.0,
                        node.pause,
                        node.easing != null ? node.easing : EasingType.LINEAR,
                        node.movement != null ? node.movement : "linear",
                        p0,
                        p3,
                        node.curveControl,
                        node.events != null ? new ArrayList<>(node.events) : new ArrayList<>()
                ));
            }
        }

        if (!MOVE_QUEUE.isEmpty()) {
            processNextMove();
        }
        pendingDisable = true;
        MC.player.displayClientMessage(Component.literal("§aStarted prop-camera playback."), true);
    }

    private static void stopPreviewPlayback(boolean completed) {
        if (!previewPlaybackActive && camera == null) {
            return;
        }

        if (completed) {
            for (String eventName : originalEndEvents != null ? originalEndEvents : List.<String>of()) {
                announcePreviewEvent(NAMED_EVENTS.get(eventName), "End", previewPlaybackTotalMs);
            }
        }

        if (camera != null) {
            camera.despawn();
            camera = null;
        }

        synchronized (MOVE_LOCK) {
            MOVE_QUEUE.clear();
            PENDING_TIMED_EVENTS.clear();
            activeMoveEvents = null;
            isMoving = false;
            pendingDisable = false;
            pauseEndTick = 0;
            moveTargetPos = null;
            previewPlaybackActive = false;
        }

        previewPlaybackStartTick = -1;
        previewPlaybackTotalMs = 0L;

        if (completed && MC.player != null) {
            MC.player.displayClientMessage(Component.literal("§aProp-camera playback finished."), true);
        }
    }

    private static void announcePreviewEvent(@Nullable CutsceneData.Event event, String triggerType, long playbackMs) {
        if (event == null || MC.player == null) {
            return;
        }

        String name = event.name != null && !event.name.isBlank() ? event.name : "unnamed";
        EventInfo info = getEventInfo(name);
        StringBuilder message = new StringBuilder("§b[Preview ")
                .append(triggerType)
                .append(" @ ")
                .append(formatDurationMs(Math.max(0L, playbackMs)))
                .append("] §f")
                .append(name)
                .append(" §7(")
                .append(info.type())
                .append(")");

        if (!info.data().isBlank() && !"No additional data".equals(info.data())) {
            message.append(" §8- §7").append(info.data());
        }

        MC.player.displayClientMessage(Component.literal(message.toString()), false);
    }

    private static String formatDurationMs(long totalMs) {
        long safeMs = Math.max(0L, totalMs);
        long minutes = safeMs / 60000L;
        long seconds = (safeMs / 1000L) % 60L;
        long millis = safeMs % 1000L;
        return String.format("%02d:%02d.%03d", minutes, seconds, millis);
    }

    public static void refreshPreview() {
        var allPaths = CameraPathRenderer.getAllPaths();
        if (allPaths.isEmpty()) return;

        // Take a snapshot of keys to avoid concurrent modification if previewCutscene triggers a change
        List<ResourceLocation> ids = new ArrayList<>(allPaths.keySet());
        for (ResourceLocation id : ids) {
            // Re-run preview for each ID.
            // Note: This uses the last established origin from the first node of that path
            List<CameraPathRenderer.NodeData> currentNodes = allPaths.get(id);
            if (!currentNodes.isEmpty()) {
                previewCutscene(id, currentNodes.getFirst().pos);
            }
        }
    }

    private static long nextRecordedDurationMs() {
        int intervalTicks = Config.RECORDING_INTERVAL_TICKS.get();
        if (intervalTicks > 0) {
            return intervalTicks * 50L;
        }

        long now = System.nanoTime();
        long durationMs = lastRecordSampleNanos < 0
                ? 50L
                : Math.max(1L, Math.round((now - lastRecordSampleNanos) / 1_000_000.0));
        lastRecordSampleNanos = now;
        return durationMs;
    }

    private static void addRecordedPoint(Vec3 pos, float yaw, float pitch, float roll, float fov, long durationMs) {
        var allPaths = CameraPathRenderer.getAllPaths();
        if (allPaths.size() > 1) return;
        
        ResourceLocation activeId = CameraPathRenderer.getActivePathId();
        if (activeId == null || MC.player == null) return;

        List<CameraPathRenderer.NodeData> currentNodes = allPaths.get(activeId);
        if (currentNodes == null) return;
        
        long clampedDurationMs = Math.max(1L, durationMs);

        CameraPathRenderer.NodeData newNode = new CameraPathRenderer.NodeData(
            pos,
            yaw,
            pitch,
            roll,
            fov,
            currentNodes.size(),
            EasingType.LINEAR,
            "curved"
        );

        // Explicitly set duration and clear speed for recorded points
        newNode.setDuration(clampedDurationMs);
        newNode.setSpeed(null); // Clear speed to ensure duration is used
        
        currentNodes.add(newNode);
        CameraPathRenderer.setDirty(true);
        
        // Update last recorded position and rotation
        lastRecordedPos = pos;
        lastRecordedYaw = yaw;
        lastRecordedPitch = pitch;
        hasLastRecorded = true;
        
        // Notify the user (but only if not the first point to avoid spam)
        if (currentNodes.size() > 1) {
            MC.player.displayClientMessage(Component.literal("§aAdded Node " + newNode.index + " to active path: " + activeId.getPath()), true);
        }
    }
    
    public static void addNodeAtPlayer() {
        if (MC.player == null || !hasActivePaths()) return;
        
        // Get player position and rotation
        Vec3 playerPos = MC.player.position();
        float yaw = MC.player.getYRot();
        float pitch = MC.player.getXRot();
        float roll = 0;
        float fov = (float) MC.options.fov().get();
        
        List<CameraPathRenderer.NodeData> currentNodes = CameraPathRenderer.getPathNodes();
        if (currentNodes == null || currentNodes.isEmpty()) return;
        
        // Check if we've moved enough to add a new point
        if (hasLastRecorded) {
            double posThreshold = Config.POSITION_THRESHOLD.get();
            double rotThreshold = Config.ROTATION_THRESHOLD.get();
            double distanceMoved = playerPos.distanceToSqr(lastRecordedPos);
            double yawDiff = Math.abs(Mth.wrapDegrees(yaw - lastRecordedYaw));
            double pitchDiff = Math.abs(Mth.wrapDegrees(pitch - lastRecordedPitch));
            
            if (distanceMoved < (posThreshold * posThreshold) && 
                yawDiff < rotThreshold && 
                pitchDiff < rotThreshold) {
                return; // Not enough movement to add a new point
            }
        }
        
        if (recording) {
            // For recording, use the dedicated method that always uses duration
            addRecordedPoint(playerPos, yaw, pitch, roll, fov, nextRecordedDurationMs());
            return;
        }
        
        // For manual points, use speed
        CameraPathRenderer.NodeData newNode = new CameraPathRenderer.NodeData(
            playerPos,
            yaw,
            pitch,
            roll,
            fov,
            currentNodes.size(),
            EasingType.LINEAR,
            "curved"
        );
        
        // Set speed and ensure no duration is set for manual points
        newNode.setSpeed(1.0);
        newNode.setDuration(null);
        
        currentNodes.add(newNode);
        CameraPathRenderer.setDirty(true);
        
        // Update last recorded position and rotation
        lastRecordedPos = playerPos;
        lastRecordedYaw = yaw;
        lastRecordedPitch = pitch;
        hasLastRecorded = true;
        
        if (currentNodes.size() > 1) {
            MC.player.displayClientMessage(Component.literal("Added point #" + (currentNodes.size() - 1) + " (Speed: " + newNode.getSpeed() + ")"), true);
        }
    }

    private static void executeCutscene(CutsceneData data, Vec3 origin) {
        MC.execute(() -> {
            if (previewPlaybackActive) {
                stopPreviewPlayback(false);
            }

            // Capture chat visibility IMMEDIATELY before any logic starts
            if (originalChatVisibility == null) {
                originalChatVisibility = MC.options.chatVisibility().get();
            }
            MC.options.chatVisibility().set(net.minecraft.world.entity.player.ChatVisiblity.HIDDEN);

            // Initialize Named Events for lookup
            NAMED_EVENTS.clear();
            if (data.events != null) {
                for (CutsceneData.Event e : data.events) {
                    if (e.name != null) NAMED_EVENTS.put(e.name, e);
                }
            }

            // PRE-CALCULATE BASE OFFSET for coordinate expansion (~)
            Vec3 baseOffset = Vec3.ZERO;
            if (data.start != null) {
                if (data.start.isJsonPrimitive() && data.start.getAsString().equals("relative")) {
                    baseOffset = origin;
                } else if (data.start.isJsonObject()) {
                    CutsceneData.Point p = GSON.fromJson(data.start, CutsceneData.Point.class);
                    baseOffset = new Vec3(p.x, p.y, p.z);
                }
            }
            activeBaseOffset = baseOffset;

            // ONLY handle "intro_fade" events for the pre-delay
            int maxFadeDelay = 0;
            for (String eName : parseEventList(data.startEvent)) {
                CutsceneData.Event event = NAMED_EVENTS.get(eName);
                if (event != null && "intro_fade".equalsIgnoreCase(event.type)) {
                    executeEvent(event);
                    JsonObject d = event.data != null ? event.data : event.eventData;
                    int duration = d != null && d.has("duration") ? d.get("duration").getAsInt() : 1000;
                    // Add 10 ticks (0.5s) buffer after fade finishes
                    maxFadeDelay = Math.max(maxFadeDelay, (duration / 50) + 10);
                }
            }

            if (maxFadeDelay > 0) {
                pendingData = data;
                pendingOrigin = origin;
                startDelayTick = MC.gui.getGuiTicks() + maxFadeDelay;
            } else {
                startActualCutscene(data, origin);
            }
        });
    }

    private static void startActualCutscene(CutsceneData data, Vec3 origin) {
        synchronized (MOVE_LOCK) {
            active = true;
            previewPlaybackActive = false;
            MOVE_QUEUE.clear();
            PENDING_TIMED_EVENTS.clear();
            activeMoveEvents = null;
            isMoving = false;
            pendingDisable = false;
            pauseEndTick = 0;
            moveTargetPos = null;
        }

        // FIRE REMAINING START EVENTS (Non-fade ones)
        for (String eName : parseEventList(data.startEvent)) {
            CutsceneData.Event event = NAMED_EVENTS.get(eName);
            if (event != null && !"intro_fade".equalsIgnoreCase(event.type)) {
                executeEvent(event);
            }
        }

        // Internal enable setup
        camera = new SimpleCameraEntity(CAMERA_ENTITY_ID);
        
        float startRoll = data.roll;
        float startFov = data.fov;
        if (data.start != null && data.start.isJsonObject()) {
             CutsceneData.Point p = GSON.fromJson(data.start, CutsceneData.Point.class);
             camera.apply(activeBaseOffset, p.yaw, p.pitch);
             startRoll = p.roll;
             startFov = p.fov;
        } else {
             camera.apply(activeBaseOffset, data.yaw, data.pitch);
        }
        currentRoll = startRoll;
        currentFov = Mth.clamp(startFov, 0.0f, 1.0f);
    
        camera.spawn();
        MC.setCameraEntity(camera);

        PENDING_TIMED_EVENTS.clear();
        cutsceneStartTick = MC.gui.getGuiTicks();
        originalEndEvents = parseEventList(data.endEvent);

        if (data.events != null) {
            for (CutsceneData.Event e : data.events) {
                if (e.time != null) PENDING_TIMED_EVENTS.add(e);
            }
        }

        Vec3 baseOffset = Vec3.ZERO;
        if (data.start != null) {
            if (data.start.isJsonPrimitive() && data.start.getAsString().equals("relative")) {
                baseOffset = origin;
                apply(baseOffset, data.yaw, data.pitch);
            } else if (data.start.isJsonObject()) {
                CutsceneData.Point p = GSON.fromJson(data.start, CutsceneData.Point.class);
                baseOffset = new Vec3(p.x, p.y, p.z);
                apply(baseOffset, p.yaw, p.pitch);
            }
        }
        activeBaseOffset = baseOffset;

        for (int i = 0; i < data.points.size(); i++) {
            CutsceneData.Point p = data.points.get(i);
            Vec3 targetPos = new Vec3(p.x + baseOffset.x, p.y + baseOffset.y, p.z + baseOffset.z);

            Vec3 p0 = i == 0 ? baseOffset : new Vec3(data.points.get(i-1).x + baseOffset.x, data.points.get(i-1).y + baseOffset.y, data.points.get(i-1).z + baseOffset.z);
            Vec3 p3 = i >= data.points.size() - 1 ? targetPos : new Vec3(data.points.get(i+1).x + baseOffset.x, data.points.get(i+1).y + baseOffset.y, data.points.get(i+1).z + baseOffset.z);
            Vec3 curveControl = (p.curveX != null && p.curveY != null && p.curveZ != null)
                    ? new Vec3(p.curveX + baseOffset.x, p.curveY + baseOffset.y, p.curveZ + baseOffset.z)
                    : null;

            synchronized (MOVE_LOCK) {
                // Use duration for recorded points, speed for manual points
                if (p.getDuration() != null) {
                    // For recorded points, use withDuration
                    MOVE_QUEUE.add(QueuedMove.withDuration(
                        targetPos,
                        p.yaw,
                        p.pitch,
                        p.roll,
                        p.fov,
                        p.getDuration(), // duration in ms
                        p.pause, // post delay
                        p.easing != null ? p.easing : EasingType.LINEAR,
                        p.movement != null ? p.movement : "linear",
                        p0,
                        p3,
                        curveControl,
                        parseEventList(p.event)
                    ));
                } else {
                    // For manual points, use withSpeed
                    MOVE_QUEUE.add(QueuedMove.withSpeed(
                        targetPos,
                        p.yaw,
                        p.pitch,
                        p.roll,
                        p.fov,
                        p.getSpeed() != null ? p.getSpeed() : 1.0, // speed in blocks per second
                        p.pause, // post delay
                        p.easing != null ? p.easing : EasingType.LINEAR,
                        p.movement != null ? p.movement : "linear",
                        p0,
                        p3,
                        curveControl,
                        parseEventList(p.event)
                    ));
                }
                if (!isMoving) {
                    processNextMove();
                }
            }
        }

        pendingDisable = true;
    }

    public static boolean isActive() { return active; }

    private static void applyRecordingMovementDamping() {
        if (MC.player == null || MC.screen != null || isActive()) return;

        float forward = 0.0f;
        float strafe = 0.0f;

        if (MC.options.keyUp.isDown()) forward += 1.0f;
        if (MC.options.keyDown.isDown()) forward -= 1.0f;
        if (MC.options.keyRight.isDown()) strafe += 1.0f;
        if (MC.options.keyLeft.isDown()) strafe -= 1.0f;

        Vec3 velocity = MC.player.getDeltaMovement();
        if (Math.abs(forward) < 1.0E-4f && Math.abs(strafe) < 1.0E-4f) {
            MC.player.setDeltaMovement(0.0, velocity.y, 0.0);
            return;
        }

        // Normalize movement intent so diagonals do not exceed straight-line speed.
        double inputLen = Math.sqrt((forward * forward) + (strafe * strafe));
        forward /= (float) inputLen;
        strafe /= (float) inputLen;

        boolean sneaking = MC.player.isShiftKeyDown();
        boolean sprinting = MC.player.isSprinting() && !sneaking;
        double modeBlocksPerSecond = sneaking ? 1.3 : (sprinting ? 5.612 : 4.317);
        double attrScale = MC.player.getAttributeValue(Attributes.MOVEMENT_SPEED) / 0.1;
        double blocksPerTick = (modeBlocksPerSecond / 20.0) * Math.max(0.1, attrScale);

        double yawRad = Math.toRadians(MC.player.getYRot());
        double sin = Math.sin(yawRad);
        double cos = Math.cos(yawRad);

        double moveX = (strafe * cos) - (forward * sin);
        double moveZ = (forward * cos) + (strafe * sin);

        MC.player.setDeltaMovement(moveX * blocksPerTick, velocity.y, moveZ * blocksPerTick);
    }

    private static void setPosition(Vec3 pos) {
        if (camera == null) return;
        camera.setCameraPosition(pos.x, pos.y, pos.z);
    }

    private static void setRotation(float yaw, float pitch) {
        if (camera == null) return;
        camera.setCameraRotation(yaw, pitch);
    }
    
    private static void syncCameraState() {
        if (camera == null) return;
        // Sync previous frame's state to prevent interpolation artifacts
        camera.xo = camera.getX();
        camera.yo = camera.getY();
        camera.zo = camera.getZ();
        camera.xRotO = camera.getXRot();
        camera.yRotO = camera.getYRot();
        camera.yHeadRotO = camera.yHeadRot;
    }

    private static void apply(Vec3 pos, float yaw, float pitch) {
        setPosition(pos);
        setRotation(yaw, pitch);
        currentYaw = yaw;
        currentPitch = pitch;
    }

    private static void apply(Vec3 pos, float yaw, float pitch, float roll, float fov) {
        setPosition(pos);
        setRotation(yaw, pitch);
        currentYaw = yaw;
        currentPitch = pitch;
        currentRoll = roll;
        currentFov = Mth.clamp(fov, 0.0f, 1.0f);
    }

    public static float getYaw() { return currentYaw; }
    public static float getPitch() { return currentPitch; }
    public static float getRoll() { return currentRoll; }
    public static float getFov() { return currentFov; }
    public static SimpleCameraEntity getCamera() { return camera; }

    /* ---------------- Queue / Movement ---------------- */

    public static void tick() {
        // Apply instant movement damping at all times when enabled (not just during recording)
        if (instantMovementEnabled) {
            applyRecordingMovementDamping();
        }

        if (Config.DEVELOPER_MODE.get() && recording) {
            if (Config.INSTANT_RECORDING_MOVEMENT.get()) {
                applyRecordingMovementDamping();
            }

            int interval = Config.RECORDING_INTERVAL_TICKS.get();
            if (interval > 0) {
                int now = MC.gui != null ? MC.gui.getGuiTicks() : 0;
                if (nextRecordTick == -1) {
                    nextRecordTick = now;
                }
                if (now >= nextRecordTick) {
                    if (hasActivePaths() && !isActive() && MC.player != null) {
                        addNodeAtPlayer();
                    }
                    nextRecordTick = now + interval;
                }
            } else {
                // For interval 0, keep positional sampling on client ticks to avoid noisy movement paths.
                int now = MC.gui != null ? MC.gui.getGuiTicks() : 0;
                if (nextRecordTick == -1) {
                    nextRecordTick = now;
                }
                if (now >= nextRecordTick) {
                    if (hasActivePaths() && !isActive() && MC.player != null) {
                        addNodeAtPlayer();
                    }
                    nextRecordTick = now + 1;
                }
            }
        }

        // Handle deferred chat restoration
        if (chatRestoreTick != -1) {
            if (MC.gui != null && MC.gui.getGuiTicks() >= chatRestoreTick) {
                if (MC.gui.getChat() != null) {
                    MC.gui.getChat().clearMessages(true);
                }
                if (originalChatVisibility != null) {
                    MC.options.chatVisibility().set(originalChatVisibility);
                    originalChatVisibility = null; // Reset for next cutscene
                }
                chatRestoreTick = -1;
            }
        }

        // Handle start delay (waiting for fade-in)
        if (pendingData != null && startDelayTick != -1) {
            if (MC.gui.getGuiTicks() >= startDelayTick) {
                startDelayTick = -1;
                fadeStartTick = -1; // Stop the black screen overlay
                startActualCutscene(pendingData, pendingOrigin);
                pendingData = null;
                pendingOrigin = null;
            }
            return;
        }

        synchronized (MOVE_LOCK) {
            int currentTick = MC.gui.getGuiTicks();

            // Process timed events
            if (!PENDING_TIMED_EVENTS.isEmpty()) {
                int elapsedTicks = currentTick - cutsceneStartTick;
                PENDING_TIMED_EVENTS.removeIf(e -> {
                    if (e.time != null && (e.time / 50) <= elapsedTicks) {
                        if (previewPlaybackActive) {
                            announcePreviewEvent(e, "Timed", e.time);
                        } else {
                            executeEvent(e);
                        }
                        return true;
                    }
                    return false;
                });
            }

            if (!isMoving && pauseEndTick > 0) {
                if (currentTick < pauseEndTick) return;
                pauseEndTick = 0;
            }

            if (!isMoving) {
                if (!MOVE_QUEUE.isEmpty()) {
                    processNextMove();
                } else if (pendingDisable) {
                    if (previewPlaybackActive) {
                        stopPreviewPlayback(true);
                        return;
                    }
                    // Check if a fade event is still running (Added 10 tick safety buffer)
                    if (fadeStartTick != -1 && currentTick < (fadeStartTick + fadeDurationTicks + 10)) {
                        return;
                    }
                    performDisable();
                    return;
                }
            }

            if (!isMoving) return;

            // Handle segment transitions
            if (currentTick >= moveStartTick + moveDurationTicks) {
                // Finalize orientation
                apply(moveTargetPos, moveTargetYaw, moveTargetPitch, moveTargetRoll, moveTargetFov);
                isMoving = false;

                // Fire the events for the node we just arrived at
                if (activeMoveEvents != null) {
                    for (String name : activeMoveEvents) {
                        if (previewPlaybackActive) {
                            announcePreviewEvent(NAMED_EVENTS.get(name), "Node", getPreviewPlaybackElapsedMs());
                        } else {
                            executeEvent(NAMED_EVENTS.get(name));
                        }
                    }
                    activeMoveEvents = null;
                }

                if (pauseEndTick > 0) {
                    if (currentTick < pauseEndTick) return;
                    pauseEndTick = 0;
                }

                processNextMove();
            }
        }
    }

    // Helper method to ensure smooth angle interpolation
    private static float lerpAngle(float start, float end, float t) {
        // Normalize angles to 0-360 range
        float diff = ((end - start) % 360.0f + 540.0f) % 360.0f - 180.0f;
        return start + diff * t;
    }

    private static float angleDeltaAbs(float start, float end) {
        return Math.abs(Mth.wrapDegrees(end - start));
    }

    public static void renderTick(float partialTick) {
        if ((!active && !previewPlaybackActive) || camera == null) return;

        synchronized (MOVE_LOCK) {
            if (!isMoving) {
                // Even when not moving, ensure camera is at exact position
                if (moveTargetPos != null) {
                    camera.setPos(moveTargetPos.x, moveTargetPos.y, moveTargetPos.z);
                    camera.setXRot(moveTargetPitch);
                    camera.setYRot(moveTargetYaw);
                    camera.yHeadRot = moveTargetYaw;
                    syncCameraState();
                }
                return;
            }

            // Calculate precise time with partial tick
            double currentTickProgress = (MC.gui.getGuiTicks() - moveStartTick) + partialTick;
            double t = Mth.clamp(currentTickProgress / moveDurationTicks, 0.0, 1.0);

            // Apply easing with optional overshoot for smoother transitions
            double easedT = moveEasing.ease(t);

            // Interpolate position
            Vec3 newPos;
            if ("curved".equals(moveType) && moveP0 != null && moveP3 != null) {
                newPos = CameraPathRenderer.calculateCurve(
                    moveP0, moveStartPos, moveTargetPos, moveP3, moveCurveControl, (float) easedT);
            } else {
                // Use linear interpolation with optional smoothing
                newPos = moveStartPos.lerp(moveTargetPos, easedT);
            }

            // Interpolate angles with proper wrapping
            float newYaw = lerpAngle(moveStartYaw, moveTargetYaw, (float)easedT);
            float newPitch = Mth.lerp((float)easedT, moveStartPitch, moveTargetPitch);
            float newRoll = Mth.lerp((float)easedT, moveStartRoll, moveTargetRoll);
            float newFov = Mth.lerp((float)easedT, moveStartFov, moveTargetFov);

            // Apply the new state
            camera.setPos(newPos.x, newPos.y, newPos.z);
            camera.setXRot(newPitch);
            camera.setYRot(newYaw);
            camera.yHeadRot = newYaw;

            // Sync previous frame's state to prevent interpolation artifacts
            syncCameraState();

            // Update current state
            currentYaw = newYaw;
            currentPitch = newPitch;
            currentRoll = newRoll;
            currentFov = Mth.clamp(newFov, 0.0f, 1.0f);
        }
    }

    public static void recordFrame() {
        if (!Config.DEVELOPER_MODE.get() || !recording) return;
        if (Config.RECORDING_INTERVAL_TICKS.get() > 0) return;
        if (!hasActivePaths() || isActive() || MC.player == null) return;

        // Keep per-frame recording for stationary mouse-look only.
        if (MC.player.getDeltaMovement().lengthSqr() > 1.0E-6) return;

        addNodeAtPlayer();
    }

    private static void processNextMove() {
        QueuedMove next = MOVE_QUEUE.poll();
        if (next != null) {
            startMove(next);
        }
    }

    private static void startMove(QueuedMove move) {
        if (camera == null) return;

        // Store events to fire upon arrival
        activeMoveEvents = move.eventNames;

        // Get current position precisely
        moveStartPos = camera.position();
        moveStartYaw = currentYaw;
        moveStartPitch = currentPitch;
        moveStartRoll = currentRoll;
        moveStartFov = currentFov;

        // Set target position and orientation
        moveTargetPos = move.pos;
        moveTargetYaw = move.yaw;
        moveTargetPitch = move.pitch;
        moveTargetRoll = move.roll;
        moveTargetFov = move.fov;

        // Calculate movement parameters
        double distance = moveStartPos.distanceTo(moveTargetPos);
        float yawDelta = angleDeltaAbs(moveStartYaw, moveTargetYaw);
        float pitchDelta = angleDeltaAbs(moveStartPitch, moveTargetPitch);
        float rollDelta = angleDeltaAbs(moveStartRoll, moveTargetRoll);
        float fovDelta = Math.abs(moveTargetFov - moveStartFov);
        float maxRotationDelta = Math.max(yawDelta, Math.max(pitchDelta, rollDelta));

        // Calculate duration based on whether this is a recorded point (duration) or manual point (speed)
        if (move.duration != null) {
            // For recorded points: use the specified duration (converting ms to ticks)
            moveDurationTicks = (int) Math.ceil(move.duration / 50.0);
        } else {
            boolean noPositionalChange = distance <= 0.0001;
            boolean noRotationalChange = maxRotationDelta <= 0.001f;
            boolean noFovChange = fovDelta <= 0.0001f;
            if (noPositionalChange && noRotationalChange && noFovChange) {
                // True no-op segment: settle immediately.
                apply(moveTargetPos, moveTargetYaw, moveTargetPitch, moveTargetRoll, moveTargetFov);
                pauseEndTick = move.postDelay > 0 ? MC.gui.getGuiTicks() + (int)(move.postDelay / 50) : 0;
                isMoving = false;
                return;
            }

            // For manual points: interpolate by distance, or by angular delta for rotation-only segments.
            double speedValue = move.speed;
            double safeSpeed = Math.max(0.001, speedValue);
            if (distance > 0.0001) {
                // speed 1.0 = 1 block per second, 2.0 = 2 blocks per second, etc.
                moveDurationTicks = (int) Math.ceil((distance / safeSpeed) * 20.0);
            } else {
                // Rotation-only nodes should never snap. Map speed to angular rate.
                double degreesPerSecond = Math.max(30.0, safeSpeed * 90.0);
                moveDurationTicks = (int) Math.ceil((maxRotationDelta / degreesPerSecond) * 20.0);
            }
        }

        // Ensure minimum duration of 1 tick to avoid division by zero
        moveDurationTicks = Math.max(1, moveDurationTicks);

        moveStartTick = MC.gui.getGuiTicks();
        moveEasing = move.easing != null ? move.easing : EasingType.EASE_IN_OUT; // Default to ease in/out for smooth transitions
        moveType = move.movement != null ? move.movement : "linear";
        moveP0 = move.p0;
        moveP3 = move.p3;
        moveCurveControl = move.curveControl;

        // Schedule any post-move delay
        pauseEndTick = move.postDelay > 0 ? moveStartTick + moveDurationTicks + (int)(move.postDelay / 50) : 0;

        // Mark as moving
        isMoving = true;

        // Immediately update to the first frame of movement to prevent any delay
        if (moveDurationTicks > 0) {
            double t = 1.0 / moveDurationTicks; // Very small step for first frame
            double easedT = moveEasing.ease(t);

            // Calculate first frame position
            Vec3 newPos;
            if ("curved".equals(moveType) && moveP0 != null && moveP3 != null) {
                newPos = CameraPathRenderer.calculateCurve(
                    moveP0, moveStartPos, moveTargetPos, moveP3, moveCurveControl, (float)easedT);
            } else {
                newPos = moveStartPos.lerp(moveTargetPos, easedT);
            }

            // Calculate first frame rotation
            float newYaw = lerpAngle(moveStartYaw, moveTargetYaw, (float)easedT);
            float newPitch = Mth.lerp((float)easedT, moveStartPitch, moveTargetPitch);
            float newRoll = Mth.lerp((float)easedT, moveStartRoll, moveTargetRoll);
            float newFov = Mth.lerp((float)easedT, moveStartFov, moveTargetFov);

            // Apply the first frame immediately
            apply(newPos, newYaw, newPitch, newRoll, newFov);
        } else {
            // If duration is 0, jump to target
            apply(moveTargetPos, moveTargetYaw, moveTargetPitch, moveTargetRoll, moveTargetFov);
        }
    }

    private static void performDisable() {
        MC.execute(() -> {
            if (camera == null || MC.player == null) return;

            // Trigger end events while chat is STILL hidden
            if (originalEndEvents != null) {
                for (String name : originalEndEvents) {
                    executeEvent(NAMED_EVENTS.get(name));
                }
            }

            // Store the cutscene ID before we clear it
            ResourceLocation finishedCutsceneId = lastRunLocation;
            CutsceneFinishedEvent.RunSource source = currentRunSource;

            // Defer restoration to catch server feedback
            chatRestoreTick = MC.gui.getGuiTicks() + 10;

            MC.setCameraEntity(MC.player);
            camera.despawn();
            camera = null;
            active = false;
            previewPlaybackActive = false;
            fadeStartTick = -1;
            ACTIVE_TEXTURES.clear();
            previewPlaybackStartTick = -1;
            previewPlaybackTotalMs = 0L;

            synchronized (MOVE_LOCK) {
                MOVE_QUEUE.clear();
                PENDING_TIMED_EVENTS.clear();
                activeMoveEvents = null;
                isMoving = false;
                pendingDisable = false;
                pauseEndTick = 0;
                moveTargetPos = null;
            }

            // Fire the cutscene finished event
            if (finishedCutsceneId != null) {
                CutsceneFinishedEvent event = new CutsceneFinishedEvent(finishedCutsceneId, source);
                NeoForge.EVENT_BUS.post(event);
            }
        });
    }

    private static void executeEvent(@Nullable CutsceneData.Event event) {
        if (event == null || MC.player == null || MC.level == null) return;

        JsonObject data = event.data != null ? event.data : event.eventData;
        if (data == null) data = new JsonObject();

        switch (event.type.toLowerCase()) {
            case "texture":
                if (data.has("path")) {
                    ResourceLocation tex = ResourceLocation.parse(data.get("path").getAsString());
                    int tx = data.has("x") ? data.get("x").getAsInt() : 0;
                    int ty = data.has("y") ? data.get("y").getAsInt() : 0;
                    float ts = data.has("scale") ? data.get("scale").getAsFloat() : 1.0f;
                    int td = data.has("duration") ? data.get("duration").getAsInt() / 50 : 100;
                    String fade = data.has("fade") ? data.get("fade").getAsString().toLowerCase() : "none";
                    boolean centered = data.has("centered") && data.get("centered").getAsBoolean();
                    ACTIVE_TEXTURES.add(new ActiveTexture(tex, tx, ty, ts, MC.gui.getGuiTicks(), td, fade, centered));
                }
                break;
            case "intro_fade":
            case "fade":
                fadeColor = data.has("color") ? (int) Long.parseLong(data.get("color").getAsString().replace("#", ""), 16) : 0xFF000000;
                fadeDurationTicks = data.has("duration") ? data.get("duration").getAsInt() / 50 : 20;
                fadeStartTick = MC.gui.getGuiTicks();

                // intro_fade is ALWAYS standard (Clear -> Opaque)
                if ("intro_fade".equalsIgnoreCase(event.type)) {
                    fadeReverse = false;
                } else {
                    // Normal fade checks for "mode": "out" OR "reverse": true
                    boolean isOut = data.has("mode") && "out".equalsIgnoreCase(data.get("mode").getAsString());
                    boolean isReverse = data.has("reverse") && data.get("reverse").getAsBoolean();
                    fadeReverse = isOut || isReverse;
                }
                break;
            case "time":
                if (data.has("value")) {
                    MC.level.setDayTime(data.get("value").getAsLong());
                }
                break;
            case "weather":
                if (data.has("type")) {
                    String w = data.get("type").getAsString().toLowerCase();
                    switch (w) {
                        case "clear" -> {
                            MC.level.setRainLevel(0);
                            MC.level.setThunderLevel(0);
                        }
                        case "rain" -> MC.level.setRainLevel(1);
                        case "thunder" -> {
                            MC.level.setRainLevel(1);
                            MC.level.setThunderLevel(1);
                        }
                    }
        }
                break;
            case "command":
                if (data.has("cmd")) {
                    String cmd = data.get("cmd").getAsString();
                    if (cmd.startsWith("/")) cmd = cmd.substring(1);

                    // Replace ~ with absolute integer origin of the cutscene
                    StringBuilder rebuilt = getStringBuilder(cmd);

                    MC.player.connection.sendCommand(rebuilt.toString().trim());
                }
                break;
        }
    }

    private static @NotNull StringBuilder getStringBuilder(String cmd) {
        int ix = (int) Math.floor(activeBaseOffset.x);
        int iy = (int) Math.floor(activeBaseOffset.y);
        int iz = (int) Math.floor(activeBaseOffset.z);

        // We split and rebuild to ensure we only replace coordinate markers
        String[] parts = cmd.split(" ");
        StringBuilder rebuilt = new StringBuilder();
        int coordIndex = 0;

        for (String part : parts) {
            if (part.startsWith("~")) {
                int offset = 0;
                try { if(part.length() > 1) offset = Integer.parseInt(part.substring(1)); } catch (Exception ignored) {}

                if (coordIndex == 0) rebuilt.append(ix + offset);
                else if (coordIndex == 1) rebuilt.append(iy + offset);
                else rebuilt.append(iz + offset);

                coordIndex = (coordIndex + 1) % 3;
            } else {
                rebuilt.append(part);
                // Reset coord tracking if we hit a non-coord word
                if (!part.matches("-?\\d+")) coordIndex = 0;
            }
            rebuilt.append(" ");
        }
        return rebuilt;
    }

    public static void renderFade(GuiGraphics graphics) {
        if (fadeStartTick == -1) return;

        int currentTick = MC.gui.getGuiTicks();
        float partial = MC.getTimer().getGameTimeDeltaPartialTick(false);
        float progress = ((currentTick + partial) - fadeStartTick) / (float) fadeDurationTicks;

        progress = Mth.clamp(progress, 0.0f, 1.0f);

        // If reverse, we go from 1.0 (opaque) to 0.0 (transparent)
        float alphaProgress = fadeReverse ? (1.0f - progress) : progress;

        int alpha = (int) (alphaProgress * 255);
        int finalColor = (alpha << 24) | (fadeColor & 0x00FFFFFF);

        graphics.fill(0, 0, graphics.guiWidth(), graphics.guiHeight(), finalColor);
    }

    public static void renderTextures(GuiGraphics graphics) {
        if (ACTIVE_TEXTURES.isEmpty()) return;

        int currentTick = MC.gui.getGuiTicks();
        float partial = MC.getTimer().getGameTimeDeltaPartialTick(false);
        ACTIVE_TEXTURES.removeIf(t -> currentTick > (t.startTick + t.durationTicks));

        for (ActiveTexture t : ACTIVE_TEXTURES) {
            float alpha = getAlpha(t, currentTick, partial);

            float drawX = t.x;
            float drawY = t.y;

            if (t.centered) {
                // Minecraft textures are typically 256x256 base size in blit
                float size = 256 * t.scale;
                drawX = (graphics.guiWidth() - size) / 2f;
                drawY = (graphics.guiHeight() - size) / 2f;
            }

            graphics.pose().pushPose();
            graphics.pose().translate(drawX, drawY, 500);
            graphics.pose().scale(t.scale, t.scale, 1.0f);

            RenderSystem.enableBlend();
            RenderSystem.defaultBlendFunc();
            RenderSystem.setShaderColor(1.0f, 1.0f, 1.0f, Mth.clamp(alpha, 0.0f, 1.0f));

            graphics.blit(t.texture, 0, 0, 0, 0, 256, 256, 256, 256);

            RenderSystem.setShaderColor(1.0f, 1.0f, 1.0f, 1.0f);
            graphics.pose().popPose();
        }
    }

    public static void renderEditorOverlay(GuiGraphics graphics) {
        if (!Config.DEVELOPER_MODE.get() || !CameraPathRenderer.isVisible() || MC.player == null || MC.screen != null) return;

        int x = Math.max(6, graphics.guiWidth() / 100);
        int y = Math.max(6, graphics.guiHeight() / 80);
        int lineHeight = MC.font.lineHeight + 2;
        int availableWidth = Math.max(140, graphics.guiWidth() - (x * 2) - 8);
        int textWidth = Math.min(availableWidth, Math.max(220, graphics.guiWidth() / 3));

        CameraPathRenderer.NodeData dragged = CameraPathRenderer.getDraggedNode();
        CameraPathRenderer.NodeData hovered = CameraPathRenderer.getHoveredNode();
        CameraPathRenderer.NodeData selected = dragged != null ? dragged : hovered;
        CameraPathRenderer.EventNode hoveredEvent = CameraPathRenderer.getHoveredEvent();

        ResourceLocation activeId = CameraPathRenderer.getActivePathId();
        String activePath = activeId != null ? activeId.toString() : "none";
        int nodeCount = CameraPathRenderer.getPathNodes().size();
        int pathCount = CameraPathRenderer.getAllPaths().size();

        String selectedLine = "Selected Node: none";
        if (selected != null) {
            String mode = dragged != null ? "moving" : "hover";
            selectedLine = String.format("Selected Node: #%d (%s) Pos %.2f, %.2f, %.2f Rot %.1f/%.1f Roll %.1f FOV %.2f",
                    selected.index, mode, selected.pos.x, selected.pos.y, selected.pos.z,
                    selected.yaw, selected.pitch, selected.roll, selected.fov);
        }

        String dragLine = CameraPathRenderer.isDragging()
                ? String.format("Drag Distance: %.2f", CameraPathRenderer.getDragDistance())
                : "Drag Distance: -";

        String eventLine = "Hovered Event: none";
        if (hoveredEvent != null) {
            String extra = hoveredEvent.extraInfo() != null ? hoveredEvent.extraInfo() : "";
            if (extra.length() > 140) {
                extra = extra.substring(0, 140) + "...";
            }
            eventLine = "Hovered Event: " + hoveredEvent.name() + " [" + hoveredEvent.type() + "]"
                    + (extra.isEmpty() ? "" : " Data: " + extra);
        }

        String recordingTool = Config.RECORDING_TOOL_ITEM.get();
        String toolMode = switch (CameraPathRenderer.getToolItemMode()) {
            case RECORDING -> "recording";
            case NODE_MOVEMENT -> "node movement";
            case MULTISELECT -> "multiselect";
        };
        long totalDurationMs = getActiveCutsceneLengthMs();
        long previewElapsedMs = getPreviewPlaybackElapsedMs();
        List<DurationMarker> durationMarkers = buildDurationMarkers();
        List<OverlayEntry> statusOverlay = new ArrayList<>();
        statusOverlay.add(new OverlayEntry(Component.literal("Sandbox Cutscenes Editor"), 0x66FFAA));
        statusOverlay.add(new OverlayEntry(Component.literal("Path: " + activePath + " | Paths: " + pathCount + " | Nodes: " + nodeCount), 0xFFFFFF));
        statusOverlay.add(new OverlayEntry(Component.literal("Tool Mode: " + toolMode + " | Selected: " + CameraPathRenderer.getSelectedNodeCount()), 0x7DCBFF));
        statusOverlay.add(new OverlayEntry(Component.literal("Recording: " + (recording ? "ON" : "OFF") + " (" + Config.RECORDING_INTERVAL_TICKS.get() + "t, " + (Config.INSTANT_RECORDING_MOVEMENT.get() || instantMovementEnabled ? "instant" : "vanilla") + ")"), recording ? 0x55FF55 : 0xFFAA55));
        statusOverlay.add(new OverlayEntry(Component.literal("Prop Camera: " + (previewPlaybackActive ? "RUNNING " + formatDurationMs(previewElapsedMs) + " / " + formatDurationMs(totalDurationMs) : "READY " + formatDurationMs(totalDurationMs))), previewPlaybackActive ? 0x55CCFF : 0xAAAAAA));
        if (previewPlaybackActive && camera != null) {
            Vec3 propPos = camera.position();
            statusOverlay.add(new OverlayEntry(Component.literal(String.format("Prop Pos: %.2f, %.2f, %.2f", propPos.x, propPos.y, propPos.z)), 0x99E6FF));
            statusOverlay.add(new OverlayEntry(Component.literal(String.format("Prop Rot: %.1f / %.1f / %.1f | FOV %.2f", currentYaw, currentPitch, currentRoll, currentFov)), 0x99E6FF));
        }
        statusOverlay.add(new OverlayEntry(Component.literal("Dirty: " + (CameraPathRenderer.isDirty() ? "YES" : "NO")), CameraPathRenderer.isDirty() ? 0xFF5555 : 0xAAAAAA));
        statusOverlay.add(new OverlayEntry(Component.literal("Event Marks: " + buildDurationMarkerSummary(durationMarkers)), 0xB8FFB8));
        statusOverlay.add(new OverlayEntry(Component.literal(selectedLine), 0xDDDDDD));
        statusOverlay.add(new OverlayEntry(Component.literal(dragLine), 0x99CCFF));
        statusOverlay.add(new OverlayEntry(Component.literal(eventLine), 0xAADDFF));

        List<OverlayEntry> controlsOverlay = new ArrayList<>();
        controlsOverlay.add(new OverlayEntry(Component.literal("Controls"), 0xFFD966));
        controlsOverlay.add(new OverlayEntry(Component.empty().append(MC.options.keyShift.getTranslatedKeyMessage()).append(Component.literal(" + Left Click: start/stop moving hovered node")), 0xBBBBBB));
        controlsOverlay.add(new OverlayEntry(Component.empty().append(Component.literal("Left Click with ")).append(Component.literal(recordingTool)).append(Component.literal(" in node movement mode: move nodes/handles")), 0xBBBBBB));
        controlsOverlay.add(new OverlayEntry(Component.empty().append(Component.literal("Left Click with ")).append(Component.literal(recordingTool)).append(Component.literal(" in multiselect mode: pick start/end range")), 0xBBBBBB));
        controlsOverlay.add(new OverlayEntry(Component.literal("Right Click with tool in multiselect: edit shared attrs"), 0xBBBBBB));
        controlsOverlay.add(new OverlayEntry(Component.literal("Left Click on hovered node: consume click / block world hit"), 0xBBBBBB));
        controlsOverlay.add(new OverlayEntry(Component.literal("Mouse Wheel while moving node: change drag distance"), 0xBBBBBB));
        controlsOverlay.add(new OverlayEntry(Component.literal("Right Click on node: open node editor"), 0xBBBBBB));
        controlsOverlay.add(new OverlayEntry(Component.literal("Right Click on event icon: open event editor"), 0xBBBBBB));
        controlsOverlay.add(new OverlayEntry(Component.literal("Right Click on timed marker: open timed event editor"), 0xBBBBBB));
        controlsOverlay.add(new OverlayEntry(Component.empty().append(Component.literal("Right Click with ")).append(Component.literal(recordingTool)).append(Component.literal(": toggle recording")), 0xBBBBBB));
        controlsOverlay.add(new OverlayEntry(Component.empty().append(Component.literal("Shift + Right Click with ")).append(Component.literal(recordingTool)).append(Component.literal(": switch tool mode")), 0xBBBBBB));
        controlsOverlay.add(new OverlayEntry(Component.empty().append(SandboxCutscenesClient.ADD_NODE.getTranslatedKeyMessage()).append(Component.literal(": add node at player")), 0xBBBBBB));
        controlsOverlay.add(new OverlayEntry(Component.empty().append(SandboxCutscenesClient.TOGGLE_PROP_CAMERA.getTranslatedKeyMessage()).append(Component.literal(": start/stop prop-camera playback")), 0xBBBBBB));
        controlsOverlay.add(new OverlayEntry(Component.empty().append(SandboxCutscenesClient.SAVE_PATH.getTranslatedKeyMessage()).append(Component.literal(": save active path")), 0xBBBBBB));
        controlsOverlay.add(new OverlayEntry(Component.empty().append(SandboxCutscenesClient.CLEAR_RENDER.getTranslatedKeyMessage()).append(Component.literal(": clear previews (double press if dirty)")), 0xBBBBBB));
        controlsOverlay.add(new OverlayEntry(Component.empty().append(SandboxCutscenesClient.RERUN_CUTSCENE.getTranslatedKeyMessage()).append(Component.literal(": rerun last cutscene")), 0xBBBBBB));
        controlsOverlay.add(new OverlayEntry(Component.empty().append(SandboxCutscenesClient.OPEN_TIMED_EVENTS.getTranslatedKeyMessage()).append(Component.literal(": open timed events editor")), 0xBBBBBB));

        int statusBottom = drawOverlayBox(graphics, statusOverlay, x, y, textWidth, lineHeight);
        drawDurationBar(graphics, x, statusBottom + 4, textWidth, getPreviewPlaybackProgress(), formatDurationMs(previewPlaybackActive ? previewElapsedMs : 0L) + " / " + formatDurationMs(totalDurationMs), previewPlaybackActive, durationMarkers);
        statusBottom += 18;
        int controlsHeight = measureOverlayHeight(controlsOverlay, textWidth, lineHeight);
        int bottomY = graphics.guiHeight() - controlsHeight - 6;
        int controlsY = Math.max(statusBottom + 6, bottomY);
        int controlsBoxWidth = textWidth + 8;
        int controlsX = Math.max(x, graphics.guiWidth() - controlsBoxWidth - x);
        drawOverlayBox(graphics, controlsOverlay, controlsX, controlsY, textWidth, lineHeight);
    }

    private static void drawDurationBar(GuiGraphics graphics, int x, int y, int width, float progress, String label, boolean activeBar, List<DurationMarker> markers) {
        int left = x;
        int top = y;
        int right = x + width;
        int bottom = y + 10;
        int fillRight = left + Math.round(width * Mth.clamp(progress, 0.0f, 1.0f));
        int fillColor = activeBar ? 0xCC55CCFF : 0x88444444;

        graphics.fill(left - 1, top - 1, right + 1, bottom + 1, 0xB0000000);
        graphics.fill(left, top, right, bottom, 0xFF1A1A1A);
        if (fillRight > left) {
            graphics.fill(left, top, fillRight, bottom, fillColor);
        }

        long totalDurationMs = Math.max(1L, getActiveCutsceneLengthMs());
        for (int index = 0; index < markers.size(); ) {
            DurationMarker marker = markers.get(index);
            int clusterEnd = index + 1;
            while (clusterEnd < markers.size() && markers.get(clusterEnd).timeMs() == marker.timeMs()) {
                clusterEnd++;
            }

            int clusterSize = clusterEnd - index;
            for (int clusterIndex = 0; clusterIndex < clusterSize; clusterIndex++) {
                DurationMarker clusterMarker = markers.get(index + clusterIndex);
                float markerProgress = Mth.clamp((float) clusterMarker.timeMs() / (float) totalDurationMs, 0.0f, 1.0f);
                int baseX = left + Math.round(width * markerProgress);
                int offset = (clusterIndex * 3) - ((clusterSize - 1) * 3 / 2);
                int markerX = Mth.clamp(baseX + offset, left, right);
                int markerTop = top - 2;
                int markerBottom = bottom + 2;

                graphics.fill(markerX, markerTop, markerX + 1, markerBottom, clusterMarker.color());
                if (clusterMarker.count() > 1) {
                    graphics.fill(markerX - 1, markerTop + 2, markerX + 2, markerBottom - 2, clusterMarker.color());
                }
            }

            index = clusterEnd;
        }

        graphics.drawString(MC.font, Component.literal("Duration: " + label), left, bottom + 3, 0xE0E0E0, false);
    }

    private static int measureOverlayHeight(List<OverlayEntry> entries, int textWidth, int lineHeight) {
        int wrappedLines = 0;
        for (OverlayEntry entry : entries) {
            wrappedLines += Math.max(1, MC.font.split(entry.text(), textWidth).size());
        }
        return (wrappedLines * lineHeight) + 8;
    }

    private static int drawOverlayBox(GuiGraphics graphics, List<OverlayEntry> entries, int x, int y, int textWidth, int lineHeight) {
        int boxHeight = measureOverlayHeight(entries, textWidth, lineHeight);
        int boxWidth = textWidth + 8;
        graphics.fill(x - 4, y - 4, x + boxWidth, y + boxHeight, 0x90000000);

        int lineY = y;
        for (OverlayEntry entry : entries) {
            for (FormattedCharSequence splitLine : MC.font.split(entry.text(), textWidth)) {
                graphics.drawString(MC.font, splitLine, x, lineY, entry.color(), false);
                lineY += lineHeight;
            }
        }
        return y + boxHeight;
    }

    private static float getAlpha(ActiveTexture t, int currentTick, float partial) {
        float progress = ((currentTick + partial) - t.startTick) / (float) t.durationTicks;
        float alpha = 1.0f;

        // Handle Fade Logic
        int fadeTicks = Math.min(20, t.durationTicks / 4); // 1 second or 25% of duration
        float fadeProgress = (float) fadeTicks / t.durationTicks;

        if (t.fadeMode.equals("in") || t.fadeMode.equals("both")) {
            if (progress < fadeProgress) alpha = progress / fadeProgress;
        }
        if (t.fadeMode.equals("out") || t.fadeMode.equals("both")) {
            if (progress > (1.0f - fadeProgress)) alpha = (1.0f - progress) / fadeProgress;
        }
        return alpha;
    }

    /* ---------------- Easing ---------------- */

    public enum EasingType {
        LINEAR(t -> t),
        EASE_IN(t -> t * t * t),
        EASE_OUT(t -> 1 - Math.pow(1 - t, 3)),
        EASE_IN_OUT(t -> t < 0.5 ? 4 * t * t * t : 1 - Math.pow(-2 * t + 2, 3) / 2);

        private final Function<Double, Double> func;
        EasingType(Function<Double, Double> func) { this.func = func; }
        public double ease(double t) {
            t = Math.min(1.0, Math.max(0.0, t));
            return func.apply(t);
        }
    }

    public static void saveCurrentPath() {
        var allPaths = CameraPathRenderer.getAllPaths();
        if (allPaths.size() > 1) {
            sendError("Multiple paths are currently previewed. Clear others or only preview one to save.");
            return;
        }

        if (saveInProgress) {
            sendError("A save is already in progress.");
            return;
        }

        ResourceLocation activeId = CameraPathRenderer.getActivePathId();
        if (activeId == null || CameraPathRenderer.getPathNodes().isEmpty()) {
            sendError("No active preview to save.");
            return;
        }

        try {
            List<CameraPathRenderer.NodeData> nodes = allPaths.get(activeId);
            JsonObject root = originalCutsceneJson != null ? originalCutsceneJson.deepCopy() : new JsonObject();

            if (originalEvents != null) {
                root.add("events", GSON.toJsonTree(originalEvents));
            } else {
                root.remove("events");
            }

            if (originalStartEvents != null) {
                root.add("startEvent", GSON.toJsonTree(originalStartEvents));
            } else {
                root.remove("startEvent");
            }

            if (originalEndEvents != null) {
                root.add("endEvent", GSON.toJsonTree(originalEndEvents));
            } else {
                root.remove("endEvent");
            }

            CameraPathRenderer.NodeData startNode = nodes.getFirst();

            if (originalStartJson != null && originalStartJson.isJsonPrimitive() &&
                originalStartJson.getAsString().equals("relative")) {
                root.add("start", originalStartJson.deepCopy());
                root.addProperty("yaw", startNode.yaw);
                root.addProperty("pitch", startNode.pitch);
                root.addProperty("roll", startNode.roll);
            } else {
                JsonObject startPoint = root.has("start") && root.get("start").isJsonObject()
                    ? root.getAsJsonObject("start").deepCopy()
                    : new JsonObject();
                startPoint.addProperty("x", startNode.pos.x);
                startPoint.addProperty("y", startNode.pos.y);
                startPoint.addProperty("z", startNode.pos.z);
                startPoint.addProperty("yaw", startNode.yaw);
                startPoint.addProperty("pitch", startNode.pitch);
                startPoint.addProperty("roll", startNode.roll);
                root.add("start", startPoint);
            }

            JsonArray existingPoints = root.has("points") && root.get("points").isJsonArray()
                ? root.getAsJsonArray("points")
                : new JsonArray();
            JsonArray pointsOut = new JsonArray();
            for (int i = 1; i < nodes.size(); i++) {
                CameraPathRenderer.NodeData node = nodes.get(i);
                JsonObject p = (i - 1 < existingPoints.size() && existingPoints.get(i - 1).isJsonObject())
                    ? existingPoints.get(i - 1).getAsJsonObject().deepCopy()
                    : new JsonObject();

                p.addProperty("x", node.pos.x - startNode.pos.x);
                p.addProperty("y", node.pos.y - startNode.pos.y);
                p.addProperty("z", node.pos.z - startNode.pos.z);
                p.addProperty("yaw", node.yaw);
                p.addProperty("pitch", node.pitch);
                p.addProperty("roll", node.roll);
                p.addProperty("fov", node.fov);
                p.addProperty("pause", node.pause);
                p.addProperty("easing", node.easing != null ? node.easing.name().toLowerCase() : EasingType.LINEAR.name().toLowerCase());
                p.addProperty("movement", node.movement != null ? node.movement : "linear");
                p.add("event", GSON.toJsonTree(node.events != null ? node.events : List.<String>of()));

                if (node.getDuration() != null) {
                    p.addProperty("duration", node.getDuration());
                    p.remove("speed");
                } else if (node.getSpeed() != null) {
                    p.addProperty("speed", node.getSpeed());
                    p.remove("duration");
                }

                if (node.lookAt != null) {
                    p.addProperty("lookX", node.lookAt.x - startNode.pos.x);
                    p.addProperty("lookY", node.lookAt.y - startNode.pos.y);
                    p.addProperty("lookZ", node.lookAt.z - startNode.pos.z);
                } else {
                    p.remove("lookX");
                    p.remove("lookY");
                    p.remove("lookZ");
                }
                if (node.curveControl != null) {
                    // Save curve handle relative to the start node for resource-pack portability.
                    p.addProperty("curveX", node.curveControl.x - startNode.pos.x);
                    p.addProperty("curveY", node.curveControl.y - startNode.pos.y);
                    p.addProperty("curveZ", node.curveControl.z - startNode.pos.z);
                } else {
                    p.remove("curveX");
                    p.remove("curveY");
                    p.remove("curveZ");
                }
                pointsOut.add(p);
            }
            root.add("points", pointsOut);

            // Fixed Saving: Check physical directory directly
            File resourcePacksDir = new File(MC.gameDirectory, "resourcepacks");
            File saveFile = null;

            if (resourcePacksDir.exists()) {
                File[] packs = resourcePacksDir.listFiles();
                if (packs != null) {
                    for (File pack : packs) {
                        File potential = new File(pack, "assets/" + currentPreviewLocation.getNamespace() + "/" + currentPreviewLocation.getPath());
                        if (potential.exists() && potential.canWrite()) {
                            saveFile = potential;
                            break;
                        }
                    }
                }
            }

            if (saveFile == null) {
                File dir = new File(MC.gameDirectory, "saved_camera_paths");
                if (!dir.exists()) dir.mkdirs();
                String fileName = currentPreviewLocation.getPath().substring(currentPreviewLocation.getPath().lastIndexOf('/') + 1);
                saveFile = new File(dir, fileName.endsWith(".json") ? fileName : fileName + ".json");
            }
            final File saveFileFinal = saveFile;
            final String saveFileName = saveFileFinal.getName();
            saveInProgress = true;
            if (MC.player != null) {
                MC.player.displayClientMessage(Component.literal("§eSaving: " + saveFileName + " ..."), true);
            }

            String json = GSON.newBuilder().setPrettyPrinting().create().toJson(root);
            int totalChars = json.length();

            CompletableFuture.runAsync(() -> {
                try (BufferedWriter writer = new BufferedWriter(new FileWriter(saveFileFinal))) {
                    final int chunkSize = 64 * 1024;
                    int written = 0;
                    long lastUpdateNs = 0;

                    while (written < totalChars) {
                        int end = Math.min(totalChars, written + chunkSize);
                        writer.write(json, written, end - written);
                        written = end;

                        long now = System.nanoTime();
                        if (lastUpdateNs == 0 || (now - lastUpdateNs) > 250_000_000L) { // ~250ms
                            int pct = totalChars == 0 ? 100 : (int) Math.min(100L, (written * 100L) / totalChars);
                            lastUpdateNs = now;
                            int finalPct = pct;
                            MC.execute(() -> {
                                if (MC.player != null) {
                                    MC.player.displayClientMessage(Component.literal("§eSaving: " + saveFileName + " ... " + finalPct + "%"), true);
                                }
                            });
                        }
                    }
                    writer.flush();

                    MC.execute(() -> {
                        CameraPathRenderer.setDirty(false); // Reset dirty flag on save
                        if (MC.player != null) {
                            MC.player.displayClientMessage(Component.literal("§aSaved to: " + saveFileName), false);
                        }
                    });
                } catch (Exception e) {
                    LOGGER.error("Save failed", e);
                    MC.execute(() -> sendError("Save failed: " + e.getMessage()));
                } finally {
                    saveInProgress = false;
                }
            }, SAVE_EXECUTOR);
        } catch (Exception e) {
            LOGGER.error("Save failed", e);
            sendError("Save failed: " + e.getMessage());
        }
    }

    public static void createNewRelativeCutsceneFile(String requestedName) {
        if (MC.player == null) return;

        String baseName = requestedName == null ? "cutscene" : requestedName.trim();
        if (baseName.isEmpty()) baseName = "cutscene";

        // Prevent directory traversal / nested folders
        baseName = baseName.replace('\\', '_').replace('/', '_');
        baseName = baseName.replaceAll("[^a-zA-Z0-9._-]", "_");

        String fileName = baseName.endsWith(".json") ? baseName : (baseName + ".json");

        File dir = new File(MC.gameDirectory, "saved_camera_paths");
        if (!dir.exists() && !dir.mkdirs()) {
            sendError("Failed to create folder: saved_camera_paths");
            return;
        }

        File out = new File(dir, fileName);
        if (out.exists()) {
            String stem = fileName.substring(0, fileName.length() - ".json".length());
            int i = 1;
            while (out.exists()) {
                out = new File(dir, stem + "_" + i + ".json");
                i++;
            }
        }

        try {
            JsonObject root = new JsonObject();
            root.addProperty("start", "relative");
            root.addProperty("yaw", 0.0f);
            root.addProperty("pitch", 0.0f);
            root.addProperty("roll", 0.0f);
            root.addProperty("fov", 1.0f);
            root.add("points", new JsonArray());

            try (FileWriter writer = new FileWriter(out)) {
                GSON.newBuilder().setPrettyPrinting().create().toJson(root, writer);
            }
            MC.player.displayClientMessage(Component.literal("§aCreated cutscene: " + out.getName()), false);
        } catch (IOException e) {
            LOGGER.error("Failed to create new cutscene file", e);
            sendError("Failed to create cutscene: " + e.getMessage());
        } catch (Exception e) {
            LOGGER.error("Failed to create new cutscene file", e);
            sendError("Failed to create cutscene: " + e.getMessage());
        }
    }
}