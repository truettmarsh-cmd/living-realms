package livingrealms.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import livingrealms.LivingRealms;
import livingrealms.Registry;
import livingrealms.settlement.Tier;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.ClientPlayerNetworkEvent;
import net.minecraftforge.client.event.RenderLevelStageEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.joml.Matrix4f;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * A soft band of light on the ground along each nearby town's border (Settlement.radius), shown while you are in or
 * near a town, or anywhere in range while holding the charter or the Architect's Plans. When a town ranks up, the ring
 * sweeps outward to its new border with a short message. The ring key (unbound by default) hides and shows it.
 */
@Mod.EventBusSubscriber(modid = LivingRealms.MODID, value = Dist.CLIENT)
public final class TownRing {
    /** How far outside the border the ring is still drawn. */
    public static final int NEAR = 32;
    /** Length of the expanding moment, in ticks. */
    public static final int GROW_TICKS = 60;
    private static boolean on = true;
    private static long clientTicks;
    private static final Map<UUID, Anim> ANIM = new HashMap<>();

    /** A town's border as drawn: sweeping from one radius to the next after a rank-up. */
    static final class Anim {
        double from, to;
        long start = Long.MIN_VALUE / 2;
        Anim(double r) { from = to = r; }
        double progress(float partial) { return Math.min(1, Math.max(0, (clientTicks + partial - start) / GROW_TICKS)); }
        boolean growing(float partial) { return progress(partial) < 1; }
        double radius(float partial) {
            double t = progress(partial), ease = 1 - Math.pow(1 - t, 3);
            return from + (to - from) * ease;
        }
    }

    /** Whether the ring is switched on (the Architect's flat planning border then steps aside). */
    public static boolean shown() { return on; }

    public static void toggle() { on = !on; }

    static boolean holdingCharterOrPlans(Player p) {
        for (var stack : new net.minecraft.world.item.ItemStack[]{p.getMainHandItem(), p.getOffhandItem()})
            if (stack.is(Registry.PLANNER.get()) || stack.is(Registry.CHARTER_ITEM.get())) return true;
        return false;
    }

    static boolean visible(Player p, CompoundTag town, double radius) {
        if (holdingCharterOrPlans(p) || ArchitectView.active()) return true;
        BlockPos c = BlockPos.of(town.getLong("center"));
        double dx = p.getX() - c.getX() - 0.5, dz = p.getZ() - c.getZ() - 0.5, reach = radius + NEAR;
        return dx * dx + dz * dz <= reach * reach;
    }

    // ------------------------------------------------------------------ the expanding moment

    @SubscribeEvent
    public static void tick(TickEvent.ClientTickEvent e) {
        if (e.phase != TickEvent.Phase.END) return;
        Minecraft mc = Minecraft.getInstance();
        clientTicks++;
        if (clientTicks % 20 == 0) GROUND.clear();
        while (ClientSetup.RING.consumeClick()) {
            toggle();
            if (mc.player != null) mc.player.displayClientMessage(Component.literal(on ? "Town borders shown" : "Town borders hidden"), true);
        }
        if (mc.player == null || mc.level == null) return;
        for (CompoundTag town : ClientState.towns) {
            UUID id = town.getUUID("id");
            int r = town.getInt("radius");
            Anim a = ANIM.get(id);
            if (a == null) { ANIM.put(id, new Anim(r)); continue; }
            if (r > a.to) {
                a.from = a.radius(0);
                a.to = r;
                a.start = clientTicks;
                if (town.getBoolean("mine")) announce(mc, town, r);
            } else if (r < a.to) {
                a.from = a.to = r;
                a.start = Long.MIN_VALUE / 2;
            }
            if (on && a.growing(0) && visible(mc.player, town, a.to)) sparkle(mc, town, a.radius(0));
        }
    }

