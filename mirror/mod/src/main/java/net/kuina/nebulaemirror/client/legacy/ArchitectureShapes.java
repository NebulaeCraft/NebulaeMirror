package net.kuina.nebulaemirror.client.legacy;

import com.google.gson.Gson;
import net.kuina.nebulaemirror.NebulaeMirror;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * ArchitectureCraft 1.12 shape table (com.elytradev.architecture.common.shape.EnumShape, MIT) and its
 * OBJSON models, bundled under assets/nebulaemirror/architecturecraft/shape/.
 */
final class ArchitectureShapes {
    enum Kind { MODEL, ROOF, WINDOW, CLADDING }

    record Shape(int id, String name, Kind kind, String model) {
    }

    /** OBJSON model: faces with a texture slot (0 base, 1 base projected, 2 secondary, 3 secondary projected). */
    static final class Model {
        double[] bounds;
        Face[] faces;
    }

    static final class Face {
        int texture;
        double[][] vertices; // x y z nx ny nz u v, block-centred (-0.5..0.5)
        int[][] triangles;
    }

    private static final Map<Integer, Shape> SHAPES = new HashMap<>();
    private static final Map<String, Model> MODELS = new ConcurrentHashMap<>();
    private static final Gson GSON = new Gson();

    private static void put(int id, String name, Kind kind, String model) {
        SHAPES.put(id, new Shape(id, name, kind, model));
    }

