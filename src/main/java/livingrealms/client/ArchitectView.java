package livingrealms.client;

import com.mojang.blaze3d.platform.InputConstants;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import livingrealms.LivingRealms;
import livingrealms.building.Blueprint;
import livingrealms.building.BuildingCatalog;
import livingrealms.building.BuildingType;
import livingrealms.client.ui.ArchitectScreen;
import livingrealms.net.Net;
import livingrealms.settlement.Planning;
import net.minecraft.ChatFormatting;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Marker;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.ClientPlayerNetworkEvent;
import net.minecraftforge.client.event.RenderHighlightEvent;
import net.minecraftforge.client.event.RenderLevelStageEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.joml.Matrix4f;
import org.joml.Vector4f;
import org.lwjgl.glfw.GLFW;

import javax.annotation.Nullable;
import java.util.UUID;

/**
 * The Architect view: a client-only free camera over one of your towns for planning. The player's body stays where it
 * is (and can be hurt); every plan goes through the normal server actions.
 */
@Mod.EventBusSubscriber(modid = LivingRealms.MODID, value = Dist.CLIENT)
public final class ArchitectView {
    public static final int MARGIN = 24, ENTER_MARGIN = 48;
    public static final float[] SPEEDS = {3, 6, 10, 16, 24, 36};
    public static final String ROAD = "road";

    @Nullable static Marker cam;
    @Nullable static LocalPlayer body;
    @Nullable static ClientLevel level;
    static UUID townId;
    static boolean fromManager;
    static float health;
    static int hurt;
    static double minY, maxY;
    static long lastFrame;
    @Nullable static Matrix4f inverse;
    static Vec3 camPos = Vec3.ZERO;

    // controls and tool state, shared with the screen
    public static int speed = 2;
    public static boolean looking;
    /** Test hook: forward, strafe, up impulses that replace the keyboard while set. */
    @Nullable public static float[] testInput;
    /** Test hook: aim with the screen centre instead of the mouse. */
    public static boolean aimCenter;
    @Nullable public static String tool;
    public static int rot, yOff, nudgeX, nudgeZ, selectedPlot = -1, hoveredPlot = -1;
    public static int roadWidth = 3;
    public static String roadMaterial = "path";
    @Nullable public static BlockPos roadStart, hit;
    @Nullable public static BlockPos origin;
    /** Why the current placement would be refused, or empty when it looks fine. */
    public static String status = "";
    public static String warning = "";

    public static boolean active() { return cam != null; }

    public static UUID town() { return townId; }

    @Nullable
    public static CompoundTag townTag() {
        for (CompoundTag t : ClientState.towns) if (t.hasUUID("id") && t.getUUID("id").equals(townId)) return t;
        return null;
    }

    /** The town data this view uses for catalogue and stock; only valid when the server sent this town's view. */
    public static CompoundTag view() {
        CompoundTag v = ClientState.view;
        return v != null && v.hasUUID("id") && v.getUUID("id").equals(townId) ? v : new CompoundTag();
    }

    @Nullable
    static CompoundTag ownTownNear(LocalPlayer p, @Nullable UUID want) {
        CompoundTag best = null;
        double bestD = Double.MAX_VALUE;
        for (CompoundTag t : ClientState.towns) {
            if (!t.getBoolean("mine") || !t.hasUUID("id") || want != null && !want.equals(t.getUUID("id"))) continue;
            BlockPos c = BlockPos.of(t.getLong("center"));
            double d = Math.sqrt(Math.pow(p.getX() - c.getX() - 0.5, 2) + Math.pow(p.getZ() - c.getZ() - 0.5, 2)) - t.getInt("radius");
            if (d <= ENTER_MARGIN && d < bestD) { best = t; bestD = d; }
        }
        return best;
    }

    public static void toggle() {
        if (active()) exit(null);
        else enter(null, false);
    }