    static void announce(Minecraft mc, CompoundTag town, int radius) {
        String rank = town.contains("tier") ? Tier.of(town.getInt("tier")).label : "town";
        mc.gui.setTimes(8, 50, 20);
        mc.gui.setSubtitle(Component.literal("Now a " + rank + ": its land reaches " + radius + " blocks").withStyle(ChatFormatting.YELLOW));
        mc.gui.setTitle(Component.literal(town.getString("name") + " expands").withStyle(ChatFormatting.GOLD));
        mc.player.displayClientMessage(Component.literal(town.getString("name") + " expands: now a " + rank + ", its land reaches " + radius + " blocks.")
                .withStyle(ChatFormatting.GOLD), false);
        mc.getSoundManager().play(SimpleSoundInstance.forUI(SoundEvents.PLAYER_LEVELUP, 0.7f, 0.6f));
    }

    /** Sparks along the moving front of an expanding border. */
    static void sparkle(Minecraft mc, CompoundTag town, double radius) {
        BlockPos c = BlockPos.of(town.getLong("center"));
        var random = mc.level.random;
        for (int i = 0; i < 16; i++) {
            double a = random.nextDouble() * Math.PI * 2;
            double x = c.getX() + 0.5 + Math.cos(a) * radius, z = c.getZ() + 0.5 + Math.sin(a) * radius;
            Double y = ground(mc, x, z);
            if (y == null || mc.player.distanceToSqr(x, y, z) > 96 * 96) continue;
            mc.level.addParticle(ParticleTypes.END_ROD, x, y + 0.2, z, 0, 0.03 + random.nextDouble() * 0.04, 0);
        }
    }

    @SubscribeEvent
    public static void logout(ClientPlayerNetworkEvent.LoggingOut e) { ANIM.clear(); }

    // ------------------------------------------------------------------ drawing

