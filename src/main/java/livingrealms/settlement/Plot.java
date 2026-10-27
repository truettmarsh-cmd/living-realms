package livingrealms.settlement;

import livingrealms.building.Blueprint;
import livingrealms.building.BuildingCatalog;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.levelgen.structure.BoundingBox;

import javax.annotation.Nullable;
import java.util.UUID;

/** A building site: planned, under construction, standing, being upgraded or torn down. */
public final class Plot {
    public enum State { PLANNED, BUILDING, BUILT, UPGRADING, REPAIRING, DEMOLISHING }

    public int id;
    public String typeId;
    public int level = 1;
    public String blueprintKey;
    public BlockPos origin;
    public Rotation rotation = Rotation.NONE;
    public State state = State.PLANNED;
    /** Build steps done: foundation blocks first, then the blueprint. */
    public int placed;
    /** Steps paid for while nobody was near; placed as soon as the area loads. */
    public int paid;
    public long[] foundation = new long[0];
    public int priority;
    public boolean ai;
    public boolean automatic;
    /** Planned while unloaded: the foundation is measured when the site loads. */
    public boolean unsited;
    /** Finished while unloaded: counts as built, its paid blocks are placed when the site loads. */
    public boolean deferred;
    /** One-off blueprint (roads) stored with the plot. */
    @Nullable public CompoundTag customBlueprint;
    // runtime status, synced to clients
    public String status = "";
    @Nullable public UUID builder;

    public Blueprint blueprint() {
        Blueprint b = BuildingCatalog.blueprint(blueprintKey);
        if (b == null && customBlueprint != null) {
            b = Blueprint.fromTag(customBlueprint);
            BuildingCatalog.putBlueprint(b);
        }
        return b;
    }

    public int totalSteps() {
        Blueprint b = blueprint();
        return foundation.length + (b == null ? 0 : b.size());
    }

    public boolean active() {
        return state == State.BUILDING || state == State.UPGRADING || state == State.REPAIRING || state == State.DEMOLISHING;
    }

    /** True while the building counts for the town (it keeps working while upgraded or repaired). */
    public boolean standing() {
        return state == State.BUILT || state == State.UPGRADING || state == State.REPAIRING;
    }

    public BoundingBox box() {
        Blueprint b = blueprint();
        int w = b == null ? 1 : b.width(rotation), d = b == null ? 1 : b.depth(rotation), h = b == null ? 1 : b.sy;
        return new BoundingBox(origin.getX(), origin.getY(), origin.getZ(), origin.getX() + w - 1, origin.getY() + h - 1, origin.getZ() + d - 1);
    }

    public BlockPos center() {
        BoundingBox bb = box();
        return new BlockPos((bb.minX() + bb.maxX()) / 2, origin.getY() + 1, (bb.minZ() + bb.maxZ()) / 2);
    }

    public float progress() {
        int t = totalSteps();
        return t == 0 ? 1 : Math.min(1f, placed / (float) t);
    }

    public CompoundTag save() {
        CompoundTag t = new CompoundTag();
        t.putInt("id", id);
        t.putString("type", typeId);
        t.putInt("level", level);
        t.putString("bp", blueprintKey);
        t.put("origin", NbtUtils.writeBlockPos(origin));
        t.putString("rot", rotation.name());
        t.putString("state", state.name());
        t.putInt("placed", placed);
        t.putInt("paid", paid);
        t.putLongArray("found", foundation);
        t.putInt("prio", priority);
        t.putBoolean("ai", ai);
        t.putBoolean("automatic", automatic);
        if (unsited) t.putBoolean("unsited", true);
        if (deferred) t.putBoolean("deferred", true);
        if (customBlueprint != null) t.put("custom", customBlueprint);
        return t;
    }

    public static Plot load(CompoundTag t) {
        Plot p = new Plot();
        p.id = t.getInt("id");
        p.typeId = t.getString("type");
        p.level = Math.max(1, t.getInt("level"));
        p.blueprintKey = t.getString("bp");
        p.origin = NbtUtils.readBlockPos(t.getCompound("origin"));
        p.rotation = Rotation.valueOf(t.getString("rot"));
        p.state = State.valueOf(t.getString("state"));
        p.placed = t.getInt("placed");
        p.paid = t.getInt("paid");
        p.foundation = t.getLongArray("found");
        p.priority = t.getInt("prio");
        p.ai = t.getBoolean("ai");
        p.automatic = t.getBoolean("automatic");
        p.unsited = t.getBoolean("unsited");
        p.deferred = t.getBoolean("deferred");
        if (t.contains("custom")) p.customBlueprint = t.getCompound("custom");
        return p;
    }

    /** Smaller form for clients: no foundation list, plus progress and status. */
    public CompoundTag toClient() {
        CompoundTag t = save();
        t.remove("found");
        t.remove("custom");
        t.putInt("total", totalSteps());
        t.putInt("foundN", foundation.length);
        t.putString("status", status);
        return t;
    }
}
