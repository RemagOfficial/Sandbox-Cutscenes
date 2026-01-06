package com.remag.scs.client.camera;

import net.minecraft.resources.ResourceLocation;
import net.neoforged.bus.api.Event;

/**
 * Fired on the NeoForge Bus when a camera cutscene finishes.
 */
public class CutsceneFinishedEvent extends Event {
    private final ResourceLocation cutsceneId;
    private final RunSource source;

    public CutsceneFinishedEvent(ResourceLocation cutsceneId, RunSource source) {
        this.cutsceneId = cutsceneId;
        this.source = source;
    }

    public ResourceLocation getCutsceneId() { return cutsceneId; }
    public RunSource getSource() { return source; }

    public enum RunSource {
        COMMAND,
        HOTKEY,
        API
    }
}
