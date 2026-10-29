package livingrealms.settlement;

import livingrealms.building.*;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.Rotation;
import livingrealms.settlement.plan.*;
import livingrealms.settlement.vx.VxLayout;
import livingrealms.settlement.vx.VxPlan;
import java.util.*;

/** Player plans always take precedence over villagers' own paid building projects. */
public final class AutoBuild {
    public static boolean pending(Plot p) { return p.active() || p.state == Plot.State.PLANNED; }
    public static boolean manualQueue(Settlement s) { return s.plots.stream().anyMatch(p -> pending(p) && !p.automatic); }
    public static boolean mayWork(Settlement s, Plot p) {
        if (p.automatic && !s.autoWalls && Walls.isWallPiece(p)) return false;
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
        if (home) { survey(level, s); payDebts(level, s, true); }
        if (s.plots.stream().anyMatch(p -> pending(p) && mayWork(s, p) || pending(p) && !p.automatic)) return;
        if (!home) { away(s, level.random); return; }
        List<BuildingType> candidates = candidates(s, level.random);
        boolean ve = Realms.on() && !s.layout.isEmpty();
        // the walls take every other turn once they are unlocked
        boolean wallsFirst = !lastWasWall(s);
        if (wallsFirst && walls(level, s)) return;
        if (ve && fromLayout(level, s, candidates)) return;
        TownPlan plan = s.plan();
        Terrain terrain = new WorldTerrain(level);
        int sitesTried = 0;
        // Village Expansion's streets and ours would cross: with its layout, only its lots or the open-site fallback
        if (!ve) for (BuildingType t : candidates) {
            if (s.cannotBuild(t) != null) continue;
            Blueprint bp = BuildingCatalog.blueprint(t.blueprintKey(1, s.style));
            if (bp == null || !affordable(s, bp, new long[0])) continue;
            if (++sitesTried > 8) break;
            TownPlan.Placement at = plan.place(terrain, s, t);
            if (at == null) continue;
            long[] foundation = Construction.foundation(level, bp, at.origin().below(t.sink), at.rot());
            if (!affordable(s, bp, foundation)) continue;
            if (Planning.plan(level, s, t, at.origin(), at.rot(), false) == null) {
                Plot p = s.plots.get(s.plots.size() - 1);
                p.automatic = true;
                p.priority = -2;
                plan.frontage.put(p.id, at.street());
                s.log("Villagers chose to build " + t.name + " while your queue was empty");
                if (t.category == Category.CIVIC) pave(level, s, plan.founding(s.radius()), true);
                pave(level, s, plan.connect(at.street(), at.along(), s.radius()), true);
                return;
            }
        }
        // no lot along the streets fits: an open site in the right district, door turned toward the town centre
        sitesTried = 0;
        for (BuildingType t : candidates) {
            if (s.cannotBuild(t) != null) continue;
            Blueprint bp = BuildingCatalog.blueprint(t.blueprintKey(1, s.style));
            if (bp == null || !affordable(s, bp, new long[0])) continue;
            if (++sitesTried > 8) break;
            for (int attempt = 0; attempt < 3; attempt++) {
                BlockPos at = AiPlanner.zonedSite(level, s, t, bp);
                if (at == null) continue;
                Rotation rot = AiPlanner.towardCentre(s, bp, at);
                BlockPos origin = at.below(t.sink);
                if (!clear(level, origin, bp, rot)) continue;
                long[] foundation = Construction.foundation(level, bp, origin, rot);
                if (!affordable(s, bp, foundation)) continue;
                if (Planning.plan(level, s, t, at, rot, false) == null) {
                    Plot p = s.plots.get(s.plots.size() - 1);
                    p.automatic = true;
                    p.priority = -2;
                    s.log("Villagers chose to build " + t.name + " while your queue was empty");
                    AiPlanner.accessRoad(level, s, p, true);
                    return;
                }
            }
        }
        if (!wallsFirst) walls(level, s);
    }

