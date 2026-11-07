package net.kuina.nebulaemirror.client.legacy;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import net.kuina.nebulaemirror.NebulaeMirror;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.model.geom.PartPose;
import net.minecraft.client.model.geom.builders.CubeListBuilder;
import net.minecraft.client.model.geom.builders.LayerDefinition;
import net.minecraft.client.model.geom.builders.MeshDefinition;
import net.minecraft.client.model.geom.builders.PartDefinition;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.core.HolderLookup;
import net.minecraft.locale.Language;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.FormattedText;
import net.minecraft.network.chat.Style;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.FormattedCharSequence;

import java.util.Optional;

/**
 * SPDX-License-Identifier: LGPL-3.0-or-later. This file is a port of MoarSigns 1.12 code (GoryMoon/MoarSigns,
 * LGPL-3.0-or-later) and stays under that licence; see THIRD_PARTY_NOTICES.txt.
 * <p>
 * MoarSigns 1.12 sign, drawn from its TileEntity. Ported from MoarSignRenderer / ModelMoarSign / TileEntityMoarSign
 * (settings: row offsets, font sizes, visibility, shadow; "texture" + "isMetal" pick the sign texture). The sign's
 * 1.12 metadata (kept as Legacy.Meta) holds the rotation (standing) or side and flat/ground flags (wall).
 */
final class MoarSign {
    private static final int[] MAX_LENGTHS = {90, 82, 76, 70, 66, 62, 58, 54, 52, 50, 48, 46, 44, 42, 40, 38, 36, 36,
            34, 32, 32};
    private static ModelPart board;
    private static ModelPart stick;

    private final boolean standing;
    private final int meta;
    private final ResourceLocation texture;
    private final Component[] rows = new Component[4];
    private final int[] rowLocations = new int[4];
    private final int[] rowSizes = new int[4];
    private final boolean[] visibleRows = {true, true, true, true};
    private final boolean[] shadowRows = new boolean[4];

    MoarSign(String blockName, int meta, CompoundTag te, HolderLookup.Provider registries) {
        this.standing = blockName.contains("standing");
        this.meta = meta;
        String name = te.getString("texture");
        if (name.isEmpty()) {
            name = "oak_sign";
        }
        boolean metal = te.getBoolean("isMetal");
        this.texture = NebulaeMirror.id("textures/moarsigns/" + (metal ? "metal/" : "wood/")
                + name.toLowerCase(java.util.Locale.ROOT) + ".png");
        for (int i = 0; i < 4; i++) {
            rowLocations[i] = 2 + 10 * i;
        }
        int version = te.getInt("nbtVersion");
        if (version == 1) {
            int fontSize = te.getInt("fontSize");
            int visible = fontSize > 15 ? 1 : fontSize > 5 ? 2 : fontSize > 1 ? 3 : 4;
            int textOffset = te.getInt("textOffset");
            for (int i = 0; i < 4; i++) {
                rowSizes[i] = fontSize;
                visibleRows[i] = i < visible;
                rowLocations[i] = Math.max(0, Math.abs(textOffset) + rowLocations[i] - (textOffset != 0 ? 2 : 0));
            }
        } else if (version == 2) {
            ListTag settings = te.getList("settings", Tag.TAG_INT_ARRAY);
            for (int i = 0; i < settings.size(); i++) {
                int[] a = settings.getIntArray(i);
                if (a.length < 5) {
                    continue;
                }
                for (int j = 0; j < 4; j++) {
                    switch (a[0]) {
                        case 0 -> rowLocations[j] = a[j + 1];
                        case 1 -> rowSizes[j] = a[j + 1];
                        case 2 -> visibleRows[j] = a[j + 1] == 1;
                        case 3 -> shadowRows[j] = a[j + 1] == 1;
                        default -> { }
                    }
                }
            }
        }
        for (int i = 0; i < 4; i++) {
            String json = te.getString("Text" + (i + 1));
            Component c;
            try {
                c = json.isEmpty() ? null : Component.Serializer.fromJson(json, registries);
            } catch (RuntimeException e) {
                c = Component.literal(json);
            }
            rows[i] = c;
        }
    }