    /** Enters over the given town (or the nearest of yours); only from inside or near one of your own towns. */
    public static boolean enter(@Nullable UUID want, boolean manager) {
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer p = mc.player;
        if (p == null || mc.level == null || active()) return false;
        CompoundTag town = ownTownNear(p, want);
        if (town == null) {
            p.displayClientMessage(Component.literal("Stand in or near one of your own towns to use the Architect view.").withStyle(ChatFormatting.GOLD), true);
            return false;
        }
        if (p.isDeadOrDying()) return false;
        townId = town.getUUID("id");
        fromManager = manager;
        body = p;
        level = mc.level;
        health = p.getHealth();
        hurt = p.hurtTime;
        BlockPos c = BlockPos.of(town.getLong("center"));
        minY = Math.max(level.getMinBuildHeight() + 1, Math.min(c.getY(), p.getY()) - 16);
        maxY = Math.min(level.getMaxBuildHeight() + 16, Math.max(c.getY(), p.getY()) + 80);
        Marker m = new Marker(EntityType.MARKER, level);
        Vec3 eye = p.getEyePosition();
        Vec3 back = Vec3.directionFromRotation(0, p.getYRot()).scale(-3);
        Vec3 start = eye.add(0, 3, 0).add(back);
        if (blocked(start)) start = eye;
        m.setPos(start);
        m.setYRot(p.getYRot());
        m.setXRot(Math.max(25, p.getXRot()));
        sync(m);
        cam = m;
        tool = null;
        selectedPlot = hoveredPlot = -1;
        roadStart = null;
        status = warning = "";
        looking = false;
        nudgeX = nudgeZ = yOff = 0;
        lastFrame = System.nanoTime();
        KeyMapping.releaseAll();
        mc.setCameraEntity(m);
        mc.setScreen(new ArchitectScreen());
        CompoundTag t = new CompoundTag();
        t.putUUID("id", townId);
        t.putString("tab", "architect");
        Net.toServer("open", t);
        return true;
    }

    private static boolean closing;

    /** Our screen was closed or replaced by another one. */
    public static void screenClosed() {
        closing = true;
        try { exit(null); } finally { closing = false; }
    }

    /** Puts the camera back on the player; a reason is shown to them. */
    public static void exit(@Nullable String reason) {
        if (cam == null) return;
        cam = null;
        Minecraft mc = Minecraft.getInstance();
        setLooking(false);
        if (mc.player != null) mc.setCameraEntity(mc.player);
        body = null;
        level = null;
        tool = null;
        roadStart = null;
        testInput = null;
        aimCenter = false;
        if (!closing && mc.screen instanceof ArchitectScreen) mc.setScreen(null);
        if (mc.getConnection() == null || mc.player == null) return;
        Net.toServer("close", new CompoundTag());
        if (reason != null) {
            mc.player.displayClientMessage(Component.literal(reason).withStyle(ChatFormatting.RED), false);
            mc.gui.setOverlayMessage(Component.literal("⚠ " + reason).withStyle(ChatFormatting.RED), false);
        } else if (fromManager && !closing && mc.screen == null) {
            CompoundTag t = new CompoundTag();
            t.putUUID("id", townId);
            t.putString("tab", "build");
            Net.toServer("open", t);
        }
    }

    public static void setLooking(boolean on) {
        if (looking == on) return;
        looking = on;
        var w = Minecraft.getInstance().getWindow();
        InputConstants.grabOrReleaseMouse(w.getWindow(), on ? GLFW.GLFW_CURSOR_DISABLED : GLFW.GLFW_CURSOR_NORMAL,
                Minecraft.getInstance().mouseHandler.xpos(), Minecraft.getInstance().mouseHandler.ypos());
    }