    @SubscribeEvent
    public static void render(RenderLevelStageEvent e) {
        if (e.getStage() != RenderLevelStageEvent.Stage.AFTER_TRANSLUCENT_BLOCKS || !on) return;
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null || ClientState.towns.isEmpty()) return;
        float partial = e.getPartialTick();
        Vec3 cam = e.getCamera().getPosition();
        PoseStack ps = e.getPoseStack();
        MultiBufferSource.BufferSource buffers = mc.renderBuffers().bufferSource();
        boolean drew = false;
        ps.pushPose();
        ps.translate(-cam.x, -cam.y, -cam.z);
        for (CompoundTag town : ClientState.towns) {
            Anim a = ANIM.get(town.getUUID("id"));
            double r = a == null ? town.getInt("radius") : a.radius(partial);
            if (!visible(mc.player, town, Math.max(r, town.getInt("radius")))) continue;
            float glow = a != null && a.growing(partial) ? (float) (1 - a.progress(partial)) : 0;
            boolean mine = town.getBoolean("mine");
            float[] col = mine ? new float[]{1f, 0.82f, 0.35f} : town.getBoolean("ai") ? new float[]{0.78f, 0.82f, 0.9f} : new float[]{0.95f, 0.5f, 0.45f};
            band(ps, buffers, mc, BlockPos.of(town.getLong("center")), r, col, 0.42f + 0.4f * glow, 0.55 + 0.9 * glow);
            if (glow > 0) band(ps, buffers, mc, BlockPos.of(town.getLong("center")), a.from, col, 0.3f * glow, 0.4);
            drew = true;
        }
        ps.popPose();
        if (drew) {
            buffers.endBatch(RenderType.debugQuads());
            buffers.endBatch(RenderType.lines());
        }
    }

    /** A ribbon fading upward from the ground along the circle, with a crisp line where it meets the ground. */
    static void band(PoseStack ps, MultiBufferSource buffers, Minecraft mc, BlockPos c, double r, float[] col, float alpha, double height) {
        int seg = (int) Math.max(96, Math.min(400, r * 2));
        VertexConsumer quads = buffers.getBuffer(RenderType.debugQuads());
        Matrix4f m = ps.last().pose();
        double cx = c.getX() + 0.5, cz = c.getZ() + 0.5;
        double px = cx + r, pz = cz;
        Double py = ground(mc, px, pz);
        float[][] line = new float[seg + 1][];
        line[0] = py == null ? null : new float[]{(float) px, py.floatValue() + 0.04f, (float) pz};
        for (int k = 1; k <= seg; k++) {
            double ang = k * Math.PI * 2 / seg;
            double x = cx + Math.cos(ang) * r, z = cz + Math.sin(ang) * r;
            Double y = ground(mc, x, z);
            line[k] = y == null ? null : new float[]{(float) x, y.floatValue() + 0.04f, (float) z};
            if (line[k] != null && line[k - 1] != null && Math.abs(line[k][1] - line[k - 1][1]) < 4) {
                float[] p0 = line[k - 1], p1 = line[k];
                float h = (float) height;
                quads.vertex(m, p0[0], p0[1], p0[2]).color(col[0], col[1], col[2], alpha).endVertex();
                quads.vertex(m, p1[0], p1[1], p1[2]).color(col[0], col[1], col[2], alpha).endVertex();
                quads.vertex(m, p1[0], p1[1] + h, p1[2]).color(col[0], col[1], col[2], 0f).endVertex();
                quads.vertex(m, p0[0], p0[1] + h, p0[2]).color(col[0], col[1], col[2], 0f).endVertex();
            }
        }
        VertexConsumer lines = buffers.getBuffer(RenderType.lines());
        var pose = ps.last();
        for (int k = 1; k <= seg; k++) {
            float[] p0 = line[k - 1], p1 = line[k];
            if (p0 == null || p1 == null || Math.abs(p1[1] - p0[1]) >= 4) continue;
            float nx = p1[0] - p0[0], ny = p1[1] - p0[1], nz = p1[2] - p0[2];
            float len = (float) Math.sqrt(nx * nx + ny * ny + nz * nz);
            if (len < 1e-4) continue;
            lines.vertex(pose.pose(), p0[0], p0[1], p0[2]).color(col[0], col[1], col[2], Math.min(1, alpha + 0.35f)).normal(pose.normal(), nx / len, ny / len, nz / len).endVertex();
            lines.vertex(pose.pose(), p1[0], p1[1], p1[2]).color(col[0], col[1], col[2], Math.min(1, alpha + 0.35f)).normal(pose.normal(), nx / len, ny / len, nz / len).endVertex();
        }
    }

    /** Ground heights along the rings, refreshed every second so new buildings and dug ground show up. */
    private static final Map<Long, Integer> GROUND = new HashMap<>();

    /**
     * Top of the ground at a point, under leaves and plants, or null where the chunk is not loaded on the client.
     * (The client only keeps the MOTION_BLOCKING and WORLD_SURFACE heightmaps.)
     */
    static Double ground(Minecraft mc, double x, double z) {
        int bx = (int) Math.floor(x), bz = (int) Math.floor(z);
        if (!mc.level.hasChunk(bx >> 4, bz >> 4)) return null;
        long key = BlockPos.asLong(bx, 0, bz);
        Integer cached = GROUND.get(key);
        if (cached == null) {
            int y = mc.level.getHeight(Heightmap.Types.MOTION_BLOCKING, bx, bz), min = mc.level.getMinBuildHeight();
            BlockPos.MutableBlockPos p = new BlockPos.MutableBlockPos(bx, y - 1, bz);
            for (int i = 0; i < 40 && p.getY() > min; i++) {
                var state = mc.level.getBlockState(p);
                if (!state.isAir() && !state.is(net.minecraft.tags.BlockTags.LEAVES) && !state.is(net.minecraft.tags.BlockTags.LOGS) && state.blocksMotion()) break;
                p.move(0, -1, 0);
            }
            cached = p.getY() + 1;
            if (GROUND.size() > 20000) GROUND.clear();
            GROUND.put(key, cached);
        }
        return cached <= mc.level.getMinBuildHeight() + 1 ? null : (double) cached;
    }

    private TownRing() {}
}
