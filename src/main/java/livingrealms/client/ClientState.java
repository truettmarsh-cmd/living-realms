package livingrealms.client;

import livingrealms.building.Blueprint;
import livingrealms.net.Net;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;

import javax.annotation.Nullable;
import java.util.*;

/** What the client knows: the open settlement's view, nearby towns and plots, and fetched blueprints. */
public final class ClientState {
    @Nullable public static CompoundTag view;
    public static List<CompoundTag> towns = new ArrayList<>();
    private static final Map<String, Blueprint> BLUEPRINTS = new HashMap<>();
    private static final Set<String> REQUESTED = new HashSet<>();

    @Nullable
    public static Blueprint blueprint(String key) {
        Blueprint b = BLUEPRINTS.get(key);
        if (b == null && REQUESTED.add(key)) {
            CompoundTag t = new CompoundTag();
            t.putString("key", key);
            Net.toServer("blueprint", t);
        }
        return b;
    }

    static void putBlueprint(CompoundTag t) {
        Blueprint b = Blueprint.fromTag(t);
        BLUEPRINTS.put(b.key, b);
    }

    public static void clear() {
        view = null;
        towns = new ArrayList<>();
        BLUEPRINTS.clear();
        REQUESTED.clear();
    }

    static void setTowns(CompoundTag t) {
        List<CompoundTag> l = new ArrayList<>();
        for (Tag e : t.getList("towns", Tag.TAG_COMPOUND)) l.add((CompoundTag) e);
        towns = l;
    }

    /** The town the player is standing in, if they own it. */
    @Nullable
    public static CompoundTag ownTownAt(net.minecraft.core.BlockPos pos) {
        for (CompoundTag t : towns) {
            if (!t.getBoolean("mine")) continue;
            var c = net.minecraft.core.BlockPos.of(t.getLong("center"));
            double r = t.getInt("radius");
            double dx = pos.getX() - c.getX(), dz = pos.getZ() - c.getZ();
            if (dx * dx + dz * dz <= r * r) return t;
        }
        return null;
    }

    public static ListTag list(String key) { return view == null ? new ListTag() : view.getList(key, Tag.TAG_COMPOUND); }

    private ClientState() {}
}
