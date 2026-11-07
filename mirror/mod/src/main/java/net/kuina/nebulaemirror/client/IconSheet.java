package net.kuina.nebulaemirror.client;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.pipeline.TextureTarget;
import com.mojang.blaze3d.platform.Lighting;
import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexSorting;
import com.mojang.math.Axis;
import net.kuina.nebulaemirror.NebulaeMirror;
import net.kuina.nebulaemirror.block.MirrorProps;
import net.kuina.nebulaemirror.data.MirrorData;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import org.joml.Matrix4f;
import org.joml.Matrix4fStack;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Renders every creative subtype state of every mirror block, isometric, into one PNG per source mod
 * (run/nebulaemirror-icons/<ns>.png) for side-by-side comparison with the 1.12 game.
 */
final class IconSheet {
    private static final int CELL = 48;
    private static final int COLUMNS = 32;

    private IconSheet() {
    }

    static String render(Minecraft mc, Path dir) throws IOException {
        Files.createDirectories(dir);
        Map<String, List<BlockState>> byNs = new LinkedHashMap<>();
        for (Map.Entry<Block, MirrorData.BlockDef> e : NebulaeMirror.BLOCKS.entrySet()) {
            MirrorData.BlockDef def = e.getValue();
            Set<BlockState> states = new LinkedHashSet<>();
            for (MirrorData.ItemDef it : def.items()) {
                if (it.state() != null) {
                    states.add(MirrorProps.apply(e.getKey().defaultBlockState(), it.state()));
                }
            }
            if (states.isEmpty()) {
                states.add(e.getKey().defaultBlockState());
            }
            byNs.computeIfAbsent(def.namespace(), k -> new ArrayList<>()).addAll(states);
        }
        int total = 0;
        for (Map.Entry<String, List<BlockState>> e : byNs.entrySet()) {
            total += sheet(mc, e.getValue(), dir.resolve(e.getKey() + ".png"));
        }
        return total + " icons in " + byNs.size() + " sheets -> " + dir;
    }

    private static int sheet(Minecraft mc, List<BlockState> states, Path file) throws IOException {
        int rows = (states.size() + COLUMNS - 1) / COLUMNS;
        int w = COLUMNS * CELL;
        int h = Math.max(1, rows) * CELL;
        RenderTarget target = new TextureTarget(w, h, true, Minecraft.ON_OSX);
        target.setClearColor(0.55f, 0.55f, 0.6f, 1f);
        target.clear(Minecraft.ON_OSX);
        target.bindWrite(true);

        Matrix4f projection = RenderSystem.getProjectionMatrix();
        VertexSorting sorting = RenderSystem.getVertexSorting();
        RenderSystem.setProjectionMatrix(new Matrix4f().setOrtho(0, w, h, 0, 1000, 21000), VertexSorting.ORTHOGRAPHIC_Z);
        Matrix4fStack modelView = RenderSystem.getModelViewStack();
        modelView.pushMatrix();
        modelView.translation(0, 0, -11000);
        RenderSystem.applyModelViewMatrix();
        Lighting.setupFor3DItems();

        MultiBufferSource.BufferSource buffers = mc.renderBuffers().bufferSource();
        PoseStack pose = new PoseStack();
        for (int i = 0; i < states.size(); i++) {
            int x = (i % COLUMNS) * CELL;
            int y = (i / COLUMNS) * CELL;
            pose.pushPose();
            pose.translate(x + CELL / 2f, y + CELL / 2f, 100);
            float s = CELL * 0.6f;
            pose.scale(s, -s, s);
            pose.mulPose(Axis.XP.rotationDegrees(30));
            pose.mulPose(Axis.YP.rotationDegrees(225));
            pose.translate(-0.5f, -0.5f, -0.5f);
            try {
                mc.getBlockRenderer().renderSingleBlock(states.get(i), pose, buffers, LightTexture.FULL_BRIGHT,
                        OverlayTexture.NO_OVERLAY);
            } catch (RuntimeException ex) {
                NebulaeMirror.LOG.warn("Icon failed for {}: {}", states.get(i), ex.toString());
            }
            pose.popPose();
        }
        buffers.endBatch();

        try (NativeImage image = new NativeImage(w, h, false)) {
            RenderSystem.bindTexture(target.getColorTextureId());
            image.downloadTexture(0, false);
            image.flipY();
            image.writeToFile(file);
        }
        modelView.popMatrix();
        RenderSystem.applyModelViewMatrix();
        RenderSystem.setProjectionMatrix(projection, sorting);
        target.destroyBuffers();
        mc.getMainRenderTarget().bindWrite(true);
        return states.size();
    }
}
