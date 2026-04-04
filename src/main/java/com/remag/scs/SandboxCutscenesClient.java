package com.remag.scs;

import com.remag.scs.client.render.CameraPathRenderer;
import com.remag.scs.client.camera.SimpleCameraManager;
import com.remag.scs.client.render.EventEditorScreen;
import com.remag.scs.client.render.NodeEditorScreen;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.ResourceLocationArgument;
import net.minecraft.commands.arguments.coordinates.Vec3Argument;
import com.mojang.brigadier.arguments.StringArgumentType;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.api.distmarker.Dist;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.item.ItemStack;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.event.lifecycle.FMLClientSetupEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.InputEvent;
import net.neoforged.neoforge.client.event.*;
import net.neoforged.neoforge.client.gui.ConfigurationScreen;
import net.neoforged.neoforge.client.gui.IConfigScreenFactory;
import org.lwjgl.glfw.GLFW;
import com.remag.scs.client.camera.CutsceneFinishedEvent;

import java.util.List;

// This class will not load on dedicated servers. Accessing client side code from here is safe.
@Mod(value = SandboxCutscenes.MODID, dist = Dist.CLIENT)
// You can use EventBusSubscriber to automatically register all static methods in the class annotated with @SubscribeEvent
@EventBusSubscriber(modid = SandboxCutscenes.MODID, value = Dist.CLIENT)
public class SandboxCutscenesClient {
    private static long lastClearAttempt = 0;
    public static final KeyMapping RERUN_CUTSCENE = new KeyMapping(
            "key.scs.rerun",
            GLFW.GLFW_KEY_K,
            "key.categories.scs"
    );

    public static final KeyMapping ADD_NODE = new KeyMapping(
            "key.scs.add_node",
            GLFW.GLFW_KEY_N,
            "key.categories.scs"
    );

    public static final KeyMapping SAVE_PATH = new KeyMapping(
            "key.scs.save",
            GLFW.GLFW_KEY_P,
            "key.categories.scs"
    );

    public static final KeyMapping CLEAR_RENDER = new KeyMapping(
            "key.scs.clear",
            GLFW.GLFW_KEY_X,
            "key.categories.scs"
    );

    public static final KeyMapping OPEN_TIMED_EVENTS = new KeyMapping(
            "key.scs.timed_events",
            GLFW.GLFW_KEY_J,
            "key.categories.scs"
    );

    public static final KeyMapping TOGGLE_PROP_CAMERA = new KeyMapping(
            "key.scs.prop_camera",
            GLFW.GLFW_KEY_B,
            "key.categories.scs"
    );

    public SandboxCutscenesClient(ModContainer container) {
        // Allows NeoForge to create a config screen for this mod's configs.
        // The config screen is accessed by going to the Mods screen > clicking on your mod > clicking on config.
        // Remember to add translations for your config options to the en_us.json file.
        container.registerExtensionPoint(IConfigScreenFactory.class, ConfigurationScreen::new);
    }

    @SubscribeEvent
    static void onClientSetup(FMLClientSetupEvent event) {
    }

    @SubscribeEvent
    public static void registerKeyMappings(RegisterKeyMappingsEvent event) {
        if (Config.DEVELOPER_MODE.get()) {
            event.register(RERUN_CUTSCENE);
            event.register(ADD_NODE);
            event.register(SAVE_PATH);
            event.register(CLEAR_RENDER);
            event.register(OPEN_TIMED_EVENTS);
            event.register(TOGGLE_PROP_CAMERA);
        }
    }

