package livingrealms.settlement;

import livingrealms.building.*;
import net.minecraft.core.BlockPos;
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
        int builders = Math.max(1, s.d.workers.getOrDefault(Job.BUILDER, 0));
        long active = s.plots.stream().filter(Plot::active).count();
        if (active >= Math.max(1, builders / 2) || s.wealth < 25) return;
        Category want = decide(level.random, s);
        BuildingType t = chooseType(level.random, s, want);
        if (t == null) return;
        for (int attempt = 0; attempt < 3; attempt++) {
            Rotation rot = Rotation.values()[level.random.nextInt(4)];
            Blueprint bp = BuildingCatalog.blueprint(t.blueprintKey(1, s.style));
            if (bp == null) return;
            BlockPos origin = findSite(level, s, bp.width(rot), bp.depth(rot));
            if (origin == null) continue;
            if (Planning.plan(level, s, t, origin, rot, true) == null) {
                Plot p = s.plots.get(s.plots.size() - 1);
                BlockPos c = p.center();
                if (c.distSqr(s.center) > 100) {
                    BlockPos from = new BlockPos(c.getX(), 0, p.box().maxZ() + 1);
                    Planning.road(level, s, from, s.center, 2, Procedural.styleFamily(s.style).equals("desert") ? "gravel" : "path");
                }
                return;
            }
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
            if (t.id.equals("livingrealms:town_hall") || t.category == Category.WALL) continue;
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
        RandomSource r = level.random;
        int radius = (int) (s.radius() * 0.8) - Math.max(w, d);
        if (radius < 8) radius = 8;
        for (int i = 0; i < 48; i++) {
            double dist = 10 + (radius - 10) * (i / 48.0);
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
            if (overlaps(s, x - 2, z - 2, x + w + 1, z + d + 1)) continue;
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
