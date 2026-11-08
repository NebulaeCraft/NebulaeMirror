package net.kuina.nebulaemirror.bridge;

import net.minecraftforge.common.config.Config;

/** config/mirrorbridge.cfg */
@Config(modid = MirrorBridge.MODID)
public final class BridgeConfig {
    @Config.Comment("Main-thread time per tick spent checking and applying a patch (milliseconds).")
    @Config.RangeInt(min = 1, max = 45)
    public static int tickBudgetMillis = 10;

    private BridgeConfig() {
    }
}
