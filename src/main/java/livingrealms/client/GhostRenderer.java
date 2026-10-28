package livingrealms.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import livingrealms.building.Blueprint;
import livingrealms.building.BuildingCatalog;
import livingrealms.building.BuildingType;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.block.BlockRenderDispatcher;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.client.event.RenderLevelStageEvent;
import net.minecraftforge.client.model.data.ModelData;

import java.util.ArrayList;
import java.util.List;

/** Translucent ghosts of planned buildings (what is left to build) and of the building being placed. */
public final class GhostRenderer {

    public static void render(RenderLevelStageEvent e) {
        if (e.getStage() != RenderLevelStageEvent.Stage.AFTER_TRANSLUCENT_BLOCKS) return;
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null) return;
        Vec3 cam = e.getCamera().getPosition();
        PoseStack ps = e.getPoseStack();
        MultiBufferSource.BufferSource buffers = mc.renderBuffers().bufferSource();
        boolean planning = Placement.holdingPlans() || Placement.mode != Placement.Mode.NONE || ArchitectView.active();
        BlockPos eye = e.getCamera().getBlockPosition();
        ps.pushPose();
        ps.translate(-cam.x, -cam.y, -cam.z);

        // planned and unfinished buildings
        List<CompoundTag> near = new ArrayList<>();
        for (CompoundTag town : ClientState.towns) {
            if (!town.getBoolean("mine") && !planning) continue;
            for (Tag t : town.getList("plots", Tag.TAG_COMPOUND)) {
                CompoundTag p = (CompoundTag) t;
                BlockPos o = BlockPos.of(p.getLong("origin") == 0 ? 0 : p.getLong("origin"));
                if (p.contains("origin", Tag.TAG_COMPOUND)) o = net.minecraft.nbt.NbtUtils.readBlockPos(p.getCompound("origin"));
                p.putLong("o", o.asLong());
                if (o.distSqr(eye) < 96 * 96) near.add(p);
            }
            if (planning && !TownRing.shown()) border(ps, buffers, town);
        }
        near.sort((a, b) -> Double.compare(BlockPos.of(a.getLong("o")).distSqr(eye), BlockPos.of(b.getLong("o")).distSqr(eye)));
        int shown = 0;
        for (CompoundTag p : near) {
            Blueprint bp = ClientState.blueprint(p.getString("bp"));
            if (bp == null) continue;
            BlockPos o = BlockPos.of(p.getLong("o"));
            Rotation r = Rotation.valueOf(p.getString("rot"));
            String state = p.getString("state");
            boolean demolish = state.equals("DEMOLISHING");
            float[] col = demolish ? new float[]{0.95f, 0.35f, 0.3f} : state.equals("BUILDING") ? new float[]{0.95f, 0.78f, 0.3f} : new float[]{0.45f, 0.75f, 1f};
            box(ps, buffers, o, bp, r, col, 0.7f);
            if (shown++ < (ArchitectView.active() ? 32 : 8) && !demolish) {
                int start = Math.max(0, p.getInt("placed") - p.getInt("foundN"));
                ghost(ps, buffers, bp, o, r, start, 2500, 0.35f);
            }
        }