    static boolean lastWasWall(Settlement s) {
        for (int i = s.plots.size() - 1; i >= 0; i--) {
            Plot p = s.plots.get(i);
            if (!p.automatic || p.typeId.equals("road")) continue;
            return Walls.isWallPiece(p);
        }
        return false;
    }

    /** Plans the next gatehouse, tower or stretch of wall, if the town has walls coming and can pay for the piece. */
    static boolean walls(ServerLevel level, Settlement s) {
        if (!s.autoWalls || Walls.unlocked(s).isEmpty()) return false;
        if (Realms.on() && !s.layout.isEmpty()) livingrealms.settlement.vx.VxPlan.walls(s);
        Walls.Plan plan = Walls.next(new WorldTerrain(level), s);
        if (plan == null) return false;
        if (plan.blueprint() != null) {
            long[] foundation = Construction.foundation(level, plan.blueprint(), plan.origin(), Rotation.NONE);
            if (!affordable(s, plan.blueprint(), foundation)) { s.plan().wallBlocked = "waiting for materials"; return false; }
        }
        String err = Walls.queue(s, plan, true);
        if (err != null) { s.plan().wallBlocked = err; return false; }
        Plot p = s.plots.get(s.plots.size() - 1);
        if (plan.blueprint() != null) s.log("Villagers began a " + plan.type().name.toLowerCase(Locale.ROOT) + " on the " + Walls.side(s, p.center()) + " side");
        Walls.Line line = s.plan().wallLines().get(plan.piece().line());
        int[] street = Walls.gateStreet(s, line, plan.piece());
        if (street != null) pave(level, s, s.plan().connect(street[0], street[1], s.radius()), true);
        else if (plan.piece().kind() == Walls.Kind.GATE && plan.edge() != null) {
            // a Village Expansion street: paved through the gate, a little way in and out
            var e = plan.edge();
            int gx = s.center.getX() + e.x(plan.piece().tc()), gz = s.center.getZ() + e.z(plan.piece().tc());
            BlockPos in = new BlockPos(gx - 8 * e.ux(), s.center.getY(), gz - 8 * e.uz()), out = new BlockPos(gx + 8 * e.ux(), s.center.getY(), gz + 8 * e.uz());
            if (s.contains(in) && s.contains(out) && Planning.road(level, s, in, out, 3, RoadClass.STREET.material(s.tier, s.style)) == null) {
                Plot road = s.plots.get(s.plots.size() - 1);
                road.automatic = true;
                road.priority = -2;
            }
        }
        return true;
    }

    /** Lays street lengths as road projects (automatic ones for player towns). Without a loaded world they are owed. */
    public static void pave(ServerLevel level, Settlement s, List<TownPlan.Paving> pavings, boolean automatic) {
        TownPlan plan = s.plan();
        for (TownPlan.Paving pv : pavings) {
            Street st = pv.street();
            if (pv.to() <= pv.from()) { plan.paved(pv); continue; }
            BlockPos a = new BlockPos(st.x(pv.from()), s.center.getY(), st.z(pv.from())), b = new BlockPos(st.x(pv.to()), s.center.getY(), st.z(pv.to()));
            if (level == null || !level.hasChunksAt(Math.min(a.getX(), b.getX()) - 3, Math.min(a.getZ(), b.getZ()) - 3, Math.max(a.getX(), b.getX()) + 3, Math.max(a.getZ(), b.getZ()) + 3)) {
                plan.owed.merge(st.id, pv.to(), Math::max);
                continue;
            }
            String err = Planning.road(level, s, a, b, st.cls.width, st.cls.material(s.tier, s.style));
            if (err == null) {
                Plot road = s.plots.get(s.plots.size() - 1);
                road.automatic = automatic;
                road.ai = s.isAi();
                road.priority = automatic ? -2 : -1;
            }
            plan.paved(pv);
        }
    }