    /** Mouse look, in screen pixels, with the player's sensitivity. */
    public static void look(double dx, double dy) {
        if (cam == null) return;
        Minecraft mc = Minecraft.getInstance();
        double s = mc.options.sensitivity().get() * 0.6 + 0.2, f = s * s * s * 8;
        cam.turn(dx * f, dy * f * (mc.options.invertYMouse().get() ? -1 : 1));
        sync(cam);
    }

    public static void lookAt(Vec3 target) {
        if (cam == null) return;
        Vec3 d = target.subtract(cam.position());
        cam.setYRot((float) (Mth.atan2(d.z, d.x) * Mth.RAD_TO_DEG) - 90);
        cam.setXRot((float) -(Mth.atan2(d.y, Math.sqrt(d.x * d.x + d.z * d.z)) * Mth.RAD_TO_DEG));
        sync(cam);
    }

    public static void moveTo(Vec3 v) {
        if (cam == null) return;
        cam.setPos(clamp(v));
        sync(cam);
    }

    public static Vec3 camera() { return cam == null ? Vec3.ZERO : cam.position(); }

    public static float yaw() { return cam == null ? 0 : cam.getYRot(); }

    static void sync(Marker m) {
        m.xo = m.xOld = m.getX();
        m.yo = m.yOld = m.getY();
        m.zo = m.zOld = m.getZ();
        m.yRotO = m.getYRot();
        m.xRotO = m.getXRot();
    }

    static boolean blocked(Vec3 v) {
        ClientLevel l = level;
        if (l == null) return true;
        if (!l.hasChunkAt(BlockPos.containing(v))) return true;
        return !l.noCollision(null, new AABB(v, v).inflate(0.2));
    }

    /** Keeps the camera over the town plus a margin and within a sensible height. */
    static Vec3 clamp(Vec3 v) {
        CompoundTag t = townTag();
        if (t == null) return v;
        BlockPos c = BlockPos.of(t.getLong("center"));
        double r = t.getInt("radius") + MARGIN, cx = c.getX() + 0.5, cz = c.getZ() + 0.5;
        double dx = v.x - cx, dz = v.z - cz, d = Math.sqrt(dx * dx + dz * dz);
        if (d > r) { dx *= r / d; dz *= r / d; }
        return new Vec3(cx + dx, Mth.clamp(v.y, minY, maxY), cz + dz);
    }

    static boolean down(KeyMapping k) {
        InputConstants.Key key = k.getKey();
        long w = Minecraft.getInstance().getWindow().getWindow();
        if (key.getType() == InputConstants.Type.MOUSE) return GLFW.glfwGetMouseButton(w, key.getValue()) == GLFW.GLFW_PRESS;
        return key.getValue() != InputConstants.UNKNOWN.getValue() && InputConstants.isKeyDown(w, key.getValue());
    }

    // ------------------------------------------------------------------ events

    @SubscribeEvent
    public static void frame(TickEvent.RenderTickEvent e) {
        if (e.phase != TickEvent.Phase.START || cam == null) return;
        long now = System.nanoTime();
        float dt = Math.min(0.1f, (now - lastFrame) / 1e9f);
        lastFrame = now;
        Minecraft mc = Minecraft.getInstance();
        float fwd, strafe, up;
        if (testInput != null) { fwd = testInput[0]; strafe = testInput[1]; up = testInput[2]; }
        else {
            fwd = (down(mc.options.keyUp) ? 1 : 0) - (down(mc.options.keyDown) ? 1 : 0);
            strafe = (down(mc.options.keyRight) ? 1 : 0) - (down(mc.options.keyLeft) ? 1 : 0);
            up = (down(mc.options.keyJump) ? 1 : 0) - (down(mc.options.keyShift) ? 1 : 0);
        }
        if (fwd == 0 && strafe == 0 && up == 0) return;
        float yaw = cam.getYRot() * Mth.DEG_TO_RAD;
        Vec3 f = new Vec3(-Mth.sin(yaw), 0, Mth.cos(yaw)), r = new Vec3(-Mth.cos(yaw), 0, -Mth.sin(yaw));
        Vec3 dir = f.scale(fwd).add(r.scale(strafe)).add(0, up, 0);
        if (dir.lengthSqr() > 1) dir = dir.normalize();
        float sp = SPEEDS[speed] * (testInput == null && down(mc.options.keySprint) ? 2.5f : 1);
        Vec3 step = dir.scale(sp * dt), p = cam.position();
        // slide along each axis so the camera never enters solid ground
        Vec3 n = new Vec3(p.x + step.x, p.y, p.z);
        if (blocked(n)) n = p;
        Vec3 n2 = new Vec3(n.x, n.y + step.y, n.z);
        if (!blocked(n2)) n = n2;
        Vec3 n3 = new Vec3(n.x, n.y, n.z + step.z);
        if (!blocked(n3)) n = n3;
        cam.setPos(clamp(n));
        sync(cam);
    }

