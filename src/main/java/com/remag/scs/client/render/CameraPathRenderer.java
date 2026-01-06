package com.remag.scs.client.render;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.*;
import com.remag.scs.client.camera.SimpleCameraManager;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class CameraPathRenderer {
    private static final Map<ResourceLocation, List<NodeData>> PATHS = new LinkedHashMap<>();
    private static final List<EventNode> TIMED_EVENTS = new ArrayList<>(); // Store timed event visuals
    private static ResourceLocation activePathId = null;
    private static boolean visible = false;
    private static boolean dirty = false; // New dirty flag
    private static NodeData hoveredNode = null;
    private static NodeData draggedNode = null;

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
        public float roll;
        public float fov;
        public NodeData(Vec3 pos, float yaw, float pitch, float roll, float fov, double speed, int index, long pause, SimpleCameraManager.EasingType easing, String movement) {
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
        }

        // Add setters since we'll be editing these
        public Vec3 pos;
        public double speed;
        public float yaw;
        public float pitch;
        public void setPos(Vec3 pos) { this.pos = pos; }
        public void setSpeed(double speed) { this.speed = speed; }
        public void setRotation(float yaw, float pitch, float roll, float fov) {
            this.yaw = yaw; this.pitch = pitch; this.roll = roll; this.fov = fov;
        }
        public void setLookAt(Vec3 lookAt) { this.lookAt = lookAt; }
    }

    public static void startDragging() {
        if (hoveredNode != null) {
            draggedNode = hoveredNode;
        }
    }

    public static void stopDragging() {
        draggedNode = null;
    }

    public static void setPath(ResourceLocation id, List<NodeData> nodes) {
        if (nodes == null || nodes.isEmpty()) {
            PATHS.remove(id);
            if (id.equals(activePathId)) activePathId = null;
        } else {
            PATHS.put(id, new ArrayList<>(nodes));
            activePathId = id;
        }
        visible = !PATHS.isEmpty();
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
        dirty = false;
    }

    public static boolean isDirty() { return dirty; }
    public static void setDirty(boolean value) { dirty = value; }

    public record EventNode(Vec3 pos, String name, String type, String extraInfo) {}

    public static void addTimedEventVisual(Vec3 pos, String name, String type, String extra) {
        TIMED_EVENTS.add(new EventNode(pos, name, type, extra));
    }

    private static EventNode hoveredEvent = null;

    public static void updateHover(Vec3 cameraPos, Vec3 lookVec) {
        if (draggedNode != null) {
            // Move node along the look vector, kept at a fixed distance or against blocks
            Minecraft mc = Minecraft.getInstance();
            double dist = 5.0; // Distance from player
            draggedNode.setPos(cameraPos.add(lookVec.scale(dist)));
            dirty = true; // Mark dirty when dragging
            return;
        }

        hoveredNode = null;
        hoveredEvent = null;
        if (!visible) return;

        double closestInteractionDist = 0.2;

        // 1. Check Timed Event Nodes (Floating green boxes along path)
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

        for (Map.Entry<ResourceLocation, List<NodeData>> entry : PATHS.entrySet()) {
            for (NodeData node : entry.getValue()) {
                // 2. Check Node-based Event Indicators
                if (node.events != null && !node.events.isEmpty()) {
                    Vec3 eventVisualPos = node.pos.add(0, 0.5, 0);
                    Vec3 toEvent = eventVisualPos.subtract(cameraPos);
                    double proj = toEvent.dot(lookVec);
                    if (proj >= 0) {
                        Vec3 cp = cameraPos.add(lookVec.scale(proj));
                        if (cp.distanceTo(eventVisualPos) < 0.2 && proj < 10) {
                            if (node.events.size() > 1) {
                                String names = String.join(", ", node.events);
                                hoveredEvent = new EventNode(eventVisualPos, names, "Multiple", "");
                            } else {
                                SimpleCameraManager.EventInfo info = SimpleCameraManager.getEventInfo(node.events.get(0));
                                hoveredEvent = new EventNode(eventVisualPos, node.events.get(0), info.type(), info.data());
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
        for (EventNode event : TIMED_EVENTS) {
            float size = (event == hoveredEvent) ? 0.15f : 0.08f;
            drawBox(matrix, event.pos, size, 0, 255, 100, 200); // Bright Green
            
            // Faint vertical line to path
            Tesselator lineTess = Tesselator.getInstance();
            BufferBuilder line = lineTess.begin(VertexFormat.Mode.DEBUG_LINES, DefaultVertexFormat.POSITION_COLOR);
            line.addVertex(matrix, (float)event.pos.x, (float)event.pos.y, (float)event.pos.z).setColor(0, 255, 100, 50);
            line.addVertex(matrix, (float)event.pos.x, (float)event.pos.y - 0.5f, (float)event.pos.z).setColor(0, 255, 100, 50);
            BufferUploader.drawWithShader(line.buildOrThrow());
        }

        for (List<NodeData> pathNodes : PATHS.values()) {
            BufferBuilder buffer = tesselator.begin(VertexFormat.Mode.DEBUG_LINES, DefaultVertexFormat.POSITION_COLOR);
            // Draw Lines/Curves
            for (int i = 0; i < pathNodes.size() - 1; i++) {
                NodeData startNode = pathNodes.get(i);
                NodeData endNode = pathNodes.get(i + 1);

                if ("curved".equals(endNode.movement) && pathNodes.size() > 2) {
                    renderCurve(matrix, buffer, pathNodes, i);
                } else {
                    buffer.addVertex(matrix, (float)startNode.pos.x, (float)startNode.pos.y, (float)startNode.pos.z).setColor(255, 255, 0, 255);
                    buffer.addVertex(matrix, (float)endNode.pos.x, (float)endNode.pos.y, (float)endNode.pos.z).setColor(255, 255, 0, 255);
                }
            }
            BufferUploader.drawWithShader(buffer.buildOrThrow());

            // Draw Nodes
            for (int i = 0; i < pathNodes.size(); i++) {
                NodeData node = pathNodes.get(i);
                
                // Draw Event icon above node if assigned
                if (node.events != null && !node.events.isEmpty()) {
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
                drawBox(matrix, node.pos, finalSize, r, g, b, a);
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

        Vec3 lastPos = p1;
        for (int i = 1; i <= steps; i++) {
            float t = i / (float) steps;
            Vec3 currentPos = calculateCatmullRom(p0, p1, p2, p3, t);
            buffer.addVertex(matrix, (float)lastPos.x, (float)lastPos.y, (float)lastPos.z).setColor(255, 255, 255, 255);
            buffer.addVertex(matrix, (float)currentPos.x, (float)currentPos.y, (float)currentPos.z).setColor(255, 255, 255, 255);
            lastPos = currentPos;
        }
    }

    public static Vec3 calculateCatmullRom(Vec3 p0, Vec3 p1, Vec3 p2, Vec3 p3, float t) {
        float t2 = t * t;
        float t3 = t2 * t;
        return p1.scale(2.0)
                .add(p2.subtract(p0).scale(t))
                .add(p0.scale(2.0).subtract(p1.scale(5.0)).add(p2.scale(4.0)).subtract(p3).scale(t2))
                .add(p1.scale(3.0).subtract(p0).subtract(p2.scale(3.0)).add(p3).scale(t3))
                .scale(0.5);
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