    /** Streets owed by buildings planned while nobody was near, paved once the ground is loaded. */
    static void payDebts(ServerLevel level, Settlement s, boolean automatic) {
        if (s.townPlan == null || s.townPlan.owed.isEmpty()) return;
        TownPlan plan = s.townPlan;
        Map<Integer, Integer> owed = new HashMap<>(plan.owed);
        plan.owed.clear();
        owed.forEach((id, along) -> pave(level, s, plan.connect(id, along, s.radius()), automatic));
    }

    /** Village Expansion's street plan comes first: a lot in the right district, the building facing its street, then the street. */
    static boolean fromLayout(ServerLevel level, Settlement s, List<BuildingType> candidates) {
        boolean castle = s.capital && s.tier.number() >= 5;
        for (BuildingType t : candidates) {
            if (s.cannotBuild(t) != null) continue;
            Blueprint bp = BuildingCatalog.blueprint(t.blueprintKey(1, s.style));
            if (bp == null || !affordable(s, bp, new long[0])) continue;
            for (VxLayout.Slot slot : VxPlan.ranked(s, t)) {
                VxPlan.Fit fit = VxPlan.fit(s, slot, bp);
                if (fit == null) continue;
                int x = fit.x(), z = fit.z(), w = bp.width(fit.rot()), d = bp.depth(fit.rot());
                if (!level.hasChunksAt(x - 1, z - 1, x + w + 1, z + d + 1)) continue;
                BlockPos corner = new BlockPos(x, s.center.getY(), z), far = new BlockPos(x + w - 1, s.center.getY(), z + d - 1);
                if (!s.contains(corner) || !s.contains(far) || !s.contains(new BlockPos(x, 0, z + d - 1)) || !s.contains(new BlockPos(x + w - 1, 0, z))) continue;
                if (AiPlanner.overlaps(s, x - 1, z - 1, x + w, z + d) || castle && CapitalCastles.reserved(s, x - 1, z - 1, x + w, z + d)) continue;
                if (s.plan().customWalls != null) {
                    // keep off the wall's band, and off lots whose street would have to run through the wall
                    if (s.plan().inWallBand(new net.minecraft.world.level.levelgen.structure.BoundingBox(x, 0, z, x + w - 1, 0, z + d - 1))) continue;
                    var f = bp.front(fit.rot());
                    int fx = x + w / 2 + f.getStepX() * (w / 2 + 2), fz = z + d / 2 + f.getStepZ() * (d / 2 + 2);
                    if (Walls.breach(s, fx, fz)) { slot.used = true; continue; }
                }
                BlockPos at = new BlockPos(x, Construction.ground(level, x + w / 2, z + d / 2), z);
                BlockPos origin = at.below(t.sink);
                if (!clear(level, origin, bp, fit.rot())) continue;
                long[] foundation = Construction.foundation(level, bp, origin, fit.rot());
                if (!affordable(s, bp, foundation) || Planning.plan(level, s, t, at, fit.rot(), false) != null) continue;
                Plot p = s.plots.get(s.plots.size() - 1);
                p.automatic = true;
                p.priority = -2;
                for (VxLayout.Slot o : s.layout.slots)
                    if (Math.abs(o.x - slot.x) * 2 < o.w + w && Math.abs(o.z - slot.z) * 2 < o.d + d) o.used = true;
                slot.used = true;
                s.log("Villagers chose to build " + t.name + " on a planned " + slot.kind + " lot");
                street(level, s, slot);
                return true;
            }
        }
        return false;
    }

