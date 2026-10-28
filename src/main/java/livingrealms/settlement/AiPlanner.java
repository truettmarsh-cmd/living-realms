package livingrealms.settlement;

import livingrealms.building.*;
import livingrealms.settlement.plan.*;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.Rotation;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** How an AI settlement decides what to build next and where. */
public final class AiPlanner {

    public static void think(ServerLevel level, Settlement s) {
        if (!level.hasChunkAt(s.center)) return;
        // claim the next rank as soon as it is earned
        if (s.tier.next() != null && s.tierBlockers().isEmpty()) {
            s.tier = s.tier.next();
        }
        AutoBuild.payDebts(level, s, false);
        // After an attack, wealth goes to soldiers before new buildings.
        if (Strategy.rearming(SettlementManager.get(level.getServer()), s, Simulation.AI_GUARDS[s.tier.ordinal()], level.getGameTime())) return;
        int builders = Math.max(1, s.d.workers.getOrDefault(Job.BUILDER, 0));
        long active = s.plots.stream().filter(Plot::active).count();
        if (active >= Math.max(1, builders / 2) || s.wealth < 25) return;
        Category want = decide(level.random, s);
        BuildingType t = chooseType(level.random, s, want);
        if (t == null) return;
        Blueprint bp = BuildingCatalog.blueprint(t.blueprintKey(1, s.style));
        if (bp == null) return;
        // a lot along the town's streets, in the district its trade belongs to, door to the street
        TownPlan plan = s.plan();
        TownPlan.Placement at = plan.place(new WorldTerrain(level), s, t);
        if (at != null && Planning.plan(level, s, t, at.origin(), at.rot(), true) == null) {
            plan.frontage.put(s.plots.get(s.plots.size() - 1).id, at.street());
            AutoBuild.pave(level, s, plan.connect(at.street(), at.along(), s.radius()), false);
            return;
        }
        for (int attempt = 0; attempt < 3; attempt++) {
            BlockPos origin = zonedSite(level, s, t, bp);
            if (origin == null) continue;
            Rotation rot = towardCentre(s, bp, origin);
            if (Planning.plan(level, s, t, origin, rot, true) == null) {
                accessRoad(level, s, s.plots.get(s.plots.size() - 1), false);
                return;
            }
        }
    }

    /** An open site in the building's district, square enough for any rotation. */
    @Nullable
    static BlockPos zonedSite(ServerLevel level, Settlement s, BuildingType t, Blueprint bp) {
        int m = Math.max(bp.sx, bp.sz);
        int count = (int) s.plots.stream().filter(p -> { BuildingType o = BuildingCatalog.get(p.typeId); return o != null && o.category == t.category; }).count();
        Zoning.Rule rule = Zoning.rule(t, s.radius(), count);
        BlockPos at = findSite(level, s, m, m, s.capital && s.tier.number() >= 5, rule.min());
        if (at == null) return null;
        Rotation rot = towardCentre(s, bp, at);
        int w = bp.width(rot), d = bp.depth(rot);
        // centre the turned building on the square
        return new BlockPos(at.getX() + (m - w) / 2, at.getY(), at.getZ() + (m - d) / 2);
    }

    /** The rotation that turns the front door toward the town centre. */
    static Rotation towardCentre(Settlement s, Blueprint bp, BlockPos at) {
        int m = Math.max(bp.sx, bp.sz);
        int dx = s.center.getX() - (at.getX() + m / 2), dz = s.center.getZ() - (at.getZ() + m / 2);
        Direction side = Math.abs(dx) >= Math.abs(dz) ? (dx >= 0 ? Direction.EAST : Direction.WEST) : (dz >= 0 ? Direction.SOUTH : Direction.NORTH);
        return bp.facing(side);
    }

