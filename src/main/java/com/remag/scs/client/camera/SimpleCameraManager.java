package com.remag.scs.client.camera;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.annotations.SerializedName;
import com.remag.scs.client.render.CameraPathRenderer;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.util.Mth;
import net.minecraft.world.level.Level;
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
    private static final Gson GSON = new Gson();

    private static final ExecutorService SAVE_EXECUTOR = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "SCS-Save");
        t.setDaemon(true);
        return t;
    });
    private static volatile boolean saveInProgress = false;

    private static boolean recording = false;
    private static int nextRecordTick = -1;
    
    // Last recorded position and rotation for threshold checks
    private static Vec3 lastRecordedPos = Vec3.ZERO;
    private static float lastRecordedYaw = 0;
    private static float lastRecordedPitch = 0;
    private static boolean hasLastRecorded = false;

    private static SimpleCameraEntity camera;
    private static boolean active = false;
    private static final int CAMERA_ENTITY_ID = -420;
    
    private static ResourceLocation currentPreviewLocation;
    private static Vec3 previewBaseOffset = Vec3.ZERO;
    private static Vec3 activeBaseOffset = Vec3.ZERO; // Track world origin of running cutscene
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

    // Helper
    private static float currentYaw;
    private static float currentPitch;
    private static float currentRoll;
    private static float currentFov; // New field
    private static int pauseEndTick = 0;

    // Event system state
    private static final Map<String, CutsceneData.Event> NAMED_EVENTS = new HashMap<>();
    private static final List<CutsceneData.Event> PENDING_TIMED_EVENTS = new ArrayList<>();
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
    private record ActiveTexture(ResourceLocation texture, int x, int y, float scale, int startTick, int durationTicks, String fadeMode, boolean centered) {}

    private record QueuedMove(Vec3 pos, float yaw, float pitch, float roll, float fov, double speed, long postDelay, EasingType easing, String movement, @Nullable Vec3 p0, @Nullable Vec3 p3, @Nullable List<String> eventNames) {}

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
            public double speed = 1.0;
            public long pause = 0;
            public EasingType easing = EasingType.LINEAR;
            public String movement = "linear";
            public Double lookX, lookY, lookZ;
            public JsonElement event; // Can be String or JsonArray
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
            nextRecordTick = 0; // Record immediately on next tick
            
            if (MC.player != null) {
                MC.player.displayClientMessage(Component.literal("§aStarted recording camera path"), true);
            }
        } else {
            if (MC.player != null) {
                MC.player.displayClientMessage(Component.literal("§aStopped recording camera path"), true);
            }
        }
    }

    public record EventInfo(String type, String data) {}

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
        if (location == null) {
            return;
        }

        if (!location.getPath().endsWith(".json")) {
            location = ResourceLocation.fromNamespaceAndPath(location.getNamespace(), location.getPath() + ".json");
        }

        currentPreviewLocation = location;

        try {
            Optional<Resource> resource = MC.getResourceManager().getResource(location);
            if (resource.isEmpty()) {
                sendError("Could not find cutscene file: " + location);
                return;
            }

            try (InputStreamReader reader = new InputStreamReader(resource.get().open())) {
                CutsceneData data = GSON.fromJson(reader, CutsceneData.class);
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

                nodes.add(new CameraPathRenderer.NodeData(baseOffset, startYaw, startPitch, startRoll, 1, 0, 0, 0, EasingType.LINEAR, "linear"));

                for (int i = 0; i < data.points.size(); i++) {
                    CutsceneData.Point p = data.points.get(i);
                    Vec3 pos = new Vec3(p.x + baseOffset.x, p.y + baseOffset.y, p.z + baseOffset.z);
                    CameraPathRenderer.NodeData node = new CameraPathRenderer.NodeData(pos, p.yaw, p.pitch, p.roll, p.fov, p.speed, i + 1, p.pause, p.easing, p.movement);
                    node.events = parseEventList(p.event); // Ensure node data tracks multiple events
                    if (p.lookX != null && p.lookY != null && p.lookZ != null) {
                        // Apply baseOffset to the saved relative lookAt coordinates
                        node.setLookAt(new Vec3(p.lookX + baseOffset.x, p.lookY + baseOffset.y, p.lookZ + baseOffset.z));
                    }
                    nodes.add(node);
                }

                CameraPathRenderer.setPath(location, nodes);

                // Calculate visual positions for timed events (Adjusted to 0.2 offset)
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
        long currentTime = 0;

        for (int i = 0; i < nodes.size() - 1; i++) {
            CameraPathRenderer.NodeData start = nodes.get(i);
            CameraPathRenderer.NodeData end = nodes.get(i + 1);

            double dist = start.pos.distanceTo(end.pos);
            long duration = Math.max(1, (long) (dist / end.speed * 50));

            if (currentTime + duration >= timeMs) {
                double t = (double) (timeMs - currentTime) / duration;
                return start.pos.lerp(end.pos, t);
            }
            currentTime += duration + start.pause;
        }
        return nodes.getLast().pos;
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

    public static void addNodeAtPlayer() {
        var allPaths = CameraPathRenderer.getAllPaths();
        if (allPaths.size() > 1) {
            sendError("Multiple paths are currently previewed. Clear others or only preview one to add nodes.");
            return;
        }

        ResourceLocation activeId = CameraPathRenderer.getActivePathId();
        if (activeId == null || MC.player == null) return;

        List<CameraPathRenderer.NodeData> currentNodes = allPaths.get(activeId);
        if (currentNodes == null || currentNodes.isEmpty()) return;

        Vec3 playerPos = MC.player.position();
        float yaw = MC.player.getYRot();
        float pitch = MC.player.getXRot();
        float roll = 0;
        float fov = 1.0f;

        // Check if we've moved/rotated enough to record a new point
        if (hasLastRecorded) {
            double posThreshold = Config.POSITION_THRESHOLD.get();
            double rotThreshold = Config.ROTATION_THRESHOLD.get();
            
            double distanceMoved = playerPos.distanceToSqr(lastRecordedPos);
            double yawDiff = Math.abs(Mth.wrapDegrees(yaw - lastRecordedYaw));
            double pitchDiff = Math.abs(Mth.wrapDegrees(pitch - lastRecordedPitch));
            
            // Only add a new node if we've moved beyond the threshold
            if (distanceMoved < (posThreshold * posThreshold) && 
                yawDiff < rotThreshold && 
                pitchDiff < rotThreshold) {
                return; // Not enough movement/rotation to record a new point
            }
        }

        double defaultSpeed = 1.0; // Default speed set to 1.0

        // Use the established base offset from the first node
        Vec3 startPos = currentNodes.getFirst().pos;

        // Create new node data
        CameraPathRenderer.NodeData newNode = new CameraPathRenderer.NodeData(
                playerPos,
                yaw,
                pitch,
                roll,
                fov,
                defaultSpeed, // Default speed
                currentNodes.size(), // Next index
                0,   // No pause
                EasingType.LINEAR,
                "curved"
        );

        currentNodes.add(newNode);
        CameraPathRenderer.setDirty(true);
        
        // Update last recorded position and rotation
        lastRecordedPos = playerPos;
        lastRecordedYaw = yaw;
        lastRecordedPitch = pitch;
        hasLastRecorded = true;
        lastRecordedYaw = yaw;
        lastRecordedPitch = pitch;
        hasLastRecorded = true;

        // Notify the user (but only if not the first point to avoid spam)
        if (currentNodes.size() > 1) {
            MC.player.displayClientMessage(Component.literal("§aAdded Node " + newNode.index + " to active path: " + activeId.getPath()), true);
        }
    }

    public static void sendError(String message) {
        if (MC.player != null) {
            MC.player.displayClientMessage(Component.literal("§c[Sandbox Cutscenes] " + message), false);
        }
    }

    private static void executeCutscene(CutsceneData data, Vec3 origin) {
        MC.execute(() -> {
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
            MOVE_QUEUE.clear();
            isMoving = false;
            pendingDisable = false;
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

            synchronized (MOVE_LOCK) {
                MOVE_QUEUE.add(new QueuedMove(targetPos, p.yaw, p.pitch, p.roll, p.fov, p.speed, p.pause, p.easing, p.movement, p0, p3, parseEventList(p.event)));
                if (!isMoving) {
                    processNextMove();
                }
            }
        }

        pendingDisable = true;
    }

    public static void enable() {
        synchronized (MOVE_LOCK) {
            if (active && camera != null) return;
            active = true;
        }

        camera = new SimpleCameraEntity(CAMERA_ENTITY_ID);
        camera.apply(MC.player.position(), MC.player.getYRot(), MC.player.getXRot());
        camera.spawn();
        MC.setCameraEntity(camera);
    }

    public static void disable() {
        if (!active || MC.player == null) return;

        synchronized (MOVE_LOCK) {
            if (isMoving) {
                pendingDisable = true;
                return;
            }
        }
        performDisable();
    }

    public static boolean isActive() { return active; }

    private static void setPosition(Vec3 pos) {
        if (!active || camera == null) return;
        camera.setCameraPosition(pos.x, pos.y, pos.z);
    }

    private static void setRotation(float yaw, float pitch) {
        if (!active || camera == null) return;
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

    private static void apply(double x, double y, double z, float yaw, float pitch) {
        apply(new Vec3(x, y, z), yaw, pitch);
    }

    private static void apply(Vec3 pos, float yaw, float pitch, float roll, float fov) {
        setPosition(pos);
        setRotation(yaw, pitch);
        currentYaw = yaw;
        currentPitch = pitch;
        currentRoll = roll;
        currentFov = Mth.clamp(fov, 0.0f, 1.0f);
    }

    private static void apply(double x, double y, double z, float yaw, float pitch, float roll, float fov) {
        apply(new Vec3(x, y, z), yaw, pitch, roll, fov);
    }

    public static float getYaw() { return currentYaw; }
    public static float getPitch() { return currentPitch; }
    public static float getRoll() { return currentRoll; }
    public static float getFov() { return currentFov; }
    public static SimpleCameraEntity getCamera() { return camera; }

    private static void moveTo(Vec3 pos, float yaw, float pitch, float roll, float fov, double speed, long postMoveDelayMs, EasingType easing) {
        synchronized (MOVE_LOCK) {
            MOVE_QUEUE.add(new QueuedMove(pos, yaw, pitch, roll, fov, speed, postMoveDelayMs, easing, "linear", null, null, null));
            if (!isMoving) {
                processNextMove();
            }
        }
    }

    /* ---------------- Queue / Movement ---------------- */

    public static void tick() {
        if (Config.DEVELOPER_MODE.get() && recording) {
            int now = MC.gui != null ? MC.gui.getGuiTicks() : 0;
            if (nextRecordTick == -1) {
                nextRecordTick = now;
            }
            if (now >= nextRecordTick) {
                if (hasActivePaths() && !isActive()) {
                    addNodeAtPlayer();
                }
                int interval = Config.RECORDING_INTERVAL_TICKS.get();
                nextRecordTick = now + Math.max(1, interval);
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
                        executeEvent(e);
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
                        executeEvent(NAMED_EVENTS.get(name));
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

    public static void renderTick(float partialTick) {
        if (!active || camera == null) return;

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
                newPos = CameraPathRenderer.calculateCatmullRom(
                    moveP0, moveStartPos, moveTargetPos, moveP3, (float) easedT);
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
        if (distance <= 0.0001) {
            // If no movement needed, just update orientation and schedule next move
            apply(moveTargetPos, moveTargetYaw, moveTargetPitch, moveTargetRoll, moveTargetFov);
            pauseEndTick = move.postDelay > 0 ? MC.gui.getGuiTicks() + (int)(move.postDelay / 50) : 0;
            isMoving = false;
            return;
        }

        // Calculate duration based on speed and recording interval from config
        // speed 1.0 = 1x recording interval, 0.5 = half the interval, 2.0 = double, etc.
        // This ensures smooth movement that matches the recording timing
        int baseTicks = Config.RECORDING_INTERVAL_TICKS.get();
        moveDurationTicks = Math.max(1, (int)(baseTicks / move.speed));
        moveStartTick = MC.gui.getGuiTicks();
        moveEasing = move.easing != null ? move.easing : EasingType.EASE_IN_OUT; // Default to ease in/out for smooth transitions
        moveType = move.movement != null ? move.movement : "linear";
        moveP0 = move.p0;
        moveP3 = move.p3;

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
                newPos = CameraPathRenderer.calculateCatmullRom(
                    moveP0, moveStartPos, moveTargetPos, moveP3, (float)easedT);
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

    private static void finishMove() {
        isMoving = false;
        // Ensure we're exactly at the target position
        if (moveTargetPos != null) {
            apply(moveTargetPos, moveTargetYaw, moveTargetPitch, moveTargetRoll, moveTargetFov);
        }
        // Reset pause timer if it's in the past
        if (pauseEndTick > 0 && MC.gui.getGuiTicks() >= pauseEndTick) {
            pauseEndTick = 0;
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
            fadeStartTick = -1;
            ACTIVE_TEXTURES.clear();

            synchronized (MOVE_LOCK) {
                MOVE_QUEUE.clear();
                isMoving = false;
                pendingDisable = false;
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
            CutsceneData data = new CutsceneData();
            data.events = originalEvents; // Restore cached events
            data.startEvent = GSON.toJsonTree(originalStartEvents);
            data.endEvent = GSON.toJsonTree(originalEndEvents);
            CameraPathRenderer.NodeData startNode = nodes.getFirst();

            if (originalStartJson != null && originalStartJson.isJsonPrimitive() &&
                originalStartJson.getAsString().equals("relative")) {
                data.start = originalStartJson;
                data.yaw = startNode.yaw;
                data.pitch = startNode.pitch;
            } else {
                CutsceneData.Point startPoint = new CutsceneData.Point();
                startPoint.x = startNode.pos.x;
                startPoint.y = startNode.pos.y;
                startPoint.z = startNode.pos.z;
                startPoint.yaw = startNode.yaw;
                startPoint.pitch = startNode.pitch;
                data.start = GSON.toJsonTree(startPoint);
            }

            data.points = new ArrayList<>();
            for (int i = 1; i < nodes.size(); i++) {
                CameraPathRenderer.NodeData node = nodes.get(i);
                CutsceneData.Point p = new CutsceneData.Point();
                p.x = node.pos.x - startNode.pos.x;
                p.y = node.pos.y - startNode.pos.y;
                p.z = node.pos.z - startNode.pos.z;
                p.yaw = node.yaw;
                p.pitch = node.pitch;
                p.speed = node.speed;
                p.pause = node.pause;
                p.easing = node.easing;
                p.movement = node.movement;
                p.event = GSON.toJsonTree(node.events); // Preserve the assigned event list
                if (node.lookAt != null) {
                    // Save as relative to the start node
                    p.lookX = node.lookAt.x - startNode.pos.x;
                    p.lookY = node.lookAt.y - startNode.pos.y;
                    p.lookZ = node.lookAt.z - startNode.pos.z;
                }
                data.points.add(p);
            }

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

            String json = GSON.newBuilder().setPrettyPrinting().create().toJson(data);
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