        // the building being placed
        if (Placement.mode == Placement.Mode.BUILDING) {
            BlockPos hit = Placement.hit();
            BuildingType t = Placement.type();
            if (hit != null && t != null) {
                BlockPos o = Placement.origin(hit);
                Blueprint bp = Placement.blueprint();
                if (o != null && bp != null) {
                    o = o.below(t.sink);
                    String why = check(o, bp, Placement.rotation());
                    Placement.status = why == null ? "" : why;
                    float[] col = why == null ? new float[]{0.35f, 0.95f, 0.45f} : new float[]{1f, 0.3f, 0.3f};
                    box(ps, buffers, o, bp, Placement.rotation(), col, 1f);
                    ghost(ps, buffers, bp, o, Placement.rotation(), 0, 6000, 0.55f);
                }
            }
        } else if (Placement.mode == Placement.Mode.ROAD && Placement.roadStart != null) {
            BlockPos hit = Placement.hit();
            if (hit != null) {
                VertexConsumer lines = buffers.getBuffer(RenderType.lines());
                BlockPos a = Placement.roadStart;
                int w = Placement.roadWidth;
                AABB ab = new AABB(Math.min(a.getX(), hit.getX()), Math.min(a.getY(), hit.getY()) + 1, Math.min(a.getZ(), hit.getZ()),
                        Math.max(a.getX(), hit.getX()) + 1, Math.max(a.getY(), hit.getY()) + 1.1, Math.max(a.getZ(), hit.getZ()) + 1);
                LevelRenderer.renderLineBox(ps, lines, new AABB(a).inflate(w / 2.0, 0, w / 2.0), 0.95f, 0.8f, 0.3f, 1f);
                LevelRenderer.renderLineBox(ps, lines, new AABB(hit).inflate(w / 2.0, 0, w / 2.0), 0.95f, 0.8f, 0.3f, 1f);
                var pose = ps.last();
                Vec3 va = Vec3.atCenterOf(a).add(0, 0.6, 0), vb = Vec3.atCenterOf(hit).add(0, 0.6, 0);
                Vec3 n = vb.subtract(va).normalize();
                lines.vertex(pose.pose(), (float) va.x, (float) va.y, (float) va.z).color(0.95f, 0.8f, 0.3f, 1f).normal(pose.normal(), (float) n.x, (float) n.y, (float) n.z).endVertex();
                lines.vertex(pose.pose(), (float) vb.x, (float) vb.y, (float) vb.z).color(0.95f, 0.8f, 0.3f, 1f).normal(pose.normal(), (float) n.x, (float) n.y, (float) n.z).endVertex();
                if (ab.getSize() < 0) Placement.status = "";
            }
        }
        ps.popPose();
        buffers.endBatch(RenderType.translucent());
        buffers.endBatch(RenderType.lines());
    }

    /** Client-side guess of whether the site is allowed; the server has the final word. */
    static String check(BlockPos o, Blueprint bp, Rotation r) {
        CompoundTag town = null;
        for (CompoundTag t : ClientState.towns) if (Placement.town != null && Placement.town.equals(t.getUUID("id"))) town = t;
        return town == null ? "Not in your town" : check(town, o, bp, r);
    }

    static String check(CompoundTag town, BlockPos o, Blueprint bp, Rotation r) {
        int x1 = o.getX() + bp.width(r) - 1, z1 = o.getZ() + bp.depth(r) - 1;
        BlockPos c = BlockPos.of(town.getLong("center"));
        long rad = town.getInt("radius") + 4;
        for (int[] p : new int[][]{{o.getX(), o.getZ()}, {x1, o.getZ()}, {o.getX(), z1}, {x1, z1}}) {
            long dx = p[0] - c.getX(), dz = p[1] - c.getZ();
            if (dx * dx + dz * dz > rad * rad) return "Outside the town's land";
        }
        for (Tag t : town.getList("boxes", Tag.TAG_STRING)) {
            String[] s = t.getAsString().split(",");
            int bx0 = Integer.parseInt(s[0]), bz0 = Integer.parseInt(s[1]), bx1 = Integer.parseInt(s[2]), bz1 = Integer.parseInt(s[3]);
            if (o.getX() <= bx1 && x1 >= bx0 && o.getZ() <= bz1 && z1 >= bz0) return "Overlaps another building";
        }
        return null;
    }

    static void box(PoseStack ps, MultiBufferSource buffers, BlockPos o, Blueprint bp, Rotation r, float[] c, float a) {
        AABB box = new AABB(o.getX(), o.getY(), o.getZ(), o.getX() + bp.width(r), o.getY() + bp.sy, o.getZ() + bp.depth(r));
        LevelRenderer.renderLineBox(ps, buffers.getBuffer(RenderType.lines()), box.inflate(0.02), c[0], c[1], c[2], a);
    }

    static void ghost(PoseStack ps, MultiBufferSource.BufferSource buffers, Blueprint bp, BlockPos o, Rotation r, int start, int max, float alpha) {
        ghost(ps, buffers, bp, o, r, start, max, alpha, null);
    }

    static void ghost(PoseStack ps, MultiBufferSource.BufferSource buffers, Blueprint bp, BlockPos o, Rotation r, int start, int max, float alpha, float[] tint) {
        Minecraft mc = Minecraft.getInstance();
        BlockRenderDispatcher brd = mc.getBlockRenderer();
        VertexConsumer base = buffers.getBuffer(RenderType.translucent());
        float[] c = tint == null ? new float[]{1, 1, 1} : new float[]{0.3f + tint[0] * 0.7f, 0.3f + tint[1] * 0.7f, 0.3f + tint[2] * 0.7f};
        MultiBufferSource src = type -> new Ghost(base, alpha, c[0], c[1], c[2]);
        int n = 0;
        for (int i = start; i < bp.size() && n < max; i++) {
            BlockState s = bp.state(i);
            if (s.isAir() || s.getRenderShape() != RenderShape.MODEL) continue;
            BlockPos w = bp.worldPos(i, o, r);
            BlockState have = mc.level.getBlockState(w);
            BlockState want = bp.worldState(i, r);
            if (have == want) continue;
            ps.pushPose();
            ps.translate(w.getX(), w.getY(), w.getZ());
            ps.translate(0.5, 0.5, 0.5);
            ps.scale(0.98f, 0.98f, 0.98f);
            ps.translate(-0.5, -0.5, -0.5);
            brd.renderSingleBlock(want, ps, src, 0xF000F0, OverlayTexture.NO_OVERLAY, ModelData.EMPTY, RenderType.translucent());
            ps.popPose();
            n++;
        }
    }

    /** The town border as a low double ring at ground level around the charter. */
    static void border(PoseStack ps, MultiBufferSource buffers, CompoundTag town) {
        BlockPos c = BlockPos.of(town.getLong("center"));
        double y = c.getY() + 0.1;
        double r = town.getInt("radius");
        VertexConsumer lines = buffers.getBuffer(RenderType.lines());
        var pose = ps.last();
        float cr = town.getBoolean("mine") ? 0.95f : 0.7f, cg = town.getBoolean("mine") ? 0.78f : 0.7f, cb = town.getBoolean("mine") ? 0.3f : 0.75f;
        int seg = 128;
        for (int k = 0; k < seg; k++) {
            double a0 = k * Math.PI * 2 / seg, a1 = (k + 1) * Math.PI * 2 / seg;
            float x0 = (float) (c.getX() + 0.5 + Math.cos(a0) * r), z0 = (float) (c.getZ() + 0.5 + Math.sin(a0) * r);
            float x1 = (float) (c.getX() + 0.5 + Math.cos(a1) * r), z1 = (float) (c.getZ() + 0.5 + Math.sin(a1) * r);
            float nx = x1 - x0, nz = z1 - z0, len = (float) Math.sqrt(nx * nx + nz * nz);
            for (float yy : new float[]{(float) y, (float) y + 1f}) {
                lines.vertex(pose.pose(), x0, yy, z0).color(cr, cg, cb, 0.8f).normal(pose.normal(), nx / len, 0, nz / len).endVertex();
                lines.vertex(pose.pose(), x1, yy, z1).color(cr, cg, cb, 0.8f).normal(pose.normal(), nx / len, 0, nz / len).endVertex();
            }
        }
    }

    /** Passes vertices through with their alpha scaled down. */
    record Ghost(VertexConsumer d, float alpha, float tr, float tg, float tb) implements VertexConsumer {
        public VertexConsumer vertex(double x, double y, double z) { d.vertex(x, y, z); return this; }
        public VertexConsumer color(int r, int g, int b, int a) { d.color((int) (r * tr), (int) (g * tg), (int) (b * tb), (int) (a * alpha)); return this; }
        public VertexConsumer uv(float u, float v) { d.uv(u, v); return this; }
        public VertexConsumer overlayCoords(int u, int v) { d.overlayCoords(u, v); return this; }
        public VertexConsumer uv2(int u, int v) { d.uv2(u, v); return this; }
        public VertexConsumer normal(float x, float y, float z) { d.normal(x, y, z); return this; }
        public void endVertex() { d.endVertex(); }
        public void defaultColor(int r, int g, int b, int a) { d.defaultColor((int) (r * tr), (int) (g * tg), (int) (b * tb), (int) (a * alpha)); }
        public void unsetDefaultColor() { d.unsetDefaultColor(); }
    }

    private GhostRenderer() {}
}
