package livingrealms.client.ui;

import com.mojang.blaze3d.platform.Lighting;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import livingrealms.building.Blueprint;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.block.BlockRenderDispatcher;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraftforge.client.model.data.ModelData;
import org.joml.Matrix4f;

/** A slowly turning 3D model of a blueprint, drawn inside the GUI. */
public final class Preview {
    public static void render(GuiGraphics g, Blueprint bp, int x, int y, int w, int h, float spin) {
        Minecraft mc = Minecraft.getInstance();
        BlockRenderDispatcher brd = mc.getBlockRenderer();
        float size = Math.max(bp.sx, Math.max(bp.sy, bp.sz));
        float scale = Math.min(w, h) / (size * 1.55f);
        PoseStack ps = g.pose();
        ps.pushPose();
        ps.translate(x + w / 2f, y + h / 2f + 4, 250);
        ps.mulPoseMatrix(new Matrix4f().scaling(scale, -scale, -scale));
        ps.mulPose(Axis.XP.rotationDegrees(-28));
        ps.mulPose(Axis.YP.rotationDegrees(spin));
        ps.translate(-bp.sx / 2f, -bp.sy / 2f, -bp.sz / 2f);
        Lighting.setupForEntityInInventory();
        RenderSystem.enableDepthTest();
        MultiBufferSource.BufferSource buf = g.bufferSource();
        int n = 0;
        for (int i = 0; i < bp.size() && n < 9000; i++) {
            BlockState s = bp.state(i);
            if (s.isAir() || s.getRenderShape() != RenderShape.MODEL) continue;
            ps.pushPose();
            ps.translate(bp.lx(i), bp.ly(i), bp.lz(i));
            brd.renderSingleBlock(s, ps, buf, 0xF000F0, OverlayTexture.NO_OVERLAY, ModelData.EMPTY, null);
            ps.popPose();
            n++;
        }
        buf.endBatch();
        Lighting.setupFor3DItems();
        ps.popPose();
    }

    private Preview() {}
}
