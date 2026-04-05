package com.remag.scs.client.render;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.*;
import com.remag.scs.Config;
import com.remag.scs.client.camera.SimpleCameraManager;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class CameraPathRenderer {
    public enum ToolItemMode {
        RECORDING,
        NODE_MOVEMENT,
        MULTISELECT
    }

    private enum CurveHandlePart {
        MAIN,
        Y_POS,
        Y_NEG
    }

    private static final Map<ResourceLocation, List<NodeData>> PATHS = new LinkedHashMap<>();
    private static final List<EventNode> TIMED_EVENTS = new ArrayList<>(); // Store timed event visuals
    private static ResourceLocation activePathId = null;
    private static boolean visible = false;
    private static boolean dirty = false; // New dirty flag
    private static NodeData hoveredNode = null;
    private static NodeData draggedNode = null;
    private static NodeData hoveredCurveHandleNode = null;
    private static NodeData draggedCurveHandleNode = null;
    private static ToolItemMode toolItemMode = ToolItemMode.RECORDING;
    private static CurveHandlePart hoveredCurveHandlePart = null;
    private static CurveHandlePart draggedCurveHandlePart = null;
    private static final double CURVE_Y_HANDLE_OFFSET = 0.35;
    private static final double DEFAULT_DRAG_DISTANCE = 5.0;
    private static final double MIN_DRAG_DISTANCE = 1.0;
    private static final double MAX_DRAG_DISTANCE = 20.0;
    private static double dragDistance = DEFAULT_DRAG_DISTANCE;
    private static final LinkedHashSet<NodeData> SELECTED_NODES = new LinkedHashSet<>();
    private static NodeData selectionAnchorNode = null;
    private static boolean draggingSelectionGroup = false;
    private static final Map<NodeData, Vec3> groupDragOffsets = new HashMap<>();

    public static boolean isVisible() {
        return visible;
    }

    public static List<NodeData> getPathNodes() {
        if (activePathId != null && PATHS.containsKey(activePathId)) {
            return PATHS.get(activePathId);
        }
        return PATHS.isEmpty() ? new ArrayList<>() : PATHS.values().iterator().next();
    }

    public static ResourceLocation getActivePathId() {
        if (activePathId == null && PATHS.size() == 1) {
            return PATHS.keySet().iterator().next();
        }
        return activePathId;
    }

    public static Map<ResourceLocation, List<NodeData>> getAllPaths() {
        return PATHS;
    }

    public static NodeData getHoveredNode() {
        return hoveredNode;
    }

    public static boolean hasHoveredCurveHandle() {
        return hoveredCurveHandleNode != null;
    }

    public static boolean isDraggingCurveHandle() {
        return draggedCurveHandleNode != null;
    }

    public static ToolItemMode getToolItemMode() {
        return toolItemMode;
    }

    public static ToolItemMode toggleToolItemMode() {
        toolItemMode = switch (toolItemMode) {
            case RECORDING -> ToolItemMode.NODE_MOVEMENT;
            case NODE_MOVEMENT -> ToolItemMode.MULTISELECT;
            case MULTISELECT -> ToolItemMode.RECORDING;
        };
        return toolItemMode;
    }

    public static List<NodeData> getSelectedNodes() {
        return new ArrayList<>(SELECTED_NODES);
    }

    public static int getSelectedNodeCount() {
        return SELECTED_NODES.size();
    }

    public static boolean isNodeSelected(NodeData node) {
        return node != null && SELECTED_NODES.contains(node);
    }

    public static boolean hasSelectionAnchor() {
        return selectionAnchorNode != null;
    }

    public static void clearSelection() {
        SELECTED_NODES.clear();
        selectionAnchorNode = null;
    }

    public static void beginSelectionRange(NodeData start) {
        if (start == null) return;
        clearSelection();
        selectionAnchorNode = start;
        SELECTED_NODES.add(start);
    }

    public static int finalizeSelectionRange(NodeData end) {
        if (selectionAnchorNode == null || end == null) {
            return SELECTED_NODES.size();
        }

        List<NodeData> path = findPathForNode(selectionAnchorNode);
        if (path == null || path != findPathForNode(end)) {
            clearSelection();
            beginSelectionRange(end);
            return SELECTED_NODES.size();
        }

        int startIndex = path.indexOf(selectionAnchorNode);
        int endIndex = path.indexOf(end);
        if (startIndex < 0 || endIndex < 0) {
            clearSelection();
            return 0;
        }

        SELECTED_NODES.clear();
        int min = Math.min(startIndex, endIndex);
        int max = Math.max(startIndex, endIndex);
        for (int i = min; i <= max; i++) {
            SELECTED_NODES.add(path.get(i));
        }
        selectionAnchorNode = null;
        return SELECTED_NODES.size();
    }

    public static NodeData getDraggedNode() {
        return draggedNode;
    }

    public static boolean isDragging() {
        return draggedNode != null || draggedCurveHandleNode != null;
    }

    public static double getDragDistance() {
        return dragDistance;
    }

    public static double adjustDragDistance(double delta) {
        dragDistance = Math.max(MIN_DRAG_DISTANCE, Math.min(MAX_DRAG_DISTANCE, dragDistance + delta));
        return dragDistance;
    }

    public static EventNode getHoveredEvent() {
        return hoveredEvent;
    }

    public static class NodeData {
        public SimpleCameraManager.EasingType easing;
        public String movement; // "linear" or "curved"
        public List<String> events = new ArrayList<>(); // Store multiple events
        public final int index;
        public float currentScale = 1.0f;
        public long pause; // Changed to long
        public Vec3 lookAt; // New field
        public Vec3 curveControl; // Optional control handle for incoming curved segment
        public float roll;
        public float fov;
        private Long duration = null; // Only for recorded points (ms)
        
        // For manual points (with speed)
        public NodeData(Vec3 pos, float yaw, float pitch, float roll, float fov, Double speed, int index, 
                       long pause, SimpleCameraManager.EasingType easing, String movement) {
            this.pos = pos;
            this.yaw = yaw;
            this.pitch = pitch;
            this.roll = roll;
            this.fov = fov;
            this.speed = speed;
            this.index = index;
            this.pause = pause;
            this.easing = easing;
            this.movement = movement;
            this.duration = null; // Ensure duration is null for manual points
        }

        // For recorded points (with duration)
        public NodeData(Vec3 pos, float yaw, float pitch, float roll, float fov,
                       int index, SimpleCameraManager.EasingType easing, String movement) {
            this.pos = pos;
            this.yaw = yaw;
            this.pitch = pitch;
            this.roll = roll;
            this.fov = fov;
            this.speed = null; // Changed from 1.0 to null to indicate duration-mode
            this.index = index;
            this.pause = 0; // No pause for recorded points
            this.easing = easing;
            this.movement = movement;
            this.duration = 0L; // Will be set explicitly after construction
        }

        // Getters and setters
        public Double getSpeed() {
            return speed;
        }

        public Long getDuration() {
            return duration;
        }

        // Add setters since we'll be editing these
        public Vec3 pos;
        public Double speed;
        public float yaw;
        public float pitch;
        public void setPos(Vec3 pos) { this.pos = pos; }

        public void setSpeed(Double speed) { this.speed = speed; }
        public void setDuration(Long duration) { this.duration = duration; }

        public void setRotation(float yaw, float pitch, float roll, float fov) {
            this.yaw = yaw; this.pitch = pitch; this.roll = roll; this.fov = fov;
        }
        public void setLookAt(Vec3 lookAt) { this.lookAt = lookAt; }
        public void setCurveControl(Vec3 curveControl) { this.curveControl = curveControl; }
    }

    public static boolean toggleDragging() {
        if (draggedNode != null || draggedCurveHandleNode != null) {
            draggedNode = null;
            draggedCurveHandleNode = null;
            draggedCurveHandlePart = null;
            draggingSelectionGroup = false;
            groupDragOffsets.clear();
            dragDistance = DEFAULT_DRAG_DISTANCE;
            return false;
        }
        if (hoveredCurveHandleNode != null) {
            draggedCurveHandleNode = hoveredCurveHandleNode;
            draggedCurveHandlePart = hoveredCurveHandlePart != null ? hoveredCurveHandlePart : CurveHandlePart.MAIN;
            Vec3 handlePos = getCurveHandlePosition(draggedCurveHandleNode);
            Minecraft mc = Minecraft.getInstance();
            if (mc.player != null) {
                dragDistance = Math.max(MIN_DRAG_DISTANCE,
                        Math.min(MAX_DRAG_DISTANCE, mc.player.position().distanceTo(handlePos)));
            } else {
                dragDistance = DEFAULT_DRAG_DISTANCE;
            }
            return true;
        }
        if (hoveredNode != null) {
            draggedNode = hoveredNode;
            draggingSelectionGroup = SELECTED_NODES.size() > 1 && SELECTED_NODES.contains(hoveredNode);
            groupDragOffsets.clear();
            if (draggingSelectionGroup) {
                Vec3 anchorPos = hoveredNode.pos;
                for (NodeData selected : SELECTED_NODES) {
                    groupDragOffsets.put(selected, selected.pos.subtract(anchorPos));
                }
            }
            Minecraft mc = Minecraft.getInstance();
            if (mc.player != null) {
                dragDistance = Math.max(MIN_DRAG_DISTANCE,
                        Math.min(MAX_DRAG_DISTANCE, mc.player.position().distanceTo(hoveredNode.pos)));
            } else {
                dragDistance = DEFAULT_DRAG_DISTANCE;
            }
            return true;
        }
        return false;
    }

    public static void stopDragging() {
        draggedNode = null;
        draggedCurveHandleNode = null;
        draggedCurveHandlePart = null;
        draggingSelectionGroup = false;
        groupDragOffsets.clear();
        dragDistance = DEFAULT_DRAG_DISTANCE;
    }

    public static void setPath(ResourceLocation id, List<NodeData> nodes) {
        if (nodes == null || nodes.isEmpty()) {
            PATHS.remove(id);
            if (id.equals(activePathId)) activePathId = null;
        } else {
            PATHS.put(id, new ArrayList<>(nodes));
            activePathId = id;
        }
        if (draggedNode != null && PATHS.values().stream().noneMatch(path -> path.contains(draggedNode))) {
            draggedNode = null;
            draggingSelectionGroup = false;
            groupDragOffsets.clear();
        }
        if (draggedCurveHandleNode != null && PATHS.values().stream().noneMatch(path -> path.contains(draggedCurveHandleNode))) {
            draggedCurveHandleNode = null;
            draggedCurveHandlePart = null;
        }
        visible = !PATHS.isEmpty();
        validateSelectionState();
    }

    public static boolean removeNode(NodeData target) {
        if (target == null) {
            return false;
        }

        for (Map.Entry<ResourceLocation, List<NodeData>> entry : PATHS.entrySet()) {
            List<NodeData> path = entry.getValue();
            if (!path.contains(target)) {
                continue;
            }

            List<NodeData> remaining = new ArrayList<>(path);
            remaining.remove(target);

            hoveredNode = hoveredNode == target ? null : hoveredNode;
            hoveredCurveHandleNode = hoveredCurveHandleNode == target ? null : hoveredCurveHandleNode;
            draggedNode = draggedNode == target ? null : draggedNode;
            draggedCurveHandleNode = draggedCurveHandleNode == target ? null : draggedCurveHandleNode;
            if (draggedCurveHandleNode == null) {
                draggedCurveHandlePart = null;
            }
            if (hoveredEvent != null && hoveredEvent.sourceNode() == target) {
                hoveredEvent = null;
            }
            SELECTED_NODES.remove(target);
            if (selectionAnchorNode == target) {
                selectionAnchorNode = null;
            }

            if (remaining.isEmpty()) {
                setPath(entry.getKey(), null);
                return true;
            }

            List<NodeData> rebuilt = new ArrayList<>();
            for (int i = 0; i < remaining.size(); i++) {
                rebuilt.add(copyNode(remaining.get(i), i));
            }
            setPath(entry.getKey(), rebuilt);
            return true;
        }

        return false;
    }

    public static void clear() {
        if (PATHS.isEmpty()) {
            SimpleCameraManager.sendError("No paths are currently previewed to clear.");
            return;
        }
        PATHS.clear();
        TIMED_EVENTS.clear();
        activePathId = null;
        visible = false;
        hoveredNode = null;
        hoveredCurveHandleNode = null;
        hoveredCurveHandlePart = null;
        draggedNode = null;
        draggedCurveHandleNode = null;
        draggedCurveHandlePart = null;
        draggingSelectionGroup = false;
        groupDragOffsets.clear();
        clearSelection();
        dirty = false;
    }

    public static boolean isDirty() { return dirty; }
    public static void setDirty(boolean value) { dirty = value; }

    private static NodeData copyNode(NodeData source, int index) {
        NodeData copy;
        if (source.getDuration() != null) {
            copy = new NodeData(source.pos, source.yaw, source.pitch, source.roll, source.fov, index, source.easing, source.movement);
            copy.setDuration(source.getDuration());
            copy.setSpeed(source.getSpeed());
        } else {
            copy = new NodeData(source.pos, source.yaw, source.pitch, source.roll, source.fov, source.getSpeed(), index, source.pause, source.easing, source.movement);
        }

        copy.pause = source.pause;
        copy.events = source.events != null ? new ArrayList<>(source.events) : new ArrayList<>();
        copy.currentScale = source.currentScale;
        copy.lookAt = source.lookAt;
        copy.curveControl = source.curveControl;
        return copy;
    }

    public record EventNode(Vec3 pos, String name, String type, String extraInfo, NodeData sourceNode, String selectedEventName) {}

    public static void addTimedEventVisual(Vec3 pos, String name, String type, String extra) {
        TIMED_EVENTS.add(new EventNode(pos, name, type, extra, null, null));
    }

    public static void clearTimedEventVisuals() {
        TIMED_EVENTS.clear();
        if (hoveredEvent != null && hoveredEvent.sourceNode() == null) {
            hoveredEvent = null;
        }
    }

    private static EventNode hoveredEvent = null;

    public static void updateHover(Vec3 cameraPos, Vec3 lookVec) {
        boolean multiSelectMode = toolItemMode == ToolItemMode.MULTISELECT;

        if (draggedNode != null) {
            // Move node along the look vector, kept at a fixed distance or against blocks
            Vec3 newAnchorPos = cameraPos.add(lookVec.scale(dragDistance));
            if (draggingSelectionGroup && SELECTED_NODES.size() > 1) {
                for (NodeData selected : SELECTED_NODES) {
                    Vec3 offset = groupDragOffsets.getOrDefault(selected, Vec3.ZERO);
                    selected.setPos(newAnchorPos.add(offset));
                }
            } else {
                draggedNode.setPos(newAnchorPos);
            }
            dirty = true; // Mark dirty when dragging
            return;
        }
        if (draggedCurveHandleNode != null) {
            Vec3 dragPoint = cameraPos.add(lookVec.scale(dragDistance));
            Vec3 base = getCurveHandlePosition(draggedCurveHandleNode);
            double anchorY = getCurveHandleAnchorY(draggedCurveHandleNode);

            Vec3 nextPoint;
            if (draggedCurveHandlePart == CurveHandlePart.Y_POS) {
                nextPoint = new Vec3(base.x, Math.max(anchorY, dragPoint.y), base.z);
            } else if (draggedCurveHandlePart == CurveHandlePart.Y_NEG) {
                nextPoint = new Vec3(base.x, Math.min(anchorY, dragPoint.y), base.z);
            } else {
                // Main handle edits horizontal curvature only.
                nextPoint = new Vec3(dragPoint.x, base.y, dragPoint.z);
            }

            draggedCurveHandleNode.setCurveControl(nextPoint);
            dirty = true;
            return;
        }

        hoveredNode = null;
        hoveredCurveHandleNode = null;
        hoveredCurveHandlePart = null;
        hoveredEvent = null;
        if (!visible) return;

        double closestInteractionDist = 0.2;

        // 1. Check Timed Event Nodes (Floating green boxes along path)
        if (!multiSelectMode) {
            for (EventNode event : TIMED_EVENTS) {
                Vec3 toNode = event.pos.subtract(cameraPos);
                double projection = toNode.dot(lookVec);
                if (projection < 0) continue;
                Vec3 closestPoint = cameraPos.add(lookVec.scale(projection));
                if (closestPoint.distanceTo(event.pos) < 0.2 && projection < 10) {
                    hoveredEvent = event;
                    return;
                }
            }
        }

        for (Map.Entry<ResourceLocation, List<NodeData>> entry : PATHS.entrySet()) {
            List<NodeData> pathNodes = entry.getValue();

            // 2. Check curve handles for curved segments.
            if (!multiSelectMode) {
                for (int i = 1; i < pathNodes.size(); i++) {
                    NodeData endNode = pathNodes.get(i);
                    if (!"curved".equals(endNode.movement)) continue;

                    Vec3 mainPos = getCurveHandlePosition(endNode);
                    Vec3 posY = getCurveYPosHandlePosition(endNode);
                    Vec3 negY = getCurveYNegHandlePosition(endNode);

                    CurveHandlePart part = pickHoveredCurvePart(cameraPos, lookVec, mainPos, posY, negY);
                    if (part != null) {
                        hoveredCurveHandleNode = endNode;
                        hoveredCurveHandlePart = part;
                        activePathId = entry.getKey();
                        return;
                    }
                }
            }

            for (NodeData node : entry.getValue()) {
                // 2. Check Node-based Event Indicators
                if (!multiSelectMode && node.events != null && !node.events.isEmpty()) {
                    Vec3 eventVisualPos = node.pos.add(0, 0.5, 0);
                    Vec3 toEvent = eventVisualPos.subtract(cameraPos);
                    double proj = toEvent.dot(lookVec);
                    if (proj >= 0) {
                        Vec3 cp = cameraPos.add(lookVec.scale(proj));
                        if (cp.distanceTo(eventVisualPos) < 0.2 && proj < 10) {
                            if (node.events.size() > 1) {
                                String names = String.join(", ", node.events);
                                hoveredEvent = new EventNode(eventVisualPos, names, "Multiple", "", node, node.events.getFirst());
                            } else {
                                SimpleCameraManager.EventInfo info = SimpleCameraManager.getEventInfo(node.events.get(0));
                                hoveredEvent = new EventNode(eventVisualPos, node.events.get(0), info.type(), info.data(), node, node.events.get(0));
                            }
                            return;
                        }
                    }
                }

                // 3. Check standard Camera Nodes
                Vec3 toNode = node.pos.subtract(cameraPos);
                double projection = toNode.dot(lookVec);
                if (projection < 0) continue;

                Vec3 closestPoint = cameraPos.add(lookVec.scale(projection));
                double dist = closestPoint.distanceTo(node.pos);

                if (dist < closestInteractionDist && projection < 10) { // Within 10 blocks
                    hoveredNode = node;
                    activePathId = entry.getKey(); // Update active path on hover
                    break;
                }
            }
            if (hoveredNode != null) break;
        }
    }

    public static void handleRightClick() {
        if (hoveredEvent != null) {
            Minecraft mc = Minecraft.getInstance();
            if (mc.player != null) {
                // Check if it's a multi-event node
                if (hoveredEvent.name.contains(", ")) {
                    mc.player.displayClientMessage(Component.literal("§2[Multiple Events]"), false);
                    mc.player.displayClientMessage(Component.literal("§fNames: §7" + hoveredEvent.name), false);
                } else {
                    mc.player.displayClientMessage(Component.literal("§2[Event: " + hoveredEvent.name + "]"), false);
                    mc.player.displayClientMessage(Component.literal("§fType: §7" + hoveredEvent.type.toUpperCase()), false);
                    if (hoveredEvent.extraInfo != null && !hoveredEvent.extraInfo.isEmpty()) {
                        mc.player.displayClientMessage(Component.literal("§fData: §7" + hoveredEvent.extraInfo), false);
                    }
                }
            }
            return;
        }

        if (hoveredNode != null) {
            Minecraft mc = Minecraft.getInstance();
            if (mc.player != null) {
                // Find the path this node belongs to to check if it's the end node
                int pathSize = 0;
                for (List<NodeData> path : PATHS.values()) {
                    if (path.contains(hoveredNode)) {
                        pathSize = path.size();
                        break;
                    }
                }

                String type;
                if (hoveredNode.index == 0) {
                    type = "§6[Start Node]";
                } else if (pathSize > 0 && hoveredNode.index == pathSize - 1) {
                    type = "§c[End Node]";
                } else {
                    type = "§e[Point " + hoveredNode.index + "]";
                }
                mc.player.displayClientMessage(Component.literal(type), false);
                mc.player.displayClientMessage(Component.literal(String.format("§fPos: %.2f, %.2f, %.2f", hoveredNode.pos.x, hoveredNode.pos.y, hoveredNode.pos.z)), false);
                mc.player.displayClientMessage(Component.literal(String.format("§fRot: %.1f / %.1f / %.1f (Roll)", hoveredNode.yaw, hoveredNode.pitch, hoveredNode.roll)), false);
                mc.player.displayClientMessage(Component.literal(String.format("§fSpeed: %.2f", hoveredNode.speed)), false);
                mc.player.displayClientMessage(Component.literal(String.format("§fEasing: %s", hoveredNode.easing.name())), false);
                if (hoveredNode.pause > 0) {
                    mc.player.displayClientMessage(Component.literal(String.format("§fPause: %d ms", hoveredNode.pause)), false);
                }
            }
        }
    }

    public static void render(PoseStack poseStack) {
        if (!visible || PATHS.isEmpty()) return;

        Minecraft mc = Minecraft.getInstance();
        boolean multiSelectMode = toolItemMode == ToolItemMode.MULTISELECT;
        Vec3 cameraPos = mc.gameRenderer.getMainCamera().getPosition();
        updateHover(cameraPos, mc.player.getViewVector(1.0f));

        // Update scales for all nodes
        float frameTime = mc.getTimer().getGameTimeDeltaPartialTick(false);
        float scaleSpeed = 0.1f * frameTime;

        for (List<NodeData> path : PATHS.values()) {
            for (NodeData node : path) {
                float target = (node == hoveredNode) ? 1.5f : 1.0f;
                if (node.currentScale < target) {
                    node.currentScale = Math.min(target, node.currentScale + scaleSpeed);
                } else if (node.currentScale > target) {
                    node.currentScale = Math.max(target, node.currentScale - scaleSpeed);
                }
            }
        }

        poseStack.pushPose();
        poseStack.translate(-cameraPos.x, -cameraPos.y, -cameraPos.z);
        Matrix4f matrix = poseStack.last().pose();

        RenderSystem.enableDepthTest();
        RenderSystem.setShader(GameRenderer::getPositionColorShader);
        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        RenderSystem.disableCull();
        RenderSystem.depthMask(false); // Disable depth writing for transparency

        Tesselator tesselator = Tesselator.getInstance();

        // Render Timed Events
        if (!multiSelectMode) {
            for (EventNode event : TIMED_EVENTS) {
                float size = (event == hoveredEvent) ? 0.15f : 0.08f;
                drawBox(matrix, event.pos, size, 0, 255, 100, 200); // Bright Green

                // Draw a thicker connector bundle so timed markers remain visible against bright terrain.
                float x = (float) event.pos.x;
                float y = (float) event.pos.y;
                float z = (float) event.pos.z;
                float bottomY = y - 0.5f;
                float offset = 0.015f;

                Tesselator lineTess = Tesselator.getInstance();
                BufferBuilder line = lineTess.begin(VertexFormat.Mode.DEBUG_LINES, DefaultVertexFormat.POSITION_COLOR);
                line.addVertex(matrix, x, y, z).setColor(0, 255, 100, 190);
                line.addVertex(matrix, x, bottomY, z).setColor(0, 255, 100, 190);

                line.addVertex(matrix, x + offset, y, z).setColor(0, 200, 70, 150);
                line.addVertex(matrix, x + offset, bottomY, z).setColor(0, 200, 70, 150);

                line.addVertex(matrix, x - offset, y, z).setColor(0, 200, 70, 150);
                line.addVertex(matrix, x - offset, bottomY, z).setColor(0, 200, 70, 150);
                BufferUploader.drawWithShader(line.buildOrThrow());
            }
        }

        for (List<NodeData> pathNodes : PATHS.values()) {
            // Draw Lines/Curves (only if we have at least 2 nodes)
            if (pathNodes.size() >= 2) {
                BufferBuilder buffer = tesselator.begin(VertexFormat.Mode.DEBUG_LINES, DefaultVertexFormat.POSITION_COLOR);
                List<NodeData> curveHandlesToDraw = new ArrayList<>();
                for (int i = 0; i < pathNodes.size() - 1; i++) {
                    NodeData startNode = pathNodes.get(i);
                    NodeData endNode = pathNodes.get(i + 1);

                    if ("curved".equals(endNode.movement) && pathNodes.size() > 2) {
                        renderCurve(matrix, buffer, pathNodes, i);
                        if (!multiSelectMode) {
                            curveHandlesToDraw.add(endNode);
                        }

                        Vec3 handlePos = getCurveHandlePosition(endNode);

                        buffer.addVertex(matrix, (float) startNode.pos.x, (float) startNode.pos.y, (float) startNode.pos.z).setColor(80, 170, 255, 170);
                        buffer.addVertex(matrix, (float) handlePos.x, (float) handlePos.y, (float) handlePos.z).setColor(80, 170, 255, 170);
                        buffer.addVertex(matrix, (float) handlePos.x, (float) handlePos.y, (float) handlePos.z).setColor(80, 170, 255, 170);
                        buffer.addVertex(matrix, (float) endNode.pos.x, (float) endNode.pos.y, (float) endNode.pos.z).setColor(80, 170, 255, 170);
                    } else {
                        buffer.addVertex(matrix, (float)startNode.pos.x, (float)startNode.pos.y, (float)startNode.pos.z).setColor(255, 255, 0, 255);
                        buffer.addVertex(matrix, (float)endNode.pos.x, (float)endNode.pos.y, (float)endNode.pos.z).setColor(255, 255, 0, 255);
                    }
                }
                BufferUploader.drawWithShader(buffer.buildOrThrow());

                // Draw handle cubes after line upload; nested tesselator starts can break active line buffers.
                for (NodeData endNode : curveHandlesToDraw) {
                    Vec3 mainPos = getCurveHandlePosition(endNode);
                    Vec3 posY = getCurveYPosHandlePosition(endNode);
                    Vec3 negY = getCurveYNegHandlePosition(endNode);

                    boolean hoveredMain = hoveredCurveHandleNode == endNode && hoveredCurveHandlePart == CurveHandlePart.MAIN;
                    boolean hoveredPosY = hoveredCurveHandleNode == endNode && hoveredCurveHandlePart == CurveHandlePart.Y_POS;
                    boolean hoveredNegY = hoveredCurveHandleNode == endNode && hoveredCurveHandlePart == CurveHandlePart.Y_NEG;

                    drawBox(matrix, mainPos, hoveredMain ? 0.10f : 0.07f, 80, 170, 255, 220);
                    drawBox(matrix, posY, hoveredPosY ? 0.085f : 0.06f, 120, 255, 170, 220);
                    drawBox(matrix, negY, hoveredNegY ? 0.085f : 0.06f, 120, 255, 170, 220);

                    BufferBuilder yGuide = tesselator.begin(VertexFormat.Mode.DEBUG_LINES, DefaultVertexFormat.POSITION_COLOR);
                    yGuide.addVertex(matrix, (float) mainPos.x, (float) mainPos.y, (float) mainPos.z).setColor(120, 255, 170, 170);
                    yGuide.addVertex(matrix, (float) posY.x, (float) posY.y, (float) posY.z).setColor(120, 255, 170, 170);
                    yGuide.addVertex(matrix, (float) mainPos.x, (float) mainPos.y, (float) mainPos.z).setColor(120, 255, 170, 170);
                    yGuide.addVertex(matrix, (float) negY.x, (float) negY.y, (float) negY.z).setColor(120, 255, 170, 170);
                    BufferUploader.drawWithShader(yGuide.buildOrThrow());
                }
            }

            // Draw Nodes
            for (int i = 0; i < pathNodes.size(); i++) {
                NodeData node = pathNodes.get(i);

                // Draw Event icon above node if assigned
                if (!multiSelectMode && node.events != null && !node.events.isEmpty()) {
                    Vec3 eventVisualPos = node.pos.add(0, 0.5, 0);
                    boolean isHovered = hoveredEvent != null && hoveredEvent.pos.distanceToSqr(eventVisualPos) < 0.01;
                    float size = isHovered ? 0.15f : 0.08f;
                    // Color code: Purple for multiple, Green for single
                    int r = 0, g = 255, b = 100;
                    if (node.events.size() > 1) { r = 200; g = 50; b = 255; }

                    drawBox(matrix, eventVisualPos, size, r, g, b, 255);
                }

                // Draw LookAt Target if exists

                boolean isStart = (i == 0);
            boolean isEnd = (i == pathNodes.size() - 1);

            float baseSize = isStart ? 0.2f : 0.1f;
            float finalSize = baseSize * node.currentScale; // Apply smooth scale

            int r, g, b;
            if (isStart) {
                r = 0; g = 255; b = 0; // Green
            } else if (isEnd) {
                r = 255; g = 0; b = 0; // Red
            } else {
                r = 255; g = 255; b = 255; // Yellow
            }

                int a = (node == hoveredNode) ? 200 : 150;
                if (SELECTED_NODES.contains(node)) {
                    a = Math.max(a, 235);
                    if (!isStart && !isEnd) {
                        r = 80;
                        g = 170;
                        b = 255;
                    }
                }
                drawBox(matrix, node.pos, finalSize, r, g, b, a);
            }
        }

        if (SimpleCameraManager.isPreviewPlaybackActive() && SimpleCameraManager.getCamera() != null) {
            Vec3 pathPos = SimpleCameraManager.getCamera().position();
            Vec3 eyePos = SimpleCameraManager.getCamera().getEyePosition(frameTime);
            Vec3 forward = Vec3.directionFromRotation(SimpleCameraManager.getPitch(), SimpleCameraManager.getYaw()).normalize();

            // Draw the solid prop camera with depth writes so body/lens self-occlude correctly.
            RenderSystem.depthMask(true);
            RenderSystem.disableBlend();
            drawPreviewCameraProp(matrix, eyePos, SimpleCameraManager.getYaw(), SimpleCameraManager.getPitch(), SimpleCameraManager.getRoll());
            drawPropDropLine(matrix, eyePos, pathPos);
            if (Config.SHOW_PROP_CAMERA_FRUSTUM.get()) {
                drawPreviewFrustum(matrix, eyePos, forward, SimpleCameraManager.getFov());
            }
        }

        RenderSystem.depthMask(true);
        RenderSystem.disableBlend();
        RenderSystem.enableCull();
        poseStack.popPose();
    }

    private static void renderCurve(Matrix4f matrix, BufferBuilder buffer, List<NodeData> nodes, int startIndex) {
        int steps = 20;
        Vec3 p0 = nodes.get(Math.max(0, startIndex - 1)).pos;
        Vec3 p1 = nodes.get(startIndex).pos;
        Vec3 p2 = nodes.get(startIndex + 1).pos;
        Vec3 p3 = nodes.get(Math.min(nodes.size() - 1, startIndex + 2)).pos;
        Vec3 control = nodes.get(startIndex + 1).curveControl;

        Vec3 lastPos = p1;
        for (int i = 1; i <= steps; i++) {
            float t = i / (float) steps;
            Vec3 currentPos = calculateCurve(p0, p1, p2, p3, control, t);
            buffer.addVertex(matrix, (float)lastPos.x, (float)lastPos.y, (float)lastPos.z).setColor(255, 255, 255, 255);
            buffer.addVertex(matrix, (float)currentPos.x, (float)currentPos.y, (float)currentPos.z).setColor(255, 255, 255, 255);
            lastPos = currentPos;
        }
    }

    public static Vec3 calculateCurve(Vec3 p0, Vec3 p1, Vec3 p2, Vec3 p3, Vec3 control, float t) {
        if (control != null) {
            double s = Math.max(0.0, Math.min(1.0, t));
            double oneMinus = 1.0 - s;
            // Quadratic Bezier with an explicit in-world handle.
            return p1.scale(oneMinus * oneMinus)
                    .add(control.scale(2.0 * oneMinus * s))
                    .add(p2.scale(s * s));
        }
        return calculateCatmullRom(p0, p1, p2, p3, t);
    }

    public static Vec3 getCurveHandlePosition(NodeData endNode) {
        if (endNode.curveControl != null) {
            return endNode.curveControl;
        }
        List<NodeData> path = findPathForNode(endNode);
        if (path == null) {
            return endNode.pos;
        }
        int index = path.indexOf(endNode);
        if (index <= 0) {
            return endNode.pos;
        }
        Vec3 start = path.get(index - 1).pos;
        return start.lerp(endNode.pos, 0.5);
    }

    private static Vec3 getCurveYPosHandlePosition(NodeData endNode) {
        Vec3 main = getCurveHandlePosition(endNode);
        double anchorY = getCurveHandleAnchorY(endNode);
        double y = Math.max(main.y, anchorY) + CURVE_Y_HANDLE_OFFSET;
        return new Vec3(main.x, y, main.z);
    }

    private static Vec3 getCurveYNegHandlePosition(NodeData endNode) {
        Vec3 main = getCurveHandlePosition(endNode);
        double anchorY = getCurveHandleAnchorY(endNode);
        double y = Math.min(main.y, anchorY) - CURVE_Y_HANDLE_OFFSET;
        return new Vec3(main.x, y, main.z);
    }

    private static double getCurveHandleAnchorY(NodeData endNode) {
        List<NodeData> path = findPathForNode(endNode);
        if (path == null) return endNode.pos.y;
        int index = path.indexOf(endNode);
        if (index <= 0) return endNode.pos.y;
        return (path.get(index - 1).pos.y + endNode.pos.y) * 0.5;
    }

    private static CurveHandlePart pickHoveredCurvePart(Vec3 cameraPos, Vec3 lookVec, Vec3 mainPos, Vec3 posY, Vec3 negY) {
        double dMain = projectedRayDistance(cameraPos, lookVec, mainPos);
        double dPosY = projectedRayDistance(cameraPos, lookVec, posY);
        double dNegY = projectedRayDistance(cameraPos, lookVec, negY);

        CurveHandlePart best = null;
        double bestDist = Double.MAX_VALUE;
        if (dMain >= 0 && dMain < 0.2 && dMain < bestDist) {
            best = CurveHandlePart.MAIN;
            bestDist = dMain;
        }
        if (dPosY >= 0 && dPosY < 0.18 && dPosY < bestDist) {
            best = CurveHandlePart.Y_POS;
            bestDist = dPosY;
        }
        if (dNegY >= 0 && dNegY < 0.18 && dNegY < bestDist) {
            best = CurveHandlePart.Y_NEG;
        }
        return best;
    }

    private static double projectedRayDistance(Vec3 cameraPos, Vec3 lookVec, Vec3 target) {
        Vec3 to = target.subtract(cameraPos);
        double projection = to.dot(lookVec);
        if (projection < 0 || projection > 10) {
            return -1;
        }
        Vec3 closestPoint = cameraPos.add(lookVec.scale(projection));
        return closestPoint.distanceTo(target);
    }

    private static List<NodeData> findPathForNode(NodeData target) {
        for (List<NodeData> path : PATHS.values()) {
            if (path.contains(target)) {
                return path;
            }
        }
        return null;
    }

    private static void validateSelectionState() {
        SELECTED_NODES.removeIf(node -> PATHS.values().stream().noneMatch(path -> path.contains(node)));
        if (selectionAnchorNode != null && PATHS.values().stream().noneMatch(path -> path.contains(selectionAnchorNode))) {
            selectionAnchorNode = null;
        }
        if (SELECTED_NODES.isEmpty() && selectionAnchorNode == null) {
            draggingSelectionGroup = false;
            groupDragOffsets.clear();
        }
    }

    // ...existing code...

    public static Vec3 calculateCatmullRom(Vec3 p0, Vec3 p1, Vec3 p2, Vec3 p3, float t) {
        // Centripetal Catmull-Rom (alpha=0.5) avoids many loops/overshoots with sparse points.
        final double alpha = 0.5;
        final double epsilon = 1.0E-6;
        double s = Math.max(0.0, Math.min(1.0, t));

        double t0 = 0.0;
        double t1 = t0 + chordParameter(p0, p1, alpha, epsilon);
        double t2 = t1 + chordParameter(p1, p2, alpha, epsilon);
        double t3 = t2 + chordParameter(p2, p3, alpha, epsilon);

        if (Math.abs(t2 - t1) < epsilon) {
            return p1.lerp(p2, s);
        }

        double u = t1 + (t2 - t1) * s;

        Vec3 a1 = interpolateByTime(p0, p1, t0, t1, u, epsilon);
        Vec3 a2 = interpolateByTime(p1, p2, t1, t2, u, epsilon);
        Vec3 a3 = interpolateByTime(p2, p3, t2, t3, u, epsilon);

        Vec3 b1 = interpolateByTime(a1, a2, t0, t2, u, epsilon);
        Vec3 b2 = interpolateByTime(a2, a3, t1, t3, u, epsilon);

        return interpolateByTime(b1, b2, t1, t2, u, epsilon);
    }

    private static double chordParameter(Vec3 a, Vec3 b, double alpha, double epsilon) {
        double distance = a.distanceTo(b);
        if (distance < epsilon) {
            distance = epsilon;
        }
        return Math.pow(distance, alpha);
    }

    private static Vec3 interpolateByTime(Vec3 a, Vec3 b, double ta, double tb, double t, double epsilon) {
        double span = tb - ta;
        if (Math.abs(span) < epsilon) {
            return a;
        }
        double amount = Math.max(0.0, Math.min(1.0, (t - ta) / span));
        return a.lerp(b, amount);
    }

    private static void drawPreviewCameraProp(Matrix4f matrix, Vec3 center, float yaw, float pitch, float roll) {
        Vec3 forward = Vec3.directionFromRotation(pitch, yaw).normalize();
        Vec3 referenceUp = Math.abs(forward.y) > 0.999 ? new Vec3(0.0, 0.0, 1.0) : new Vec3(0.0, 1.0, 0.0);
        Vec3 right = forward.cross(referenceUp).normalize();
        Vec3 up = right.cross(forward).normalize();

        if (Math.abs(roll) > 0.001f) {
            right = rotateAroundAxis(right, forward, roll).normalize();
            up = rotateAroundAxis(up, forward, roll).normalize();
        }

        Vec3 bodyCenter = center.subtract(forward.scale(0.03));
        Vec3 bodyRight = right.scale(0.14);
        Vec3 bodyUp = up.scale(0.11);
        Vec3 bodyForward = forward.scale(0.12);
        Vec3 coneBaseCenter = bodyCenter.add(bodyForward).add(forward.scale(0.15));
        Vec3 coneRight = right.scale(0.07);
        Vec3 coneUp = up.scale(0.06);
        Vec3 coneForward = forward.scale(0.16);

        Tesselator tesselator = Tesselator.getInstance();
        BufferBuilder buffer = tesselator.begin(VertexFormat.Mode.TRIANGLES, DefaultVertexFormat.POSITION_COLOR);

        addOrientedBox(buffer, matrix, bodyCenter, bodyRight, bodyUp, bodyForward, 58, 58, 62, 255);
        addOrientedPyramid(buffer, matrix, coneBaseCenter, coneRight, coneUp, coneForward, 36, 36, 40, 255);

        BufferUploader.drawWithShader(buffer.buildOrThrow());
    }

    private static Vec3 rotateAroundAxis(Vec3 vector, Vec3 axis, float degrees) {
        double radians = Math.toRadians(degrees);
        double cos = Math.cos(radians);
        double sin = Math.sin(radians);
        Vec3 normalizedAxis = axis.normalize();

        Vec3 term1 = vector.scale(cos);
        Vec3 term2 = normalizedAxis.cross(vector).scale(sin);
        Vec3 term3 = normalizedAxis.scale(normalizedAxis.dot(vector) * (1.0 - cos));
        return term1.add(term2).add(term3);
    }

    private static void addOrientedBox(BufferBuilder buffer, Matrix4f matrix, Vec3 center, Vec3 right, Vec3 up, Vec3 forward, int r, int g, int b, int a) {
        Vec3 nnn = center.subtract(right).subtract(up).subtract(forward);
        Vec3 nnp = center.subtract(right).subtract(up).add(forward);
        Vec3 npn = center.subtract(right).add(up).subtract(forward);
        Vec3 npp = center.subtract(right).add(up).add(forward);
        Vec3 pnn = center.add(right).subtract(up).subtract(forward);
        Vec3 pnp = center.add(right).subtract(up).add(forward);
        Vec3 ppn = center.add(right).add(up).subtract(forward);
        Vec3 ppp = center.add(right).add(up).add(forward);

        addQuad(buffer, matrix, nnn, pnn, ppn, npn, r, g, b, a);
        addQuad(buffer, matrix, pnp, nnp, npp, ppp, r, g, b, a);
        addQuad(buffer, matrix, nnp, nnn, npn, npp, r, g, b, a);
        addQuad(buffer, matrix, pnn, pnp, ppp, ppn, r, g, b, a);
        addQuad(buffer, matrix, npn, ppn, ppp, npp, r, g, b, a);
        addQuad(buffer, matrix, nnn, nnp, pnp, pnn, r, g, b, a);
    }

    private static void addOrientedPyramid(BufferBuilder buffer, Matrix4f matrix, Vec3 baseCenter, Vec3 right, Vec3 up, Vec3 forward, int r, int g, int b, int a) {
        Vec3 b1 = baseCenter.subtract(right).subtract(up);
        Vec3 b2 = baseCenter.add(right).subtract(up);
        Vec3 b3 = baseCenter.add(right).add(up);
        Vec3 b4 = baseCenter.subtract(right).add(up);
        // Lens cone points outward from the camera body.
        Vec3 apex = baseCenter.subtract(forward);

        addQuad(buffer, matrix, b1, b2, b3, b4, r, g, b, a);
        addTriangle(buffer, matrix, b1, b2, apex, r, g, b, a);
        addTriangle(buffer, matrix, b2, b3, apex, r, g, b, a);
        addTriangle(buffer, matrix, b3, b4, apex, r, g, b, a);
        addTriangle(buffer, matrix, b4, b1, apex, r, g, b, a);
    }

    private static void addQuad(BufferBuilder buffer, Matrix4f matrix, Vec3 aPos, Vec3 bPos, Vec3 cPos, Vec3 dPos, int r, int g, int b, int a) {
        addTriangle(buffer, matrix, aPos, bPos, cPos, r, g, b, a);
        addTriangle(buffer, matrix, aPos, cPos, dPos, r, g, b, a);
    }

    private static void addTriangle(BufferBuilder buffer, Matrix4f matrix, Vec3 aPos, Vec3 bPos, Vec3 cPos, int r, int g, int b, int a) {
        buffer.addVertex(matrix, (float) aPos.x, (float) aPos.y, (float) aPos.z).setColor(r, g, b, a);
        buffer.addVertex(matrix, (float) bPos.x, (float) bPos.y, (float) bPos.z).setColor(r, g, b, a);
        buffer.addVertex(matrix, (float) cPos.x, (float) cPos.y, (float) cPos.z).setColor(r, g, b, a);
    }

    private static void drawPropDropLine(Matrix4f matrix, Vec3 from, Vec3 to) {
        Tesselator tesselator = Tesselator.getInstance();
        BufferBuilder line = tesselator.begin(VertexFormat.Mode.DEBUG_LINES, DefaultVertexFormat.POSITION_COLOR);
        line.addVertex(matrix, (float) from.x, (float) from.y, (float) from.z).setColor(180, 180, 180, 255);
        line.addVertex(matrix, (float) to.x, (float) to.y, (float) to.z).setColor(90, 90, 90, 255);
        BufferUploader.drawWithShader(line.buildOrThrow());
    }

    private static void drawPreviewFrustum(Matrix4f matrix, Vec3 origin, Vec3 forward, float zoomScale) {
        double maxRange = 8.0;
        double baseFovDeg = 70.0;
        double approxFovDeg = 5.0 + (baseFovDeg - 5.0) * Math.max(0.0, Math.min(1.0, zoomScale));
        double halfWidth = Math.tan(Math.toRadians(approxFovDeg * 0.5)) * maxRange;
        double halfHeight = halfWidth * 0.56; // Rough 16:9 framing

        Vec3 refUp = Math.abs(forward.y) > 0.999 ? new Vec3(0.0, 0.0, 1.0) : new Vec3(0.0, 1.0, 0.0);
        Vec3 right = forward.cross(refUp).normalize();
        Vec3 up = right.cross(forward).normalize();
        Vec3 farCenter = origin.add(forward.scale(maxRange));

        Vec3 topLeft = farCenter.add(up.scale(halfHeight)).subtract(right.scale(halfWidth));
        Vec3 topRight = farCenter.add(up.scale(halfHeight)).add(right.scale(halfWidth));
        Vec3 bottomLeft = farCenter.subtract(up.scale(halfHeight)).subtract(right.scale(halfWidth));
        Vec3 bottomRight = farCenter.subtract(up.scale(halfHeight)).add(right.scale(halfWidth));

        // Very transparent filled bounds to make frustum volume easier to read.
        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        RenderSystem.depthMask(false);

        Tesselator faceTess = Tesselator.getInstance();
        BufferBuilder faces = faceTess.begin(VertexFormat.Mode.TRIANGLES, DefaultVertexFormat.POSITION_COLOR);
        int faceA = 28;
        addTriangle(faces, matrix, origin, topLeft, topRight, 255, 255, 255, faceA);
        addTriangle(faces, matrix, origin, topRight, bottomRight, 255, 255, 255, faceA);
        addTriangle(faces, matrix, origin, bottomRight, bottomLeft, 255, 255, 255, faceA);
        addTriangle(faces, matrix, origin, bottomLeft, topLeft, 255, 255, 255, faceA);
        addQuad(faces, matrix, topLeft, topRight, bottomRight, bottomLeft, 255, 255, 255, 20);
        BufferUploader.drawWithShader(faces.buildOrThrow());

        RenderSystem.depthMask(true);
        RenderSystem.disableBlend();

        Tesselator tesselator = Tesselator.getInstance();
        BufferBuilder lines = tesselator.begin(VertexFormat.Mode.DEBUG_LINES, DefaultVertexFormat.POSITION_COLOR);
        addLine(lines, matrix, origin, topLeft, 255, 220, 80, 200);
        addLine(lines, matrix, origin, topRight, 255, 220, 80, 200);
        addLine(lines, matrix, origin, bottomLeft, 255, 220, 80, 200);
        addLine(lines, matrix, origin, bottomRight, 255, 220, 80, 200);

        addLine(lines, matrix, topLeft, topRight, 255, 180, 60, 180);
        addLine(lines, matrix, topRight, bottomRight, 255, 180, 60, 180);
        addLine(lines, matrix, bottomRight, bottomLeft, 255, 180, 60, 180);
        addLine(lines, matrix, bottomLeft, topLeft, 255, 180, 60, 180);
        BufferUploader.drawWithShader(lines.buildOrThrow());
    }

    private static void addLine(BufferBuilder buffer, Matrix4f matrix, Vec3 from, Vec3 to, int r, int g, int b, int a) {
        buffer.addVertex(matrix, (float) from.x, (float) from.y, (float) from.z).setColor(r, g, b, a);
        buffer.addVertex(matrix, (float) to.x, (float) to.y, (float) to.z).setColor(r, g, b, a);
    }

    private static void drawBox(Matrix4f matrix, Vec3 p, float size, int r, int g, int b, int a) {
        float x = (float)p.x, y = (float)p.y, z = (float)p.z;
        float minX = x - size, maxX = x + size;
        float minY = y - size, maxY = y + size;
        float minZ = z - size, maxZ = z + size;

        Tesselator tesselator = Tesselator.getInstance();
        BufferBuilder buffer = tesselator.begin(VertexFormat.Mode.TRIANGLES, DefaultVertexFormat.POSITION_COLOR);

        addQuad(buffer, matrix, minX, maxX, minY, maxY, minZ, maxZ, r, g, b, a);

        BufferUploader.drawWithShader(buffer.buildOrThrow());
    }

    private static void addQuad(BufferBuilder buffer, Matrix4f matrix, float minX, float maxX, float minY, float maxY, float minZ, float maxZ, int r, int g, int b, int a) {
        // Back
        buffer.addVertex(matrix, minX, minY, minZ).setColor(r, g, b, a);
        buffer.addVertex(matrix, minX, maxY, minZ).setColor(r, g, b, a);
        buffer.addVertex(matrix, maxX, maxY, minZ).setColor(r, g, b, a);
        buffer.addVertex(matrix, minX, minY, minZ).setColor(r, g, b, a);
        buffer.addVertex(matrix, maxX, maxY, minZ).setColor(r, g, b, a);
        buffer.addVertex(matrix, maxX, minY, minZ).setColor(r, g, b, a);
        // Front
        buffer.addVertex(matrix, minX, minY, maxZ).setColor(r, g, b, a);
        buffer.addVertex(matrix, maxX, minY, maxZ).setColor(r, g, b, a);
        buffer.addVertex(matrix, maxX, maxY, maxZ).setColor(r, g, b, a);
        buffer.addVertex(matrix, minX, minY, maxZ).setColor(r, g, b, a);
        buffer.addVertex(matrix, maxX, maxY, maxZ).setColor(r, g, b, a);
        buffer.addVertex(matrix, minX, maxY, maxZ).setColor(r, g, b, a);
        // Left
        buffer.addVertex(matrix, minX, minY, minZ).setColor(r, g, b, a);
        buffer.addVertex(matrix, minX, maxY, minZ).setColor(r, g, b, a);
        buffer.addVertex(matrix, minX, maxY, maxZ).setColor(r, g, b, a);
        buffer.addVertex(matrix, minX, minY, minZ).setColor(r, g, b, a);
        buffer.addVertex(matrix, minX, maxY, maxZ).setColor(r, g, b, a);
        buffer.addVertex(matrix, minX, minY, maxZ).setColor(r, g, b, a);
        // Right
        buffer.addVertex(matrix, maxX, minY, minZ).setColor(r, g, b, a);
        buffer.addVertex(matrix, maxX, minY, maxZ).setColor(r, g, b, a);
        buffer.addVertex(matrix, maxX, maxY, maxZ).setColor(r, g, b, a);
        buffer.addVertex(matrix, maxX, minY, minZ).setColor(r, g, b, a);
        buffer.addVertex(matrix, maxX, maxY, maxZ).setColor(r, g, b, a);
        buffer.addVertex(matrix, maxX, maxY, minZ).setColor(r, g, b, a);
        // Bottom
        buffer.addVertex(matrix, minX, minY, minZ).setColor(r, g, b, a);
        buffer.addVertex(matrix, minX, minY, maxZ).setColor(r, g, b, a);
        buffer.addVertex(matrix, maxX, minY, maxZ).setColor(r, g, b, a);
        buffer.addVertex(matrix, minX, minY, minZ).setColor(r, g, b, a);
        buffer.addVertex(matrix, maxX, minY, maxZ).setColor(r, g, b, a);
        buffer.addVertex(matrix, maxX, minY, minZ).setColor(r, g, b, a);
        // Top
        buffer.addVertex(matrix, minX, maxY, minZ).setColor(r, g, b, a);
        buffer.addVertex(matrix, maxX, maxY, minZ).setColor(r, g, b, a);
        buffer.addVertex(matrix, maxX, maxY, maxZ).setColor(r, g, b, a);
        buffer.addVertex(matrix, minX, maxY, minZ).setColor(r, g, b, a);
        buffer.addVertex(matrix, maxX, maxY, maxZ).setColor(r, g, b, a);
        buffer.addVertex(matrix, minX, maxY, maxZ).setColor(r, g, b, a);
    }
}