    /** A path straight out of the front door, then on to the nearest road (or the centre). */
    static void accessRoad(ServerLevel level, Settlement s, Plot p, boolean automatic) {
        Blueprint bp = p.blueprint();
        if (bp == null) return;
        Direction front = bp.front(p.rotation);
        BlockPos door = bp.doorWorld(p.origin, p.rotation);
        var box = p.box();
        BlockPos out = switch (front) {
            case NORTH -> new BlockPos(door.getX(), door.getY(), box.minZ() - 2);
            case SOUTH -> new BlockPos(door.getX(), door.getY(), box.maxZ() + 2);
            case WEST -> new BlockPos(box.minX() - 2, door.getY(), door.getZ());
            default -> new BlockPos(box.maxX() + 2, door.getY(), door.getZ());
        };
        if (out.distSqr(s.center) <= 100) return;
        BlockPos to = s.center;
        double best = Double.MAX_VALUE;
        for (Plot r : s.plots) {
            if (!r.typeId.equals("road")) continue;
            Blueprint rb = r.blueprint();
            if (rb == null) continue;
            for (int i = 0; i < rb.size(); i += 3) {
                if (rb.state(i).isAir()) continue;
                BlockPos q = new BlockPos(r.origin.getX() + rb.lx(i), out.getY(), r.origin.getZ() + rb.lz(i));
                double dd = out.distSqr(q);
                if (dd < best) { best = dd; to = q; }
            }
        }
        boolean desert = Procedural.styleFamily(s.style).equals("desert");
        String material = RoadClass.LANE.material(s.tier, desert);
        BlockPos step = out.relative(front.getOpposite(), 2);
        for (BlockPos[] leg : new BlockPos[][]{{step, out}, {out, to}}) {
            if (leg[0].distSqr(leg[1]) < 1 || Planning.road(level, s, leg[0], leg[1], 2, material) != null) continue;
            Plot road = s.plots.get(s.plots.size() - 1);
            road.automatic = automatic;
            road.priority = automatic ? -2 : -1;
        }
    }

    static Category decide(RandomSource r, Settlement s) {
        Settlement.Derived d = s.d;
        if (d.housing - s.population <= 2) return Category.HOUSING;
        if (s.count("livingrealms:farm_field") + s.standing(Category.FARM) < Math.max(1, s.population / 6)) return Category.FARM;
        if (s.tier.number() >= 2 && s.standing(Category.CONSTRUCTION) == 0 && !planned(s, Category.CONSTRUCTION)) return Category.CONSTRUCTION;
        if (s.tier.number() >= 2 && s.standing(Category.BARRACKS) == 0 && !planned(s, Category.BARRACKS)) return Category.BARRACKS;
        if (s.tier.number() >= 2 && s.standing(Category.SMITHY) == 0 && s.has("smithing") && !planned(s, Category.SMITHY)) return Category.SMITHY;
        if (s.tier.number() >= 3 && s.standing(Category.TOWER) < s.tier.number() - 1) return Category.TOWER;
        List<Category> pool = new ArrayList<>(Arrays.asList(Category.HOUSING, Category.HOUSING, Category.WORKSHOP, Category.FARM,
                Category.DECOR, Category.STORAGE, Category.LUMBER, Category.TAVERN, Category.LIBRARY, Category.MARKET, Category.CHAPEL,
                Category.MINE, Category.STABLE));
        return pool.get(r.nextInt(pool.size()));
    }

    static boolean planned(Settlement s, Category c) {
        for (Plot p : s.plots) {
            BuildingType t = BuildingCatalog.get(p.typeId);
            if (t != null && t.category == c) return true;
        }
        return false;
    }

    /** Prefers buildings of the village's own style, then the settlement's procedural ones. */
    @Nullable
    static BuildingType chooseType(RandomSource r, Settlement s, Category c) {
        List<BuildingType> own = new ArrayList<>(), family = new ArrayList<>(), proc = new ArrayList<>();
        String fam = Procedural.styleFamily(s.style);
        for (BuildingType t : BuildingCatalog.types().values()) {
            if (t.category != c || s.cannotBuild(t) != null) continue;
            if (t.id.equals(CapitalCastles.ID) || t.id.equals("livingrealms:town_hall") || t.category == Category.WALL) continue;
            if (t.procedural()) proc.add(t);
            else if (t.style.equals(s.style)) own.add(t);
            else if (Procedural.styleFamily(t.style).equals(fam) && t.style.startsWith("minecraft:")) family.add(t);
        }
        if (!own.isEmpty() && (proc.isEmpty() || r.nextInt(4) > 0)) return own.get(r.nextInt(own.size()));
        if (!family.isEmpty() && (proc.isEmpty() || r.nextBoolean())) return family.get(r.nextInt(family.size()));
        return proc.isEmpty() ? null : proc.get(r.nextInt(proc.size()));
    }