    /** Lays the lot's street as an automatic road project, once: main streets get the better paving. */
    static void street(ServerLevel level, Settlement s, VxLayout.Slot slot) {
        if (!slot.hasRoad() || !s.layout.paved.add(slot.roadA ^ (slot.roadB * 31))) return;
        BlockPos a = slot.roadA(), b = slot.roadB();
        // pave the part inside the town's land: walk each end in until it is
        if (!s.contains(a)) { BlockPos t = a; a = b; b = t; }
        if (!s.contains(a)) return;
        BlockPos far = b;
        int n = Math.max(Math.abs(far.getX() - a.getX()), Math.abs(far.getZ() - a.getZ()));
        for (int i = n; i > 0 && !s.contains(b); i--)
            b = new BlockPos(a.getX() + (int) Math.round((far.getX() - a.getX()) * i / (double) n), far.getY(), a.getZ() + (int) Math.round((far.getZ() - a.getZ()) * i / (double) n));
        // a street that would cross a wall line away from its gates is paved up to the wall on each side, not through it
        BlockPos rest = null, restEnd = null;
        if (s.plan().customWalls != null) {
            if (a.distSqr(s.center) > b.distSqr(s.center)) { BlockPos t = a; a = b; b = t; }
            BlockPos end = b;
            int m = Math.max(1, Math.max(Math.abs(end.getX() - a.getX()), Math.abs(end.getZ() - a.getZ())));
            int first = -1, last = -1;
            for (int i = 0; i <= m; i++) {
                if (Walls.breach(s, at(a, end, i, m).getX(), at(a, end, i, m).getZ())) { if (first < 0) first = i; last = i; }
                else if (first >= 0) break;
            }
            if (first >= 0) {
                if (last + 3 < m) { rest = at(a, end, last + 3, m); restEnd = end; }
                b = at(a, end, Math.max(0, first - 3), m);
            }
        }
        if (rest != null && rest.distSqr(restEnd) >= 4) {
            if (Planning.road(level, s, rest, restEnd, 3, (slot.primary ? RoadClass.HIGH : RoadClass.STREET).material(s.tier, s.style)) == null) {
                Plot road = s.plots.get(s.plots.size() - 1);
                road.automatic = true;
                road.priority = -2;
            }
        }
        if (a.distSqr(b) < 4) return;
        String material = (slot.primary ? RoadClass.HIGH : RoadClass.STREET).material(s.tier, s.style);
        if (Planning.road(level, s, a, b, 3, material) == null) {
            Plot road = s.plots.get(s.plots.size() - 1);
            road.automatic = true;
            road.priority = -2;
        }
    }

    static BlockPos at(BlockPos a, BlockPos b, int i, int n) {
        return new BlockPos(a.getX() + (int) Math.round((b.getX() - a.getX()) * i / (double) n), b.getY(), a.getZ() + (int) Math.round((b.getZ() - a.getZ()) * i / (double) n));
    }

    /** While loaded, keeps a few lots along the streets on file (one new or rechecked per step) for planning while nobody is near. */
    static void survey(ServerLevel level, Settlement s) {
        if (Realms.on() && !s.layout.isEmpty()) return;
        TownPlan plan = s.plan();
        s.sites.clear();
        if (!plan.sites.isEmpty()) {
            TownPlan.Site site = plan.sites.remove(0);
            BlockPos at = site.corner();
            if (level.hasChunksAt(at.getX(), at.getZ(), at.getX() + site.size(), at.getZ() + site.size())) {
                if (!AiPlanner.overlaps(s, at.getX() - 2, at.getZ() - 2, at.getX() + site.size() + 1, at.getZ() + site.size() + 1)
                        && clearBox(level, at, site.size(), site.size(), SITE_HEIGHT)) plan.sites.add(site);
            } else plan.sites.add(site);
        }
        if (plan.sites.size() >= MAX_SITES) return;
        TownPlan.Site site = plan.survey(new WorldTerrain(level), s, SITE, plan.sites.size() % 2 == 1);
        if (site != null && clearBox(level, site.corner(), SITE, SITE, SITE_HEIGHT)) plan.sites.add(site);
    }

