package com.remag.scs;

import net.neoforged.neoforge.common.ModConfigSpec;

// An example config class. This is not required, but it's a good idea to have one to keep your config organized.
// Demonstrates how to use Neo's config APIs
    public class Config {
        private static final ModConfigSpec.Builder BUILDER = new ModConfigSpec.Builder();

        public static final ModConfigSpec.BooleanValue DEVELOPER_MODE = BUILDER
                .comment("Whether to enable developer tools (Keybinds, Path Editor, Commands)")
                .define("developerMode", false);

        static final ModConfigSpec SPEC = BUILDER.build();
}