    /** A fairly flat, open spot inside the territory, as close to the center as possible. */
    @Nullable
    public static BlockPos findSite(ServerLevel level, Settlement s, int w, int d) {
        return findSite(level, s, w, d, true);
    }

    public static BlockPos findSite(ServerLevel level, Settlement s, int w, int d, boolean reserveCastle) {
        return findSite(level, s, w, d, reserveCastle, 10);
    }

    /** Sites from {@code minDist} outward; the market place and its castle ring stay free. */
    public static BlockPos findSite(ServerLevel level, Settlement s, int w, int d, boolean reserveCastle, int minDist) {
        RandomSource r = level.random;
        int radius = (int) (s.radius() * 0.8) - Math.max(w, d);
        int from = Math.max(Zoning.CORE + Math.max(w, d) / 2 + 2, minDist);
        if (radius < from) radius = Math.min(from + 8, s.radius() - Math.max(w, d) / 2 - 1);
        for (int i = 0; i < 48; i++) {
            double dist = from + (radius - from) * (i / 48.0);
            double a = r.nextDouble() * Math.PI * 2;
            int x = s.center.getX() + (int) (Math.cos(a) * dist) - w / 2, z = s.center.getZ() + (int) (Math.sin(a) * dist) - d / 2;
            if (!level.hasChunksAt(x - 2, z - 2, x + w + 2, z + d + 2)) continue;
            int[] hs = {Construction.ground(level, x, z), Construction.ground(level, x + w - 1, z), Construction.ground(level, x, z + d - 1),
                    Construction.ground(level, x + w - 1, z + d - 1), Construction.ground(level, x + w / 2, z + d / 2)};
            int min = Arrays.stream(hs).min().getAsInt(), max = Arrays.stream(hs).max().getAsInt();
            if (max - min > (w * d > 120 ? 3 : 2)) continue;
            Arrays.sort(hs);
            int y = hs[2];
            if (!level.getBlockState(new BlockPos(x + w / 2, y, z + d / 2)).getFluidState().isEmpty()) continue;
            if (reserveCastle && CapitalCastles.reserved(s, x - 2, z - 2, x + w + 1, z + d + 1)) continue;
            if (overlaps(s, x - 2, z - 2, x + w + 1, z + d + 1)) continue;
            if (s.plan().onStreet(new net.minecraft.world.level.levelgen.structure.BoundingBox(x, 0, z, x + w - 1, 0, z + d - 1), 1)) continue;
            if (!open(level, x, y, z, w, d)) continue;
            return new BlockPos(x, y, z);
        }
        return null;
    }

    static boolean overlaps(Settlement s, int x0, int z0, int x1, int z1) {
        for (Plot p : s.plots) {
            if (p.typeId.equals("road")) continue;
            var b = p.box();
            if (x0 <= b.maxX() && x1 >= b.minX() && z0 <= b.maxZ() && z1 >= b.minZ()) return true;
        }
        return false;
    }

    /** No walls, floors or roofs (a generated village's houses) inside the footprint. */
    static boolean open(ServerLevel level, int x, int y, int z, int w, int d) {
        BlockPos.MutableBlockPos m = new BlockPos.MutableBlockPos();
        for (int dx = -1; dx <= w; dx += 2) for (int dz = -1; dz <= d; dz += 2) for (int dy = -1; dy <= 4; dy++) {
            m.set(x + dx, y + dy, z + dz);
            if (!Construction.natural(level.getBlockState(m))) return false;
        }
        return true;
    }

    private AiPlanner() {}
}