    private static void model() {
        if (board != null) {
            return;
        }
        MeshDefinition mesh = new MeshDefinition();
        PartDefinition root = mesh.getRoot();
        root.addOrReplaceChild("board", CubeListBuilder.create().texOffs(0, 0).addBox(-12, -14, -1, 24, 12, 2),
                PartPose.ZERO);
        root.addOrReplaceChild("stick", CubeListBuilder.create().texOffs(0, 14).addBox(-1, -2, -1, 2, 14, 2),
                PartPose.ZERO);
        ModelPart r = LayerDefinition.create(mesh, 64, 32).bakeRoot();
        board = r.getChild("board");
        stick = r.getChild("stick");
    }

    /** Render thread only; mirrors MoarSignRenderer#render. */
    void render(PoseStack pose, MultiBufferSource buffers, int light, int overlay) {
        model();
        float f = 0.6666667F;
        pose.pushPose();
        boolean showStick;
        if (!standing) {
            int side = meta & 7;
            float f1 = 0;
            boolean flat = (meta & 8) >> 3 == 1;
            boolean ground = false;
            if (flat) {
                ground = (meta & 1) == 1;
                int rotation = (meta & 6) >> 1;
                if (ground) {
                    f1 = switch (rotation) { case 1 -> 90; case 2 -> 180; case 3 -> -90; default -> 0; };
                } else {
                    f1 = switch (rotation) { case 1 -> -90; case 2 -> 0; case 3 -> 90; default -> 180; };
                }
            } else {
                f1 = switch (side) { case 2 -> 180; case 4 -> 90; case 5 -> -90; default -> 0; };
            }
            pose.translate(0.5F, 0.75F * f, 0.5F);
            pose.mulPose(Axis.YP.rotationDegrees(-f1));
            if (flat && !ground) {
                pose.mulPose(Axis.XP.rotationDegrees(90));
            }
            if (flat && ground) {
                pose.mulPose(Axis.XP.rotationDegrees(270));
                pose.mulPose(Axis.ZP.rotationDegrees(180));
            }
            pose.translate(0.0F, -0.3125F, -0.4375F);
            showStick = false;
        } else {
            pose.translate(0.5F, 0.75F * f, 0.5F);
            pose.mulPose(Axis.YP.rotationDegrees(-(meta * 360 / 16.0F)));
            showStick = true;
        }

        pose.pushPose();
        pose.scale(f, -f, -f);
        var vc = buffers.getBuffer(RenderType.entityCutoutNoCull(texture));
        board.render(pose, vc, light, overlay == 0 ? OverlayTexture.NO_OVERLAY : overlay);
        if (showStick) {
            stick.render(pose, vc, light, overlay == 0 ? OverlayTexture.NO_OVERLAY : overlay);
        }
        pose.popPose();

        Font font = Minecraft.getInstance().font;
        int iWidth = font.width("i");
        for (int row = 0; row < 4; row++) {
            Component text = rows[row];
            if (text == null || !visibleRows[row]) {
                continue;
            }
            float size = rowSizes[row];
            pose.pushPose();
            float scale = 0.016666668F * f + size / 1000.0F;
            float adjust = size < 2 ? 0 : size < 7 ? 0.01F : size < 11 ? 0.02F : size < 16 ? 0.03F : size < 20 ? 0.035F
                    : 0.037F;
            pose.translate(size > 0 ? 0.01F : 0.0F, 0.5F * f - 0.02F * size - adjust, 0.07F * f);
            pose.scale(scale, -scale, scale);
            int styleOffset = (underlined(text) ? 1 : 0) + (shadowRows[row] ? 1 : 0);
            int sizeIdx = (int) size;
            int maxLength = (sizeIdx >= 0 && sizeIdx < MAX_LENGTHS.length ? MAX_LENGTHS[sizeIdx] : 0) - iWidth * styleOffset;
            FormattedText cut = font.substrByWidth(text, Math.max(0, Math.min(maxLength, font.width(text))));
            FormattedCharSequence seq = Language.getInstance().getVisualOrder(cut);
            float x = -font.width(seq) / 2f;
            float y = -4 * 5 + rowLocations[row] - 2;
            font.drawInBatch(seq, x, y, 0, shadowRows[row], pose.last().pose(), buffers, Font.DisplayMode.POLYGON_OFFSET,
                    0, light);
            pose.popPose();
        }
        pose.popPose();
    }

    private static boolean underlined(Component c) {
        Optional<Boolean> found = c.visit((Style style, String s) -> style.isUnderlined() || s.contains("§n")
                ? Optional.of(Boolean.TRUE) : Optional.empty(), Style.EMPTY);
        return found.isPresent();
    }
}
