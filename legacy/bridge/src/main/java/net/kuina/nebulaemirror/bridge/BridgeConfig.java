package net.kuina.nebulaemirror.bridge;

import net.minecraftforge.common.config.Config;

/** config/mirrorbridge.cfg */
@Config(modid = MirrorBridge.MODID)
public final class BridgeConfig {
    @Config.Comment("Main-thread time per tick spent checking and applying a patch (milliseconds).")
    @Config.RangeInt(min = 1, max = 45)
    public static int tickBudgetMillis = 10;

    @Config.Comment("Run the HTTP API the mirror server submits to.")
    public static boolean httpEnabled = true;

    @Config.Comment("Address the HTTP API listens on. Keep 127.0.0.1 when the mirror runs on the same machine.")
    public static String httpHost = "127.0.0.1";

    @Config.Comment("Port of the HTTP API.")
    @Config.RangeInt(min = 1, max = 65535)
    public static int httpPort = 21201;

    @Config.Comment("Shared secret the mirror sends as X-Mirror-Token; generated on first start. Copy it into the mirror's config/nebulaemirror-sync.json (bridge.token).")
    public static String token = "";

    private BridgeConfig() {
    }
}
