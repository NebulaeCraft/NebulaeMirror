package net.kuina.nebulaemirror.dumper;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

final class DumpOptions {
    /** Namespaces to dump in full. Empty = every namespace. */
    final Set<String> mods = new HashSet<>();
    /** Also bake quads for minecraft:* blocks (the mirror uses 1.21's own vanilla assets, so off by default). */
    boolean vanillaModels;
    /**
     * Copy minecraft:* textures that dumped (modded) models reference. The generator prefers 1.21's own
     * renamed texture and only falls back to these when there is no 1.21 equivalent.
     */
    boolean vanillaTextures = true;
    /** Upper bound of valid states dumped per block; the meta-reachable states always come first. */
    int maxStates = 4096;

    static DumpOptions parse(String[] args) {
        DumpOptions o = new DumpOptions();
        for (String a : args) {
            String s = a.trim();
            String lower = s.toLowerCase(Locale.ROOT);
            if (lower.startsWith("mods=")) {
                o.mods.addAll(split(s.substring(5)));
            } else if (lower.equals("vanillamodels")) {
                o.vanillaModels = true;
            } else if (lower.equals("novanillatextures")) {
                o.vanillaTextures = false;
            } else if (lower.startsWith("maxstates=")) {
                o.maxStates = Integer.parseInt(s.substring(10));
            } else if (!lower.equals("all") && !s.isEmpty()) {
                throw new IllegalArgumentException("未知选项：" + a);
            }
        }
        return o;
    }

    static DumpOptions fromSystemProperties() {
        DumpOptions o = new DumpOptions();
        String mods = System.getProperty("mirrordump.mods", "");
        if (!mods.isEmpty()) {
            o.mods.addAll(split(mods));
        }
        o.vanillaModels = Boolean.getBoolean("mirrordump.vanillaModels");
        o.vanillaTextures = !Boolean.getBoolean("mirrordump.noVanillaTextures");
        o.maxStates = Integer.getInteger("mirrordump.maxStates", o.maxStates);
        return o;
    }

    private static Set<String> split(String s) {
        Set<String> out = new HashSet<>();
        for (String p : Arrays.asList(s.split(","))) {
            if (!p.trim().isEmpty()) {
                out.add(p.trim());
            }
        }
        return out;
    }

    boolean fullDump(String namespace) {
        if ("minecraft".equals(namespace)) {
            return vanillaModels;
        }
        return mods.isEmpty() || mods.contains(namespace);
    }
}
