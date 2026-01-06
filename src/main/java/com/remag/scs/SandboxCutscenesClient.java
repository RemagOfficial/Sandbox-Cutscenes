package com.remag.scs;

import com.remag.scs.client.render.CameraPathRenderer;
import com.remag.scs.client.camera.SimpleCameraManager;
import com.remag.scs.client.render.NodeEditorScreen;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.ResourceLocationArgument;
import net.minecraft.commands.arguments.coordinates.Vec3Argument;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.api.distmarker.Dist;
import net.minecraft.world.phys.Vec3;
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
                    mc.player.displayClientMessage(net.minecraft.network.chat.Component.literal("§c[Camera Mod] No path previewed to add nodes to."), true);
                }
            }

            while (SAVE_PATH.consumeClick()) {
                SimpleCameraManager.saveCurrentPath();
            }

            while (CLEAR_RENDER.consumeClick()) {
                if (SimpleCameraManager.hasActivePaths()) {
                    long currentTime = System.currentTimeMillis();
                    if (CameraPathRenderer.isDirty() && (currentTime - lastClearAttempt > 5000)) {
                        mc.player.displayClientMessage(net.minecraft.network.chat.Component.literal("§6[Camera Mod] You have unsaved changes! Press again to confirm clear."), true);
                        lastClearAttempt = currentTime;
                    } else {
                        CameraPathRenderer.clear();
                        mc.player.displayClientMessage(net.minecraft.network.chat.Component.literal("§a[Camera Mod] Cleared all previews."), true);
                        lastClearAttempt = 0;
                    }
                } else {
                    mc.player.displayClientMessage(net.minecraft.network.chat.Component.literal("§c[Camera Mod] No paths are currently previewed."), true);
                }
            }
        }

        // Logic (Queue processing) still happens at 20Hz
        SimpleCameraManager.tick();
    }

    @SubscribeEvent
    public static void onRenderFrame(RenderFrameEvent.Pre event) {
        if (SimpleCameraManager.isActive()) {
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
                                    Vec3 origin = Minecraft.getInstance().player != null ? Minecraft.getInstance().player.getEyePosition().subtract(0, 1.5, 0) : Vec3.ZERO;
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
                                    Vec3 origin = Minecraft.getInstance().player != null ? Minecraft.getInstance().player.getEyePosition().subtract(0, 1.5, 0) : Vec3.ZERO;
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
    }

    @SubscribeEvent
    public static void onInput(InputEvent.MouseButton.Pre event) {
        if (!Config.DEVELOPER_MODE.get() || !CameraPathRenderer.isVisible() || SimpleCameraManager.isActive()) return;

        if (event.getButton() == GLFW.GLFW_MOUSE_BUTTON_LEFT) {
            if (event.getAction() == GLFW.GLFW_PRESS) {
                // Check if Shift is held (either Left or Right Shift)
                long window = Minecraft.getInstance().getWindow().getWindow();
                boolean isShiftDown = GLFW.glfwGetKey(window, GLFW.GLFW_KEY_LEFT_SHIFT) == GLFW.GLFW_PRESS ||
                                     GLFW.glfwGetKey(window, GLFW.GLFW_KEY_RIGHT_SHIFT) == GLFW.GLFW_PRESS;
                
                if (isShiftDown) {
                    CameraPathRenderer.startDragging();
                }
            } else if (event.getAction() == GLFW.GLFW_RELEASE) {
                CameraPathRenderer.stopDragging();
            }
        }
        
        if (event.getButton() == GLFW.GLFW_MOUSE_BUTTON_RIGHT && event.getAction() == GLFW.GLFW_PRESS) {
            if (CameraPathRenderer.getHoveredEvent() != null) {
                CameraPathRenderer.handleRightClick();
                event.setCanceled(true); // Prevent item use
            } else {
                CameraPathRenderer.NodeData hoveredNode = CameraPathRenderer.getHoveredNode();
                if (hoveredNode != null) {
                    Minecraft.getInstance().setScreen(new NodeEditorScreen(hoveredNode));
                    event.setCanceled(true); // Prevent item use
                }
            }
        }
    }
}