    @SubscribeEvent
    public static void tick(TickEvent.ClientTickEvent e) {
        if (e.phase != TickEvent.Phase.END) return;
        Minecraft mc = Minecraft.getInstance();
        while (ClientSetup.ARCHITECT.consumeClick()) if (mc.screen == null) toggle();
        if (cam == null) return;
        LocalPlayer p = mc.player;
        if (p == null || p != body || mc.level != level) { exit(null); return; }
        if (p.isDeadOrDying()) { exit("You died while in the Architect view."); return; }
        if (p.getHealth() < health - 0.01f || p.hurtTime > hurt) { exit("You were hurt! The Architect view closed so you can defend yourself."); return; }
        health = p.getHealth();
        hurt = p.hurtTime;
        if (!(mc.screen instanceof ArchitectScreen)) { exit(null); return; }
        // the player may have walked away (pushed by mobs, water)
        CompoundTag t = townTag();
        if (t != null && ownTownNear(p, townId) == null) exit("You left your town, so the Architect view closed.");
    }

    @SubscribeEvent
    public static void logout(ClientPlayerNetworkEvent.LoggingOut e) { exit(null); }

    @SubscribeEvent
    public static void highlight(RenderHighlightEvent.Block e) { if (cam != null) e.setCanceled(true); }

    @SubscribeEvent
    public static void hand(net.minecraftforge.client.event.RenderHandEvent e) { if (cam != null) e.setCanceled(true); }

