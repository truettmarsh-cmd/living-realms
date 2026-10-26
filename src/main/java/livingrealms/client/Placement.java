package livingrealms.client;

import livingrealms.Registry;
import livingrealms.building.Blueprint;
import livingrealms.building.BuildingCatalog;
import livingrealms.building.BuildingType;
import livingrealms.net.Net;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;

import javax.annotation.Nullable;
import java.util.UUID;

/** Placing planned buildings and roads in the world with the Architect's Plans. */
public final class Placement {
    public enum Mode { NONE, BUILDING, ROAD }

    public static Mode mode = Mode.NONE;
    public static String typeId = "";
    public static int rot;
    public static int yOff;
    @Nullable public static UUID town;
    public static String style = "minecraft:plains";
    @Nullable public static BlockPos roadStart;
    public static int roadWidth = 3;
    public static String roadMaterial = "path";
    public static String status = "";

    public static void startBuilding(UUID townId, String townStyle, String type) {
        mode = Mode.BUILDING;
        town = townId;
        style = townStyle;
        typeId = type;
        yOff = 0;
        Minecraft.getInstance().setScreen(null);
        hint("Right-click with the Architect's Plans to place. Scroll to rotate, Shift+scroll to raise or lower.");
    }

    public static void startRoad(UUID townId, String material, int width) {
        mode = Mode.ROAD;
        town = townId;
        roadMaterial = material;
        roadWidth = width;
        roadStart = null;
        Minecraft.getInstance().setScreen(null);
        hint("Right-click where the road starts, then where it ends. Keep clicking to continue it; Shift+right-click to finish.");
    }

    public static void cancel() {
        mode = Mode.NONE;
        roadStart = null;
    }

    static void hint(String s) {
        var p = Minecraft.getInstance().player;
        if (p != null) p.displayClientMessage(Component.literal(s).withStyle(ChatFormatting.GOLD), false);
    }

    public static boolean holdingPlans() {
        var p = Minecraft.getInstance().player;
        return p != null && (p.getMainHandItem().is(Registry.PLANNER.get()) || p.getOffhandItem().is(Registry.PLANNER.get()));
    }

    /** Right-click with the plans. */
    public static void use(boolean shift) {
        if (mode == Mode.NONE) {
            CompoundTag t = new CompoundTag();
            t.putString("tab", "build");
            Net.toServer("open", t);
            return;
        }
        if (shift) { cancel(); return; }
        BlockPos hit = hit();
        if (hit == null || town == null) return;
        if (mode == Mode.BUILDING) {
            BlockPos o = origin(hit);
            if (o == null) return;
            CompoundTag d = new CompoundTag();
            d.putString("type", typeId);
            d.putLong("pos", o.asLong());
            d.putInt("rot", rot);
            action("plan", d);
            cancel();
        } else {
            if (roadStart == null) { roadStart = hit; return; }
            CompoundTag d = new CompoundTag();
            d.putLong("a", roadStart.asLong());
            d.putLong("b", hit.asLong());
            d.putInt("width", roadWidth);
            d.putString("material", roadMaterial);
            action("road", d);
            roadStart = hit;
        }
    }

    public static void action(String action, CompoundTag data) {
        CompoundTag t = new CompoundTag();
        t.putUUID("id", town);
        t.putString("action", action);
        t.put("data", data);
        Net.toServer("action", t);
    }

    public static void scroll(double delta, boolean shift) {
        int dir = delta > 0 ? 1 : -1;
        if (mode == Mode.BUILDING && shift) yOff = Math.max(-16, Math.min(16, yOff + dir));
        else if (mode == Mode.BUILDING) rot = (rot + dir) & 3;
        else if (mode == Mode.ROAD) roadWidth = Math.max(1, Math.min(7, roadWidth + dir));
    }

    @Nullable
    public static BlockPos hit() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) return null;
        HitResult r = mc.player.pick(96, 1f, false);
        if (r instanceof BlockHitResult b && r.getType() == HitResult.Type.BLOCK) return b.getBlockPos();
        return null;
    }

    public static Rotation rotation() { return Rotation.values()[rot & 3]; }

    @Nullable
    public static BuildingType type() { return BuildingCatalog.get(typeId); }

    @Nullable
    public static Blueprint blueprint() {
        BuildingType t = type();
        return t == null ? null : ClientState.blueprint(t.blueprintKey(1, style));
    }

    /** Bounding box corner for a building centred on the block looked at. */
    @Nullable
    public static BlockPos origin(BlockPos hit) {
        BuildingType t = type();
        if (t == null) return null;
        Blueprint bp = blueprint();
        int w = bp != null ? bp.width(rotation()) : (rot % 2 == 0 ? t.w : t.d);
        int d = bp != null ? bp.depth(rotation()) : (rot % 2 == 0 ? t.d : t.w);
        return new BlockPos(hit.getX() - w / 2, hit.getY() + yOff, hit.getZ() - d / 2);
    }

    private Placement() {}
}
