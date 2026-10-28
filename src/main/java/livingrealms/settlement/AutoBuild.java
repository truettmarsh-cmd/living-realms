package livingrealms.settlement;

import livingrealms.building.*;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.Rotation;
import livingrealms.settlement.vx.VxLayout;
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

    /** Surveyed sites are squares this wide, checked this high. */
    public static final int SITE = 17, SITE_HEIGHT = 16, MAX_SITES = 4;

    public static void think(ServerLevel level, Settlement s) {
        if (s.isAi() || !s.autoBuild || Autonomy.underAttack(level, s)) return;
        boolean home = level.hasChunkAt(s.center);
        if (home) survey(level, s);
        if (s.plots.stream().anyMatch(AutoBuild::pending)) return;
        if (!home) { away(s, level.random); return; }
        List<BuildingType> candidates = candidates(s, level.random);
        if (Realms.on() && !s.layout.isEmpty() && fromLayout(level, s, candidates)) return;
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

    /** Village Expansion's street plan comes first: a lot zoned for this kind of building, then the street in front of it. */
    static boolean fromLayout(ServerLevel level, Settlement s, List<BuildingType> candidates) {
        boolean castle = s.capital && s.tier.number() >= 5;
        for (BuildingType t : candidates) {
            if (s.cannotBuild(t) != null) continue;
            Blueprint bp = BuildingCatalog.blueprint(t.blueprintKey(1, s.style));
            if (bp == null || !affordable(s, bp, new long[0])) continue;
            for (VxLayout.Slot slot : s.layout.slots) {
                if (slot.used || t.category != Category.CIVIC && !VxLayout.accepts(slot.kind).contains(t.category)) continue;
                for (Rotation rot : facing(slot)) {
                    int w = bp.width(rot), d = bp.depth(rot);
                    if (w > slot.w + 4 || d > slot.d + 4) continue;
                    int x = slot.x - w / 2, z = slot.z - d / 2;
                    if (!level.hasChunksAt(x - 1, z - 1, x + w + 1, z + d + 1)) continue;
                    BlockPos corner = new BlockPos(x, s.center.getY(), z), far = new BlockPos(x + w - 1, s.center.getY(), z + d - 1);
                    if (!s.contains(corner) || !s.contains(far) || !s.contains(new BlockPos(x, 0, z + d - 1)) || !s.contains(new BlockPos(x + w - 1, 0, z))) continue;
                    if (AiPlanner.overlaps(s, x - 1, z - 1, x + w, z + d) || castle && CapitalCastles.reserved(s, x - 1, z - 1, x + w, z + d)) continue;
                    BlockPos at = new BlockPos(x, Construction.ground(level, x + w / 2, z + d / 2), z);
                    BlockPos origin = at.below(t.sink);
                    if (!clear(level, origin, bp, rot)) continue;
                    long[] foundation = Construction.foundation(level, bp, origin, rot);
                    if (!affordable(s, bp, foundation) || Planning.plan(level, s, t, at, rot, false) != null) continue;
                    Plot p = s.plots.get(s.plots.size() - 1);
                    p.automatic = true;
                    p.priority = -2;
                    for (VxLayout.Slot o : s.layout.slots)
                        if (Math.abs(o.x - slot.x) * 2 < o.w + w && Math.abs(o.z - slot.z) * 2 < o.d + d) o.used = true;
                    s.log("Villagers chose to build " + t.name + " on a planned " + slot.kind + " lot");
                    street(level, s, slot);
                    return true;
                }
            }
        }
        return false;
    }

    /** Rotations with the long side along the street first. */
    static List<Rotation> facing(VxLayout.Slot slot) {
        boolean alongX = slot.hasRoad() && Math.abs(slot.roadA().getX() - slot.roadB().getX()) >= Math.abs(slot.roadA().getZ() - slot.roadB().getZ());
        return alongX ? List.of(Rotation.NONE, Rotation.CLOCKWISE_180, Rotation.CLOCKWISE_90, Rotation.COUNTERCLOCKWISE_90)
                : List.of(Rotation.CLOCKWISE_90, Rotation.COUNTERCLOCKWISE_90, Rotation.NONE, Rotation.CLOCKWISE_180);
    }

    /** Lays the lot's street as an automatic road project, once. */
    static void street(ServerLevel level, Settlement s, VxLayout.Slot slot) {
        if (!slot.hasRoad() || !s.layout.paved.add(slot.roadA ^ (slot.roadB * 31))) return;
        BlockPos a = slot.roadA(), b = slot.roadB();
        if (!s.contains(a) || !s.contains(b)) return;
        String material = Procedural.styleFamily(s.style).equals("desert") ? "gravel" : "path";
        if (Planning.road(level, s, a, b, 3, material) == null) {
            Plot road = s.plots.get(s.plots.size() - 1);
            road.automatic = true;
            road.priority = -2;
        }
    }

    /** While loaded, keeps a few clear squares on file (one new or rechecked per step) for planning while nobody is near. */
    static void survey(ServerLevel level, Settlement s) {
        boolean castle = s.capital && s.tier.number() >= 5;
        if (!s.sites.isEmpty()) {
            BlockPos at = s.sites.remove(0);
            if (level.hasChunksAt(at.getX(), at.getZ(), at.getX() + SITE, at.getZ() + SITE)) {
                if (usable(s, at) && clearBox(level, at, SITE, SITE, SITE_HEIGHT)) s.sites.add(at);
            } else s.sites.add(at);
        }
        if (s.sites.size() >= MAX_SITES) return;
        BlockPos at = AiPlanner.findSite(level, s, SITE, SITE, castle);
        if (at != null && usable(s, at) && clearBox(level, at, SITE, SITE, SITE_HEIGHT)) s.sites.add(at);
    }

    /** Inside the land, clear of plots, other surveyed sites and the future castle. */
    static boolean usable(Settlement s, BlockPos at) {
        int x0 = at.getX(), z0 = at.getZ(), x1 = x0 + SITE - 1, z1 = z0 + SITE - 1;
        if (!s.contains(at) || !s.contains(new BlockPos(x1, at.getY(), z1)) || !s.contains(new BlockPos(x0, at.getY(), z1)) || !s.contains(new BlockPos(x1, at.getY(), z0))) return false;
        if (AiPlanner.overlaps(s, x0 - 2, z0 - 2, x1 + 2, z1 + 2)) return false;
        if (s.capital && s.tier.number() >= 5 && CapitalCastles.reserved(s, x0 - 2, z0 - 2, x1 + 2, z1 + 2)) return false;
        for (BlockPos o : s.sites) if (o != at && x0 <= o.getX() + SITE + 1 && x1 + 2 >= o.getX() && z0 <= o.getZ() + SITE + 1 && z1 + 2 >= o.getZ()) return false;
        return true;
    }

    /** Plans on a surveyed site without reading terrain; the foundation is measured when the site loads. */
    static void away(Settlement s, net.minecraft.util.RandomSource random) {
        if (s.sites.isEmpty()) return;
        for (BuildingType t : candidates(s, random)) {
            if (t.sink != 0 || s.cannotBuild(t) != null) continue;
            Blueprint bp = BuildingCatalog.blueprint(t.blueprintKey(1, s.style));
            if (bp == null || bp.sy > SITE_HEIGHT) continue;
            Rotation rot = null;
            for (Rotation r : Rotation.values()) if (bp.width(r) <= SITE && bp.depth(r) <= SITE) { rot = r; break; }
            if (rot == null || !affordable(s, bp, new long[bp.width(rot) * bp.depth(rot) / 4])) continue;
            for (BlockPos at : new ArrayList<>(s.sites)) {
                s.sites.remove(at);
                if (!usable(s, at)) continue;
                if (Planning.plan(null, s, t, at, rot, false) == null) {
                    Plot p = s.plots.get(s.plots.size() - 1);
                    p.automatic = true;
                    p.priority = -2;
                    s.log("Villagers chose to build " + t.name + " while you were away");
                    return;
                }
            }
            return;
        }
    }

    static List<BuildingType> candidates(Settlement s, net.minecraft.util.RandomSource random) {
        List<BuildingType> candidates = new ArrayList<>();
        if (!s.d.hasTownHall) {
            var hall = BuildingCatalog.get("livingrealms:town_hall");
            if (hall != null) candidates.add(hall);
        } else {
            Category need = AiPlanner.decide(random, s);
            for (BuildingType t : BuildingCatalog.types().values()) {
                if (t.category == Category.CIVIC || t.category == Category.ROAD || t.category == Category.WALL
                        || t.id.equals(CapitalCastles.ID) || s.cannotBuild(t) != null) continue;
                candidates.add(t);
            }
            Collections.shuffle(candidates, new Random(random.nextLong()));
            candidates.sort(Comparator.comparingInt(t -> t.category == need ? 0 : 1));
        }
        return candidates;
    }

    static boolean affordable(Settlement s, Blueprint bp, long[] foundation) {
        Map<Item, Integer> bill = new HashMap<>(bp.bill());
        Item base = Blueprint.costItem(Construction.foundationBlock(s));
        if (base != null) bill.merge(base, foundation.length, Integer::sum);
        return bill.entrySet().stream().allMatch(e -> s.stock.count(e.getKey()) >= e.getValue());
    }

    /** Check every block, including containers and upper floors; do not flatten player-built structures. */
    static boolean clear(ServerLevel level, BlockPos origin, Blueprint bp, Rotation rot) {
        return clearBox(level, origin, bp.width(rot), bp.depth(rot), bp.sy);
    }

    static boolean clearBox(ServerLevel level, BlockPos origin, int w, int d, int h) {
        for (int x = 0; x < w; x++) for (int z = 0; z < d; z++) for (int y = 0; y < h; y++) {
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