    @SubscribeEvent
    public static void render(RenderLevelStageEvent e) {
        if (cam == null || level == null) return;
        if (e.getStage() == RenderLevelStageEvent.Stage.AFTER_SKY) {
            inverse = new Matrix4f(e.getProjectionMatrix()).mul(e.getPoseStack().last().pose()).invert();
            camPos = e.getCamera().getPosition();
            aim();
            return;
        }
        if (e.getStage() != RenderLevelStageEvent.Stage.AFTER_TRANSLUCENT_BLOCKS) return;
        Minecraft mc = Minecraft.getInstance();
        PoseStack ps = e.getPoseStack();
        MultiBufferSource.BufferSource buffers = mc.renderBuffers().bufferSource();
        ps.pushPose();
        ps.translate(-camPos.x, -camPos.y, -camPos.z);
        VertexConsumer lines = buffers.getBuffer(RenderType.lines());
        for (Tag t : view().getList("plots", Tag.TAG_COMPOUND)) {
            CompoundTag pl = (CompoundTag) t;
            AABB box = box(pl);
            if (box == null) continue;
            int id = pl.getInt("id");
            boolean built = pl.getString("state").equals("BUILT");
            if (id == selectedPlot || id == hoveredPlot) LevelRenderer.renderLineBox(ps, lines, box.inflate(0.06), 1f, 0.85f, 0.3f, 1f);
            else if (built && !pl.getString("type").equals(ROAD)) LevelRenderer.renderLineBox(ps, lines, box.inflate(0.03), 0.85f, 0.88f, 0.95f, 0.35f);
        }
        // the planned wall lines, a hand's breadth above the ground
        for (Tag wt : view().getList("walls", Tag.TAG_INT_ARRAY)) {
            int[] wv = ((net.minecraft.nbt.IntArrayTag) wt).getAsIntArray();
            var pose = ps.last();
            for (int i = 0; i + 1 < wv.length; i += 2) {
                int j = (i + 2) % wv.length;
                int n = Math.max(1, Math.max(Math.abs(wv[j] - wv[i]), Math.abs(wv[j + 1] - wv[i + 1])) / 2);
                for (int k = 0; k < n; k++) {
                    double ax = wv[i] + (wv[j] - wv[i]) * (double) k / n + 0.5, az = wv[i + 1] + (wv[j + 1] - wv[i + 1]) * (double) k / n + 0.5;
                    double bx = wv[i] + (wv[j] - wv[i]) * (double) (k + 1) / n + 0.5, bz = wv[i + 1] + (wv[j + 1] - wv[i + 1]) * (double) (k + 1) / n + 0.5;
                    float ay = level.getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, (int) Math.floor(ax), (int) Math.floor(az)) + 0.15f;
                    float by = level.getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, (int) Math.floor(bx), (int) Math.floor(bz)) + 0.15f;
                    Vec3 nrm = new Vec3(bx - ax, by - ay, bz - az).normalize();
                    lines.vertex(pose.pose(), (float) ax, ay, (float) az).color(0.85f, 0.82f, 0.75f, 0.9f).normal(pose.normal(), (float) nrm.x, (float) nrm.y, (float) nrm.z).endVertex();
                    lines.vertex(pose.pose(), (float) bx, by, (float) bz).color(0.85f, 0.82f, 0.75f, 0.9f).normal(pose.normal(), (float) nrm.x, (float) nrm.y, (float) nrm.z).endVertex();
                }
            }
        }
        BuildingType bt = tool == null ? null : BuildingCatalog.get(tool);
        Blueprint bp = blueprint();
        if (bt != null && bp != null && origin != null) {
            BlockPos o = origin.below(bt.sink);
            float[] col = status.isEmpty() ? new float[]{0.35f, 0.95f, 0.45f} : new float[]{1f, 0.3f, 0.3f};
            GhostRenderer.box(ps, buffers, o, bp, rotation(), col, 1f);
            GhostRenderer.ghost(ps, buffers, bp, o, rotation(), 0, bp.size(), 0.6f, col);
        } else if (ROAD.equals(tool) && hit != null) {
            float[] c = status.isEmpty() ? new float[]{0.95f, 0.8f, 0.3f} : new float[]{1f, 0.3f, 0.3f};
            AABB end = new AABB(hit).inflate((roadWidth - 1) / 2.0, 0, (roadWidth - 1) / 2.0);
            LevelRenderer.renderLineBox(ps, lines, end, c[0], c[1], c[2], 1f);
            if (roadStart != null) {
                LevelRenderer.renderLineBox(ps, lines, new AABB(roadStart).inflate((roadWidth - 1) / 2.0, 0, (roadWidth - 1) / 2.0), c[0], c[1], c[2], 1f);
                var pose = ps.last();
                Vec3 a = Vec3.atCenterOf(roadStart).add(0, 0.6, 0), b = Vec3.atCenterOf(hit).add(0, 0.6, 0), n = b.subtract(a).normalize();
                lines.vertex(pose.pose(), (float) a.x, (float) a.y, (float) a.z).color(c[0], c[1], c[2], 1f).normal(pose.normal(), (float) n.x, (float) n.y, (float) n.z).endVertex();
                lines.vertex(pose.pose(), (float) b.x, (float) b.y, (float) b.z).color(c[0], c[1], c[2], 1f).normal(pose.normal(), (float) n.x, (float) n.y, (float) n.z).endVertex();
            }
        }
        ps.popPose();
        buffers.endBatch(RenderType.translucent());
        buffers.endBatch(RenderType.lines());
    }

    // ------------------------------------------------------------------ aiming and checks

    @Nullable
    static AABB box(CompoundTag pl) {
        Blueprint bp = ClientState.blueprint(pl.getString("bp"));
        if (bp == null) return null;
        BlockPos o = NbtUtils.readBlockPos(pl.getCompound("origin"));
        Rotation r = Rotation.valueOf(pl.getString("rot"));
        return new AABB(o.getX(), o.getY(), o.getZ(), o.getX() + bp.width(r), o.getY() + bp.sy, o.getZ() + bp.depth(r));
    }

    /** The ray under the mouse (or the screen centre while looking around). */
    static void aim() {
        Minecraft mc = Minecraft.getInstance();
        var w = mc.getWindow();
        double nx = 0, ny = 0;
        if (!looking && !aimCenter) {
            nx = mc.mouseHandler.xpos() / w.getScreenWidth() * 2 - 1;
            ny = 1 - mc.mouseHandler.ypos() / w.getScreenHeight() * 2;
        }
        Vector4f near = inverse.transform(new Vector4f((float) nx, (float) ny, -1, 1)), far = inverse.transform(new Vector4f((float) nx, (float) ny, 1, 1));
        Vec3 a = new Vec3(near.x / near.w, near.y / near.w, near.z / near.w), b = new Vec3(far.x / far.w, far.y / far.w, far.z / far.w);
        Vec3 from = camPos.add(a), to = camPos.add(a).add(b.subtract(a).normalize().scale(200));
        HitResult r = level.clip(new ClipContext(from, to, ClipContext.Block.COLLIDER, ClipContext.Fluid.SOURCE_ONLY, cam));
        hit = r instanceof BlockHitResult bh && r.getType() == HitResult.Type.BLOCK ? bh.getBlockPos() : null;
        double reach = hit == null ? 200 : r.getLocation().distanceTo(from) + 0.5;
        hoveredPlot = -1;
        if (tool == null) {
            for (Tag t : view().getList("plots", Tag.TAG_COMPOUND)) {
                CompoundTag pl = (CompoundTag) t;
                if (pl.getString("state").equals("BUILT") || pl.getString("type").equals(ROAD)) continue;
                AABB box = box(pl);
                var c = box == null ? java.util.Optional.<Vec3>empty() : box.clip(from, to);
                if (box != null && box.contains(from)) c = java.util.Optional.of(from);
                if (c.isPresent() && c.get().distanceTo(from) < reach) { reach = c.get().distanceTo(from); hoveredPlot = pl.getInt("id"); }
            }
        }
        check();
    }

    public static Rotation rotation() { return Rotation.values()[rot & 3]; }

    @Nullable
    public static Blueprint blueprint() {
        BuildingType t = tool == null ? null : BuildingCatalog.get(tool);
        CompoundTag town = townTag();
        return t == null ? null : ClientState.blueprint(t.blueprintKey(1, town == null ? "minecraft:plains" : town.getString("style")));
    }

    /** Arrow keys move the building one block, relative to where the camera faces. */
    public static void nudge(int forward, int right) {
        Direction f = Direction.fromYRot(yaw()), r = f.getClockWise();
        nudgeX += f.getStepX() * forward + r.getStepX() * right;
        nudgeZ += f.getStepZ() * forward + r.getStepZ() * right;
    }

    /** Cheap client-side guesses; the server repeats every check when the plan is sent. */
    static void check() {
        origin = null;
        warning = "";
        CompoundTag town = townTag();
        if (ROAD.equals(tool)) {
            status = hit == null ? "Point at the ground" : !inside(town, hit, 0) ? "Outside the town's land"
                    : roadStart != null && Math.max(Math.abs(hit.getX() - roadStart.getX()), Math.abs(hit.getZ() - roadStart.getZ())) > 128 ? "Too long (128 blocks at most)" : "";
            return;
        }
        BuildingType t = tool == null ? null : BuildingCatalog.get(tool);
        if (t == null) { status = ""; return; }
        Blueprint bp = blueprint();
        if (hit == null || bp == null) { status = bp == null ? "Loading blueprint…" : "Point at the ground"; return; }
        Rotation r = rotation();
        origin = new BlockPos(hit.getX() - bp.width(r) / 2 + nudgeX, hit.getY() + yOff, hit.getZ() - bp.depth(r) / 2 + nudgeZ);
        String lock = view().getCompound("locks").getString(t.id);
        String why = !lock.isEmpty() ? lock : town == null ? "Not in your town" : GhostRenderer.check(town, origin.below(t.sink), bp, r);
        status = why == null ? "" : why;
        if (status.isEmpty()) warning = terrain(origin.below(t.sink), bp.width(r), bp.depth(r), bp.sy);
    }

    static boolean inside(@Nullable CompoundTag town, BlockPos p, int extra) {
        if (town == null) return false;
        BlockPos c = BlockPos.of(town.getLong("center"));
        long dx = p.getX() - c.getX(), dz = p.getZ() - c.getZ(), r = town.getInt("radius") + extra;
        return dx * dx + dz * dz <= r * r;
    }

    private static long lastTerrainAt;
    private static String lastTerrain = "";

    /** What the builders would clear away: anything not natural ground (the server allows it, so this only warns). */
    static String terrain(BlockPos o, int w, int d, int h) {
        long key = o.asLong() ^ (long) w << 50 ^ (long) d << 40;
        if (lastTerrainAt == key) return lastTerrain;
        lastTerrainAt = key;
        int built = 0, containers = 0;
        BlockPos.MutableBlockPos m = new BlockPos.MutableBlockPos();
        for (int x = 0; x < w; x++) for (int z = 0; z < d; z++) for (int y = 0; y < h; y++) {
            var s = level.getBlockState(m.set(o.getX() + x, o.getY() + y, o.getZ() + z));
            if (s.hasBlockEntity()) containers++;
            else if (!livingrealms.building.Construction.natural(s) && !(y == 0 && Planning.ROAD_MATERIALS.containsValue(s))) built++;
        }
        lastTerrain = containers > 0 ? "Builders will clear " + containers + " container" + (containers == 1 ? "" : "s") + " here"
                : built > 0 ? "Builders will clear " + built + " built block" + (built == 1 ? "" : "s") : "";
        return lastTerrain;
    }

    // ------------------------------------------------------------------ actions

    public static void act(String action, CompoundTag data) {
        if (townId == null) return;
        CompoundTag t = new CompoundTag();
        t.putUUID("id", townId);
        t.putString("action", action);
        t.put("data", data);
        Net.toServer("action", t);
    }

    /** Left click in the world: place the building, set a road point, or pick a planned project. */
    public static void click() {
        if (tool == null) { selectedPlot = hoveredPlot; return; }
        if (!status.isEmpty() || hit == null) return;
        if (ROAD.equals(tool)) {
            if (roadStart == null) { roadStart = hit; return; }
            CompoundTag d = new CompoundTag();
            d.putLong("a", roadStart.asLong());
            d.putLong("b", hit.asLong());
            d.putInt("width", roadWidth);
            d.putString("material", roadMaterial);
            act("road", d);
            roadStart = hit;
            return;
        }
        if (origin == null) return;
        CompoundTag d = new CompoundTag();
        d.putString("type", tool);
        d.putLong("pos", origin.asLong());
        d.putInt("rot", rot & 3);
        act("plan", d);
        nudgeX = nudgeZ = 0;
    }

    public static void select(@Nullable String id) {
        tool = id;
        roadStart = null;
        nudgeX = nudgeZ = yOff = 0;
        selectedPlot = -1;
    }

    private ArchitectView() {}
}