    @SubscribeEvent
    public static void onClientTick(ClientTickEvent.Post event) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) return;

        if (Config.DEVELOPER_MODE.get()) {
            while (RERUN_CUTSCENE.consumeClick()) {
                SimpleCameraManager.rerunLastCutscene();
            }

            while (ADD_NODE.consumeClick()) {
                if (SimpleCameraManager.hasActivePaths()) {
                    SimpleCameraManager.addNodeAtPlayer();
                } else {
                    mc.player.displayClientMessage(net.minecraft.network.chat.Component.literal("§c[Sandbox Cutscenes] No path previewed to add nodes to."), true);
                }
            }

            while (SAVE_PATH.consumeClick()) {
                SimpleCameraManager.saveCurrentPath();
            }

            while (TOGGLE_PROP_CAMERA.consumeClick()) {
                if (!SimpleCameraManager.hasActivePaths()) {
                    mc.player.displayClientMessage(Component.literal("§c[Sandbox Cutscenes] No path previewed."), true);
                    continue;
                }
                SimpleCameraManager.togglePreviewPlayback();
            }

            while (CLEAR_RENDER.consumeClick()) {
                if (SimpleCameraManager.hasActivePaths()) {
                    long currentTime = System.currentTimeMillis();
                    if (CameraPathRenderer.isDirty() && (currentTime - lastClearAttempt > 5000)) {
                        mc.player.displayClientMessage(net.minecraft.network.chat.Component.literal("§6[Sandbox Cutscenes] You have unsaved changes! Press again to confirm clear."), true);
                        lastClearAttempt = currentTime;
                    } else {
                        if (SimpleCameraManager.isPreviewPlaybackActive()) {
                            SimpleCameraManager.togglePreviewPlayback();
                        }
                        CameraPathRenderer.clear();
                        mc.player.displayClientMessage(net.minecraft.network.chat.Component.literal("§a[Sandbox Cutscenes] Cleared all previews."), true);
                        lastClearAttempt = 0;
                    }
                } else {
                    mc.player.displayClientMessage(net.minecraft.network.chat.Component.literal("§c[Sandbox Cutscenes] No paths are currently previewed."), true);
                }
            }

            while (OPEN_TIMED_EVENTS.consumeClick()) {
                if (!SimpleCameraManager.hasActivePaths()) {
                    mc.player.displayClientMessage(Component.literal("§c[Sandbox Cutscenes] No path previewed."), true);
                    continue;
                }

                List<String> timedNames = SimpleCameraManager.getTimedEventNames();
                String initialTimed = timedNames.isEmpty() ? null : timedNames.getFirst();
                mc.setScreen(new EventEditorScreen(initialTimed));
            }
        }

        // Logic (Queue processing) still happens at 20Hz
        SimpleCameraManager.tick();
    }

    @SubscribeEvent
    public static void onRenderFrame(RenderFrameEvent.Pre event) {
        SimpleCameraManager.recordFrame();
        if (SimpleCameraManager.isActive() || SimpleCameraManager.isPreviewPlaybackActive()) {
            SimpleCameraManager.renderTick(Minecraft.getInstance().getTimer().getGameTimeDeltaPartialTick(false));
        }
    }

    @SubscribeEvent
    public static void registerClientCommands(RegisterClientCommandsEvent event) {
        if (!Config.DEVELOPER_MODE.get()) return;

        event.getDispatcher().register(Commands.literal("scs")
                .then(Commands.literal("run")
                        .then(Commands.argument("path", ResourceLocationArgument.id())
                                .executes(context -> {
                                    ResourceLocation path = ResourceLocationArgument.getId(context, "path");
                                    Vec3 origin = Minecraft.getInstance().player != null ? Minecraft.getInstance().player.position() : Vec3.ZERO;
                                    SimpleCameraManager.runCutscene(path, origin, CutsceneFinishedEvent.RunSource.COMMAND);
                                    return 1;
                                })
                                .then(Commands.argument("pos", Vec3Argument.vec3())
                                        .executes(context -> {
                                            ResourceLocation path = ResourceLocationArgument.getId(context, "path");
                                            Vec3 origin = Vec3Argument.getVec3(context, "pos");
                                            SimpleCameraManager.runCutscene(path, origin, CutsceneFinishedEvent.RunSource.COMMAND);
                                            return 1;
                                        })
                                )
                        )
                )
                .then(Commands.literal("preview")
                        .then(Commands.argument("path", ResourceLocationArgument.id())
                                .executes(context -> {
                                    ResourceLocation path = ResourceLocationArgument.getId(context, "path");
                                    Vec3 origin = Minecraft.getInstance().player != null ? Minecraft.getInstance().player.position() : Vec3.ZERO;
                                    SimpleCameraManager.previewCutscene(path, origin);
                                    return 1;
                                })
                                .then(Commands.argument("pos", Vec3Argument.vec3())
                                        .executes(context -> {
                                            ResourceLocation path = ResourceLocationArgument.getId(context, "path");
                                            Vec3 origin = Vec3Argument.getVec3(context, "pos");
                                            SimpleCameraManager.previewCutscene(path, origin);
                                            return 1;
                                        })
                                )
                        )
                )
                .then(Commands.literal("clear")
                        .executes(context -> {
                            if (SimpleCameraManager.isPreviewPlaybackActive()) {
                                SimpleCameraManager.togglePreviewPlayback();
                            }
                            CameraPathRenderer.clear();
                            SimpleCameraManager.previewCutscene(null, null); // Clear tracked location
                            return 1;
                        })
                )
                .then(Commands.literal("save")
                        .executes(context -> {
                            SimpleCameraManager.saveCurrentPath();
                            return 1;
                        })
                )
                .then(Commands.literal("new")
                        .then(Commands.argument("name", StringArgumentType.greedyString())
                                .executes(context -> {
                                    String name = StringArgumentType.getString(context, "name");
                                    SimpleCameraManager.createNewRelativeCutsceneFile(name);
                                    return 1;
                                })
                        )
                        .executes(context -> {
                            SimpleCameraManager.createNewRelativeCutsceneFile("cutscene");
                            return 1;
                        })
                )
        );
    }

    @SubscribeEvent
    public static void onResourcesReload(TextureAtlasStitchedEvent event) {
        // TextureStitch.Post is a reliable point after F3+T finishes
        SimpleCameraManager.refreshPreview();
    }

    // Remove the old onRecipesUpdated if you still have it
    @SubscribeEvent
    public static void onRenderLevel(RenderLevelStageEvent event) {
        if (event.getStage() == RenderLevelStageEvent.Stage.AFTER_PARTICLES) {
            CameraPathRenderer.render(event.getPoseStack());
        }
    }

    @SubscribeEvent
    public static void onRenderGui(RenderGuiEvent.Pre event) {
        if (SimpleCameraManager.isActive()) {
            event.setCanceled(true); // Cancel the event, HUD won't render
        }
        SimpleCameraManager.renderFade(event.getGuiGraphics());
        SimpleCameraManager.renderTextures(event.getGuiGraphics());
        SimpleCameraManager.renderEditorOverlay(event.getGuiGraphics());
    }

    @SubscribeEvent
    public static void onInput(InputEvent.MouseButton.Pre event) {
        Minecraft mc = Minecraft.getInstance();
        if (!Config.DEVELOPER_MODE.get() || !CameraPathRenderer.isVisible() || SimpleCameraManager.isActive() || mc.screen != null) return;

        if (event.getButton() == GLFW.GLFW_MOUSE_BUTTON_LEFT) {
            if (event.getAction() == GLFW.GLFW_PRESS) {
                boolean isNodeInteraction = CameraPathRenderer.getHoveredNode() != null || CameraPathRenderer.isDragging();
                boolean isSneaking = mc.player != null && mc.player.isShiftKeyDown();

                if (isNodeInteraction) {
                    // Always consume node left-clicks so they do not hit blocks behind the node.
                    event.setCanceled(true);
                }

                if (isSneaking) {
                    boolean wasDragging = CameraPathRenderer.isDragging();
                    boolean nowDragging = CameraPathRenderer.toggleDragging();


                    if (mc.player != null) {
                        if (wasDragging && !nowDragging) {
                            mc.player.displayClientMessage(Component.literal("§aStopped moving node."), true);
                        } else if (nowDragging) {
                            CameraPathRenderer.NodeData hovered = CameraPathRenderer.getHoveredNode();
                            String nodeLabel = hovered != null ? String.valueOf(hovered.index) : "";
                            mc.player.displayClientMessage(Component.literal("§aMoving node " + nodeLabel + " (click again to stop, scroll to change distance)."), true);
                        } else {
                            mc.player.displayClientMessage(Component.literal("§eSneak and left-click a node to move it."), true);
                        }
                    }
                }
            }
        }
        
        if (event.getButton() == GLFW.GLFW_MOUSE_BUTTON_RIGHT && event.getAction() == GLFW.GLFW_PRESS) {
            // Recording tool takes priority so you can always toggle recording,
            // even if a node/event is currently hovered.
            if (mc.player != null) {
                ItemStack held = mc.player.getMainHandItem();
                String heldId = net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(held.getItem()).toString();
                String toolId = Config.RECORDING_TOOL_ITEM.get();
                if (toolId != null && toolId.equals(heldId)) {
                    SimpleCameraManager.setRecording(!SimpleCameraManager.isRecording());
                    event.setCanceled(true); // Prevent stick/item use
                    return;
                }
            }

            // Preserve existing editor right-click behaviors
            if (CameraPathRenderer.getHoveredEvent() != null) {
                CameraPathRenderer.EventNode hoveredEvent = CameraPathRenderer.getHoveredEvent();
                if (hoveredEvent.sourceNode() != null) {
                    Minecraft.getInstance().setScreen(new EventEditorScreen(hoveredEvent.sourceNode(), hoveredEvent.selectedEventName()));
                } else {
                    Minecraft.getInstance().setScreen(new EventEditorScreen(hoveredEvent.name()));
                }
                event.setCanceled(true); // Prevent item use
                return;
            }
            CameraPathRenderer.NodeData hoveredNode = CameraPathRenderer.getHoveredNode();
            if (hoveredNode != null) {
                Minecraft.getInstance().setScreen(new NodeEditorScreen(hoveredNode));
                event.setCanceled(true); // Prevent item use
                return;
            }
        }
    }

    @SubscribeEvent
    public static void onMouseScroll(InputEvent.MouseScrollingEvent event) {
        Minecraft mc = Minecraft.getInstance();
        if (!Config.DEVELOPER_MODE.get() || !CameraPathRenderer.isVisible() || SimpleCameraManager.isActive() || mc.screen != null) return;
        if (!CameraPathRenderer.isDragging()) return;

        double updatedDistance = CameraPathRenderer.adjustDragDistance(event.getScrollDeltaY() * 0.25);
        event.setCanceled(true);
        if (mc.player != null) {
            mc.player.displayClientMessage(Component.literal(String.format("§bNode distance: %.2f", updatedDistance)), true);
        }
    }
}
