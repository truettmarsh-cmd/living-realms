package livingrealms.settlement;

import livingrealms.building.*;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.Rotation;
import java.util.*;

/** Player plans always take precedence over villagers' own paid building projects. */
public final class AutoBuild {
    public static boolean pending(Plot p) { return p.active() || p.state == Plot.State.PLANNED; }
    public static boolean manualQueue(Settlement s) { return s.plots.stream().anyMatch(p -> pending(p) && !p.automatic); }
    public static boolean mayWork(Settlement s, Plot p) {
        return s.isAi() || !p.automatic || s.autoBuild && !manualQueue(s);
    }
    public static String status(Settlement s) {
        if (!s.autoBuild) return "Automatic building off";
        if (manualQueue(s)) return "Your queued projects come first";
        if (s.plots.stream().anyMatch(p -> pending(p) && p.automatic)) return "Villagers are building their chosen project";
        return "Villagers will plan when materials and a clear site are available";
    }

    public static void think(ServerLevel level, Settlement s) {
        if (s.isAi() || !s.autoBuild || !level.hasChunkAt(s.center) || s.plots.stream().anyMatch(AutoBuild::pending)
                || s.alarmUntil > level.getGameTime()) return;
        List<BuildingType> candidates = new ArrayList<>();
        if (!s.d.hasTownHall) {
            var hall = BuildingCatalog.get("livingrealms:town_hall");
            if (hall != null) candidates.add(hall);
        } else {
            Category need = AiPlanner.decide(level.random, s);
            for (BuildingType t : BuildingCatalog.types().values()) {
                if (t.category == Category.CIVIC || t.category == Category.ROAD || t.category == Category.WALL
                        || t.id.equals(CapitalCastles.ID) || s.cannotBuild(t) != null) continue;
                candidates.add(t);
            }
            Collections.shuffle(candidates, new Random(level.random.nextLong()));
            candidates.sort(Comparator.comparingInt(t -> t.category == need ? 0 : 1));
        }
        int sitesTried = 0;
        for (BuildingType t : candidates) {
            if (s.cannotBuild(t) != null) continue;
            Blueprint bp = BuildingCatalog.blueprint(t.blueprintKey(1, s.style));
            if (bp == null || !affordable(s, bp, new long[0])) continue;
            if (++sitesTried > 8) return;
            for (int attempt = 0; attempt < 3; attempt++) {
                Rotation rot = Rotation.values()[level.random.nextInt(4)];
                BlockPos at = AiPlanner.findSite(level, s, bp.width(rot), bp.depth(rot), s.capital && s.tier.number() >= 5);
                if (at == null) continue;
                BlockPos origin = at.below(t.sink);
                if (!clear(level, origin, bp, rot)) continue;
                long[] foundation = Construction.foundation(level, bp, origin, rot);
                if (!affordable(s, bp, foundation)) continue;
                if (Planning.plan(level, s, t, at, rot, false) == null) {
                    Plot p = s.plots.get(s.plots.size() - 1);
                    p.automatic = true;
                    p.priority = -2;
                    s.log("Villagers chose to build " + t.name + " while your queue was empty");
                    return;
                }
            }
        }
    }

    static boolean affordable(Settlement s, Blueprint bp, long[] foundation) {
        Map<Item, Integer> bill = new HashMap<>(bp.bill());
        Item base = Blueprint.costItem(Construction.foundationBlock(s));
        if (base != null) bill.merge(base, foundation.length, Integer::sum);
        return bill.entrySet().stream().allMatch(e -> s.stock.count(e.getKey()) >= e.getValue());
    }

    /** Check every block, including containers and upper floors; do not flatten player-built structures. */
    static boolean clear(ServerLevel level, BlockPos origin, Blueprint bp, Rotation rot) {
        for (int x = 0; x < bp.width(rot); x++) for (int z = 0; z < bp.depth(rot); z++) for (int y = 0; y < bp.sy; y++) {
            BlockPos pos = origin.offset(x, y, z);
            var state = level.getBlockState(pos);
            if (state.hasBlockEntity() || state.is(net.minecraft.tags.BlockTags.LOGS)) return false;
            if (y > 0 && !state.isAir() && !state.canBeReplaced() && !state.is(net.minecraft.tags.BlockTags.LEAVES)) return false;
            if (!Construction.natural(state) && !(y == 0 && Planning.ROAD_MATERIALS.containsValue(state))) return false;
        }
        return true;
    }
    private AutoBuild() {}
}