    /** Plans on a surveyed lot without reading terrain: its district, facing its street; the foundation and street follow when it loads. */
    static void away(Settlement s, net.minecraft.util.RandomSource random) {
        TownPlan plan = s.plan();
        // sites surveyed before towns had a street plan: square, door toward the centre, no street of their own
        for (BlockPos at : s.sites) {
            int dx = s.center.getX() - (at.getX() + SITE / 2), dz = s.center.getZ() - (at.getZ() + SITE / 2);
            Direction front = Math.abs(dx) >= Math.abs(dz) ? (dx >= 0 ? Direction.EAST : Direction.WEST) : (dz >= 0 ? Direction.SOUTH : Direction.NORTH);
            plan.sites.add(new TownPlan.Site(at, SITE, front, -1, 0));
        }
        s.sites.clear();
        if (plan.sites.isEmpty()) return;
        for (BuildingType t : candidates(s, random)) {
            if (t.sink != 0 || s.cannotBuild(t) != null) continue;
            Blueprint bp = BuildingCatalog.blueprint(t.blueprintKey(1, s.style));
            if (bp == null || bp.sy > SITE_HEIGHT) continue;
            int count = (int) s.plots.stream().filter(p -> { BuildingType o = BuildingCatalog.get(p.typeId); return o != null && o.category == t.category; }).count();
            Zoning.Rule rule = Zoning.rule(t, s.radius(), count);
            for (TownPlan.Site site : new ArrayList<>(plan.sites)) {
                Rotation rot = bp.facing(site.front());
                if (bp.width(rot) > site.size() || bp.depth(rot) > site.size()) break;
                double d = plan.distance(site);
                if (site.street() >= 0 && (d < rule.min() || d > rule.max())) continue;
                if (!affordable(s, bp, new long[bp.width(rot) * bp.depth(rot) / 4])) break;
                if (Planning.plan(null, s, t, TownPlan.onSite(site, bp, rot), rot, false) == null) {
                    plan.sites.remove(site);
                    Plot p = s.plots.get(s.plots.size() - 1);
                    p.automatic = true;
                    p.priority = -2;
                    if (site.street() >= 0) {
                        plan.frontage.put(p.id, site.street());
                        plan.owed.merge(site.street(), site.along(), Math::max);
                    }
                    s.log("Villagers chose to build " + t.name + " while you were away");
                    return;
                }
            }
        }
    }

    static List<BuildingType> candidates(Settlement s, net.minecraft.util.RandomSource random) {
        List<BuildingType> candidates = new ArrayList<>();
        if (!s.d.hasTownHall) {
            var hall = BuildingCatalog.get("livingrealms:town_hall");
            if (hall != null) candidates.add(hall);
        } else {
            Category need = AiPlanner.decide(random, s);
            List<BuildingType> offStyle = new ArrayList<>();
            for (BuildingType t : BuildingCatalog.types().values()) {
                if (t.category == Category.CIVIC || t.category == Category.ROAD || t.category == Category.WALL
                        || t.id.equals(CapitalCastles.ID) || s.cannotBuild(t) != null) continue;
                // the town's own look: procedural buildings and templates of its style family only
                if (BuildingCatalog.fits(t, s.style)) candidates.add(t);
                else if (t.category == need) offStyle.add(t);
            }
            Collections.shuffle(candidates, new Random(random.nextLong()));
            candidates.sort(Comparator.comparingInt(t -> t.category == need ? 0 : 1));
            // nothing of the needed kind in the town's style: the nearest style's (never a far one), after everything in style
            if (candidates.stream().noneMatch(t -> t.category == need)) {
                String kin = BuildingCatalog.kin(Procedural.styleFamily(s.style));
                offStyle.removeIf(t -> !Procedural.styleFamily(t.style).equals(kin) || BuildingCatalog.excludedStyle(t.style));
                Collections.shuffle(offStyle, new Random(random.nextLong()));
                candidates.addAll(offStyle);
            }
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
    public static boolean clear(ServerLevel level, BlockPos origin, Blueprint bp, Rotation rot) {
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