    static {
        put(0, "roof_tile", Kind.ROOF, null);
        put(1, "roof_outer_corner", Kind.ROOF, null);
        put(2, "roof_inner_corner", Kind.ROOF, null);
        put(3, "roof_ridge", Kind.ROOF, null);
        put(4, "roof_smart_ridge", Kind.ROOF, null);
        put(5, "roof_valley", Kind.ROOF, null);
        put(6, "roof_smart_valley", Kind.ROOF, null);
        put(7, "roof_overhang", Kind.MODEL, "roof_overhang");
        put(8, "roof_overhang_outer_corner", Kind.MODEL, "roof_overhang_outer_corner");
        put(9, "roof_overhang_inner_corner", Kind.MODEL, "roof_overhang_inner_corner");
        put(10, "cylinder", Kind.MODEL, "cylinder_full_r8h16");
        put(11, "cylinder_half", Kind.MODEL, "cylinder_half_r8h16");
        put(12, "cylinder_quarter", Kind.MODEL, "cylinder_quarter_r8h16");
        put(13, "cylinder_large_quarter", Kind.MODEL, "cylinder_quarter_r16h16");
        put(14, "anticylinder_large_quarter", Kind.MODEL, "round_inner_corner");
        put(15, "pillar", Kind.MODEL, "cylinder_r6h16");
        put(16, "post", Kind.MODEL, "cylinder_r4h16");
        put(17, "pole", Kind.MODEL, "cylinder_r2h16");
        put(18, "bevelled_outer_corner", Kind.MODEL, "bevelled_outer_corner");
        put(19, "bevelled_inner_corner", Kind.MODEL, "bevelled_inner_corner");
        put(20, "pillar_base", Kind.MODEL, "pillar_base");
        put(21, "doric_capital", Kind.MODEL, "doric_capital");
        put(22, "ionic_capital", Kind.MODEL, "ionic_capital");
        put(23, "corinthian_capital", Kind.MODEL, "corinthian_capital");
        put(24, "doric_triglyph", Kind.MODEL, "doric_triglyph");
        put(25, "doric_triglyph_corner", Kind.MODEL, "doric_triglyph_corner");
        put(26, "doric_metope", Kind.MODEL, "doric_metope");
        put(27, "architrave", Kind.MODEL, "architrave");
        put(28, "architrave_corner", Kind.MODEL, "architrave_corner");
        put(30, "window_frame", Kind.WINDOW, null);
        put(31, "window_corner", Kind.WINDOW, null);
        put(32, "window_mullion", Kind.WINDOW, null);
        put(33, "sphere_full", Kind.MODEL, "sphere_full_r8");
        put(34, "sphere_half", Kind.MODEL, "sphere_half_r8");
        put(35, "sphere_quarter", Kind.MODEL, "sphere_quarter_r8");
        put(36, "sphere_eighth", Kind.MODEL, "sphere_eighth_r8");
        put(37, "sphere_eighth_large", Kind.MODEL, "sphere_eighth_r16");
        put(38, "sphere_eighth_large_rev", Kind.MODEL, "sphere_eighth_r16_rev");
        put(40, "roof_overhang_gable_lh", Kind.MODEL, "roof_overhang_gable_lh");
        put(41, "roof_overhang_gable_rh", Kind.MODEL, "roof_overhang_gable_rh");
        put(42, "roof_overhang_gable_end_lh", Kind.MODEL, "roof_overhang_gable_end_lh");
        put(43, "roof_overhang_gable_end_rh", Kind.MODEL, "roof_overhang_gable_end_rh");
        put(44, "roof_overhang_ridge", Kind.MODEL, "roof_overhang_gable_ridge");
        put(45, "roof_overhang_valley", Kind.MODEL, "roof_overhang_gable_valley");
        put(50, "cornice_lh", Kind.MODEL, "cornice_lh");
        put(51, "cornice_rh", Kind.MODEL, "cornice_rh");
        put(52, "cornice_end_lh", Kind.MODEL, "cornice_end_lh");
        put(53, "cornice_end_rh", Kind.MODEL, "cornice_end_rh");
        put(54, "cornice_ridge", Kind.MODEL, "cornice_ridge");
        put(55, "cornice_valley", Kind.MODEL, "cornice_valley");
        put(56, "cornice_bottom", Kind.MODEL, "cornice_bottom");
        put(60, "cladding_sheet", Kind.CLADDING, null);
        put(61, "arch_d_1", Kind.MODEL, "arch_d1");
        put(62, "arch_d_2", Kind.MODEL, "arch_d2");
        put(63, "arch_d_3_a", Kind.MODEL, "arch_d3a");
        put(64, "arch_d_3_b", Kind.MODEL, "arch_d3b");
        put(65, "arch_d_3_c", Kind.MODEL, "arch_d3c");
        put(66, "arch_d_4_a", Kind.MODEL, "arch_d4a");
        put(67, "arch_d_4_b", Kind.MODEL, "arch_d4b");
        put(68, "arch_d_4_c", Kind.MODEL, "arch_d4c");
        put(70, "banister_plain_bottom", Kind.MODEL, "balustrade_stair_plain_bottom");
        put(71, "banister_plain", Kind.MODEL, "balustrade_stair_plain");
        put(72, "banister_plain_top", Kind.MODEL, "balustrade_stair_plain_top");
        put(73, "balustrade_fancy", Kind.MODEL, "balustrade_fancy");
        put(74, "balustrade_fancy_corner", Kind.MODEL, "balustrade_fancy_corner");
        put(75, "balustrade_fancy_with_newel", Kind.MODEL, "balustrade_fancy_with_newel");
        put(76, "balustrade_fancy_newel", Kind.MODEL, "balustrade_fancy_newel");
        put(77, "balustrade_plain", Kind.MODEL, "balustrade_plain");
        put(78, "balustrade_plain_outer_corner", Kind.MODEL, "balustrade_plain_outer_corner");
        put(79, "balustrade_plain_with_newel", Kind.MODEL, "balustrade_plain_with_newel");
        put(80, "banister_plain_end", Kind.MODEL, "balustrade_stair_plain_end");
        put(81, "banister_fancy_newel_tall", Kind.MODEL, "balustrade_fancy_newel_tall");
        put(82, "balustrade_plain_inner_corner", Kind.MODEL, "balustrade_plain_inner_corner");
        put(83, "balustrade_plain_end", Kind.MODEL, "balustrade_plain_end");
        put(84, "banister_fancy_bottom", Kind.MODEL, "balustrade_stair_fancy_bottom");
        put(85, "banister_fancy", Kind.MODEL, "balustrade_stair_fancy");
        put(86, "banister_fancy_top", Kind.MODEL, "balustrade_stair_fancy_top");
        put(87, "banister_fancy_end", Kind.MODEL, "balustrade_stair_fancy_end");
        put(88, "banister_plain_inner_corner", Kind.MODEL, "balustrade_stair_plain_inner_corner");
        put(90, "slab", Kind.MODEL, "slab");
        put(91, "stairs", Kind.MODEL, "stairs");
        put(92, "stairs_outer_corner", Kind.MODEL, "stairs_outer_corner");
        put(93, "stairs_inner_corner", Kind.MODEL, "stairs_inner_corner");
    }

    private ArchitectureShapes() {
    }

    static Shape shape(int id) {
        return SHAPES.get(id);
    }

    static Model model(String name) {
        return MODELS.computeIfAbsent(name, n -> {
            String path = "/assets/nebulaemirror/architecturecraft/shape/" + n + ".objson";
            try (InputStream in = ArchitectureShapes.class.getResourceAsStream(path)) {
                if (in == null) {
                    NebulaeMirror.LOG.warn("Missing ArchitectureCraft model {}", path);
                    return new Model();
                }
                return GSON.fromJson(new InputStreamReader(in, StandardCharsets.UTF_8), Model.class);
            } catch (Exception e) {
                NebulaeMirror.LOG.warn("Bad ArchitectureCraft model {}: {}", path, e.toString());
                return new Model();
            }
        });
    }
}
