package com.remag.scs;

import net.neoforged.neoforge.common.ModConfigSpec;

// An example config class. This is not required, but it's a good idea to have one to keep your config organized.
// Demonstrates how to use Neo's config APIs
public class Config {
    private static final ModConfigSpec.Builder BUILDER = new ModConfigSpec.Builder();
    
    // Developer Mode Settings
    public static final ModConfigSpec.BooleanValue DEVELOPER_MODE;
    
    // Recording Settings
    public static final ModConfigSpec.ConfigValue<String> RECORDING_TOOL_ITEM;
    public static final ModConfigSpec.IntValue RECORDING_INTERVAL_TICKS;
    public static final ModConfigSpec.DoubleValue POSITION_THRESHOLD;
    public static final ModConfigSpec.DoubleValue ROTATION_THRESHOLD;
    
    // Config Specs
    public static final ModConfigSpec COMMON_SPEC;
    
    static {
        // Developer Settings
        BUILDER.comment("Developer Settings").push("developer");
        DEVELOPER_MODE = BUILDER
                .comment("Whether to enable developer tools (Keybinds, Path Editor, Commands)")
                .define("developerMode", false);
        BUILDER.pop();
        
        // Recording Settings
        BUILDER.comment("Recording Settings").push("recording");
        
        RECORDING_TOOL_ITEM = BUILDER
                .comment("Item ID used as the recording tool (right click while holding to toggle recording)")
                .define("recordingToolItem", "minecraft:stick");

        RECORDING_INTERVAL_TICKS = BUILDER
                .comment("How often to check for position/rotation changes while recording (in client ticks)")
                .defineInRange("recordingIntervalTicks", 1, 1, 20);
                
        POSITION_THRESHOLD = BUILDER
                .comment("Minimum distance the player needs to move to record a new point (in blocks). Set to 0 to record every tick.")
                .defineInRange("positionThreshold", 0.0, 0.0, 10.0);
                
        ROTATION_THRESHOLD = BUILDER
                .comment("Minimum rotation change (in degrees) needed to record a new point. Set to 0 to record every tick.")
                .defineInRange("rotationThreshold", 0.0, 0.0, 180.0);
        
        // Pop recording settings
        BUILDER.pop();
        
        // Build the final config
        COMMON_SPEC = BUILDER.build();
    }
}
