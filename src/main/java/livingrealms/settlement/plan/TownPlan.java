package livingrealms.settlement.plan;

import livingrealms.building.*;
import livingrealms.settlement.Plot;
import livingrealms.settlement.Settlement;
import livingrealms.settlement.Tier;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.levelgen.structure.BoundingBox;

import javax.annotation.Nullable;
import java.util.*;

/**
 * A town's street plan, laid out once from its centre to the Capital radius and opened ring by ring as the town's
 * land grows. The town hall stands in the middle of a paved market place; four high streets run from the market
 * place to the edge (where gates would stand); square ring streets cross them every 28 to 34 blocks. Every
 * building is cut a lot along a street with its door facing it, in the district its trade belongs to, and the
 * street is paved from the market place to its door when it is built. Only the paved lengths and surveyed sites
 * are saved: the skeleton is a pure function of the centre.
 */
public final class TownPlan {
    public static final int VERSION = 1;
    /** Planned out to the Capital radius from the start, so streets never have to move. */
    public static final int SKELETON = Tier.CAPITAL.radius;
    /** Centre line of the market place round the hall (paved 5 wide: 13..17 blocks out). */
    public static final int PLAZA = 15;
    /** Centre lines of the ring streets. */
    public static final int[] RINGS = {34, 62, 94, 128};
    /** The hall's own precinct inside the market place. */
    public static final int PRECINCT = 12;

    public BlockPos center;
    public long seed;
    public final List<Street> streets = new ArrayList<>();
    /** Paved length of each street (absent: not paved). */
    public final Map<Integer, Integer> laid = new HashMap<>();
    /** Lots surveyed while loaded for planning while nobody is near: envelope corner (with ground y) and its front. */
    public final List<Site> sites = new ArrayList<>();
    /** Street lengths owed to buildings planned while the ground was not loaded: street id to along-distance. */
    public final Map<Integer, Integer> owed = new HashMap<>();
    /** Wall lines laid round a Village Expansion layout (null: the standard old-town and city walls). */
    @Nullable public List<Walls.Line> customWalls;
    /** Why the next wall piece could not be planned (not saved). */
    public transient String wallBlocked = "";
    /** Which street each automatically placed plot fronts, by plot id. */
    public final Map<Integer, Integer> frontage = new HashMap<>();

    public record Site(BlockPos corner, int size, Direction front, int street, int along) {}

    /** Where a building goes: origin (bounding box corner before sinking), rotation, and the street to pave to. */
    public record Placement(BlockPos origin, Rotation rot, int street, int along, Direction front) {}

    /** A street segment to pave: from and to along-distances. */
    public record Paving(Street street, int from, int to) {}

    public TownPlan(BlockPos center, long seed) {
        this.center = center;
        this.seed = seed;
        generate();
    }

    // ------------------------------------------------------------------ skeleton

    private void generate() {
        streets.clear();
        int cx = center.getX(), cz = center.getZ(), p = PLAZA, e = PLAZA + 2;
        // market place: north, south, west, east sides
        streets.add(new Street(0, RoadClass.PLAZA, cx - e, cz - p, Direction.EAST, 2 * e, -1, 0));
        streets.add(new Street(1, RoadClass.PLAZA, cx - e, cz + p, Direction.EAST, 2 * e, -1, 0));
        streets.add(new Street(2, RoadClass.PLAZA, cx - p, cz - e, Direction.SOUTH, 2 * e, -1, 0));
        streets.add(new Street(3, RoadClass.PLAZA, cx + p, cz - e, Direction.SOUTH, 2 * e, -1, 0));
        // high streets leave the market place from the middle of each side
        Direction[] arms = {Direction.NORTH, Direction.SOUTH, Direction.WEST, Direction.EAST};
        int[] armIds = new int[4];
        for (int i = 0; i < 4; i++) {
            Direction d = arms[i];
            armIds[i] = streets.size();
            streets.add(new Street(streets.size(), RoadClass.HIGH, cx + d.getStepX() * p, cz + d.getStepZ() * p, d, SKELETON - p - 2, i, e));
        }
        // ring streets: each side leaves its high street both ways and meets the next side at the corner
        for (int k : RINGS) for (int i = 0; i < 4; i++) {
            Direction d = arms[i];
            for (Direction side : new Direction[]{d.getClockWise(), d.getCounterClockWise()}) {
                Street full = new Street(streets.size(), RoadClass.STREET, cx + d.getStepX() * k, cz + d.getStepZ() * k, side, k, armIds[i], k - p);
                // a ring street stops short of a wall line rather than run through it
                int len = k;
                for (int t = 0; t <= k && len == k; t++)
                    for (Walls.Line l : Walls.standard()) {
                        BoundingBox slice = full.corridor(t, t);
                        int lo = Walls.minLevel(l, center, slice), hi = Walls.maxLevel(l, center, slice);
                        if (hi >= -1 && lo <= 1) { len = Math.max(0, t - 2); break; }
                    }
                streets.add(new Street(full.id, RoadClass.STREET, full.ax, full.az, side, len, armIds[i], k - p));
            }
        }
    }

    public List<Walls.Line> wallLines() { return customWalls != null ? customWalls : Walls.standard(); }

    /** True if a footprint reaches into a wall line's band (the wall and the strips kept free each side). */
    public boolean inWallBand(BoundingBox b) {
        for (Walls.Line l : wallLines()) if (Walls.inBand(l, center, b, Walls.BAND)) return true;
        return false;
    }

    /** How far along a street is inside the town's land (its centre line two blocks inside the edge). */
    public int open(Street st, int radius) {
        int t = -1;
        for (int i = 0; i <= st.length; i++) {
            long dx = st.x(i) - center.getX(), dz = st.z(i) - center.getZ();
            if (dx * dx + dz * dz > (long) (radius - 2) * (radius - 2)) break;
            t = i;
        }
        return t;
    }

    public boolean onStreet(BoundingBox b, int margin) {
        for (Street st : streets) if (overlap(st.corridor().inflatedBy(margin), b)) return true;
        return false;
    }

    /** True inside the market place and the castle ring around it (kept for a capital's castle). */
    public boolean inCore(BoundingBox b) {
        int c = Zoning.CORE;
        return b.maxX() >= center.getX() - c && b.minX() <= center.getX() + c && b.maxZ() >= center.getZ() - c && b.minZ() <= center.getZ() + c;
    }

    // ------------------------------------------------------------------ growth of buildings

    /** Blueprint at the highest level the type can reach (procedural buildings grow when upgraded). */
    public static Blueprint biggest(BuildingType t, String style) {
        Blueprint b = BuildingCatalog.blueprint(t.blueprintKey(t.maxLevel, style));
        return b != null ? b : BuildingCatalog.blueprint(t.blueprintKey(1, style));
    }

    /**
     * Origin of a building that grows from one blueprint to another: its front wall stays where it is (on the street)
     * and it stays centred along the street, growing back and to both sides.
     */
    public static BlockPos grownOrigin(BlockPos origin, Rotation rot, Blueprint from, Blueprint to) {
        int w1 = from.width(rot), d1 = from.depth(rot), w2 = to.width(rot), d2 = to.depth(rot);
        int x = origin.getX(), z = origin.getZ();
        Direction f = from.front(rot);
        if (f.getAxis() == Direction.Axis.Z) {
            x -= (w2 - w1) / 2;
            if (f == Direction.SOUTH) z += d1 - d2;
        } else {
            z -= (d2 - d1) / 2;
            if (f == Direction.EAST) x += w1 - w2;
        }
        return new BlockPos(x, origin.getY(), z);
    }

    /** The ground a plot will cover once fully upgraded: neighbours keep clear of it. */
    public static BoundingBox grownBox(Settlement s, Plot p) {
        BuildingType t = BuildingCatalog.get(p.typeId);
        Blueprint cur = p.blueprint();
        if (t == null || cur == null || p.typeId.equals("road") || !t.procedural()) return p.box();
        Blueprint max = biggest(t, s.style);
        BlockPos o = grownOrigin(p.origin, p.rotation, cur, max);
        return new BoundingBox(o.getX(), o.getY(), o.getZ(), o.getX() + max.width(p.rotation) - 1, o.getY() + max.sy - 1, o.getZ() + max.depth(p.rotation) - 1);
    }

    // ------------------------------------------------------------------ choosing a lot

    /** Finds the best lot for a building: its district, along a street of the right kind, facing it, on fit ground. */
    @Nullable
    public Placement place(Terrain terrain, Settlement s, BuildingType t) {
        Blueprint bp = BuildingCatalog.blueprint(t.blueprintKey(1, s.style));
        if (bp == null) return null;
        if (t.id.equals("livingrealms:town_hall")) {
            Placement hall = hall(terrain, s, t, bp);
            if (hall != null) return hall;
        }
        int radius = s.radius();
        int already = (int) s.plots.stream().filter(p -> { BuildingType o = BuildingCatalog.get(p.typeId); return o != null && o.category == t.category; }).count();
        Zoning.Rule rule = Zoning.rule(t, radius, already);
        if (t.category == Category.CIVIC) rule = new Zoning.Rule(Zoning.CORE, 48, Zoning.CORE, true, false, 2, 1);
        Blueprint max = biggest(t, s.style);
        List<BoundingBox> taken = new ArrayList<>();
        List<BoundingBox> noisy = new ArrayList<>(), homes = new ArrayList<>(), same = new ArrayList<>();
        List<Plot> walls = new ArrayList<>();
        for (Plot p : s.plots) {
            if (p.typeId.equals("road") || p.state == Plot.State.DEMOLISHING) continue;
            if (Walls.isWallPiece(p)) { walls.add(p); continue; }
            BoundingBox g = grownBox(s, p);
            taken.add(g);
            BuildingType o = BuildingCatalog.get(p.typeId);
            if (o == null) continue;
            if (Zoning.noisy(o)) noisy.add(g);
            if (o.category == Category.HOUSING) homes.add(g);
            if (o.category == t.category) same.add(g);
        }
        boolean capitalRing = s.capital;
        List<Candidate> cands = new ArrayList<>();
        for (Street st : streets) {
            int open = open(st, radius);
            if (open < 0) continue;
            if (st.cls == RoadClass.PLAZA && (capitalRing || !(t.category == Category.MARKET || t.category == Category.TAVERN
                    || t.category == Category.CHAPEL || t.category == Category.LIBRARY || t.category == Category.DECOR))) continue;
            for (Direction side : new Direction[]{st.dir.getClockWise(), st.dir.getCounterClockWise()}) {
                Direction front = side.getOpposite();
                Rotation rot = bp.facing(front);
                int wMax = st.alongX() ? max.width(rot) : max.depth(rot);
                int w1 = st.alongX() ? bp.width(rot) : bp.depth(rot);
                if (wMax < w1) wMax = w1;
                for (int a = 3; a + wMax - 1 <= open; a += 2) {
                    BoundingBox lot = lot(st, side, a, rot, max, bp, rule.setback() + jitter(st.id, a, side));
                    BoundingBox b1 = first(st, side, lot, rot, bp);
                    if (!inside(s, lot)) continue;
                    if (st.cls == RoadClass.PLAZA) { if (!inCore(lot) || overlap(lot, precinct())) continue; }
                    else if (inCore(lot)) continue;
                    double d = dist(b1);
                    if (d < rule.min() || d > rule.max()) continue;
                    if (rule.noHighInCore() && st.cls == RoadClass.HIGH && d < 40) continue;
                    if (crossesOther(st, lot) || inWallBand(lot)) continue;
                    boolean clash = false;
                    BoundingBox padded = lot.inflatedBy(rule.gap());
                    for (BoundingBox o : taken) if (o.intersects(padded.minX(), padded.minZ(), padded.maxX(), padded.maxZ())) { clash = true; break; }
                    for (Plot w : walls) if (!clash && w.covers(padded)) clash = true;
                    if (clash) continue;
                    double score = -Math.abs(d - rule.pref());
                    if (rule.wantsHigh() && (st.cls == RoadClass.HIGH || st.cls == RoadClass.PLAZA)) score += 15;
                    if (t.category == Category.HOUSING && st.cls == RoadClass.STREET) score += 6;
                    if (st.cls == RoadClass.PLAZA) score += 10;
                    if (t.category == Category.HOUSING && near(lot, noisy, 12)) score -= 25;
                    if (Zoning.noisy(t) && near(lot, homes, 10)) score -= 10;
                    if (t.category != Category.HOUSING && near(lot, same, 16)) score += 4;
                    score += Math.floorMod(Objects.hash(seed, st.id, a, side.ordinal()), 5) * 0.5;
                    cands.add(new Candidate(st, side, a, rot, lot, b1, score));
                }
            }
        }
        cands.sort(Comparator.comparingDouble(c -> -c.score));
        int tries = 0;
        for (Candidate c : cands) {
            if (++tries > 48) break;
            BlockPos at = ground(terrain, t, bp, c.rot, c.first);
            if (at == null) continue;
            int along = c.a + (c.st.alongX() ? c.lot.getXSpan() : c.lot.getZSpan()) + 1;
            return new Placement(at, c.rot, c.st.id, Math.min(along, c.st.length), c.side.getOpposite());
        }
        return null;
    }

    private record Candidate(Street st, Direction side, int a, Rotation rot, BoundingBox lot, BoundingBox first, double score) {}

    /** Town hall: in the middle of the market place, facing south down the high street, room left to grow. */
    @Nullable
    private Placement hall(Terrain terrain, Settlement s, BuildingType t, Blueprint bp) {
        Rotation rot = bp.facing(Direction.SOUTH);
        Blueprint max = biggest(t, s.style);
        int wm = max.width(rot), dm = max.depth(rot), w = bp.width(rot), d = bp.depth(rot);
        int x0 = center.getX() - wm / 2, z0 = center.getZ() - dm / 2;
        BoundingBox b1 = new BoundingBox(x0 + (wm - w) / 2, 0, z0 + dm - d, x0 + (wm - w) / 2 + w - 1, 0, z0 + dm - 1);
        for (Plot p : s.plots)
            if (!p.typeId.equals("road") && p.state != Plot.State.DEMOLISHING && (Walls.isWallPiece(p) ? p.covers(new BoundingBox(x0 - 1, 0, z0 - 1, x0 + wm, 0, z0 + dm))
                    : grownBox(s, p).intersects(x0 - 1, z0 - 1, x0 + wm, z0 + dm))) return null;
        BlockPos at = ground(terrain, t, bp, rot, b1);
        return at == null ? null : new Placement(at, rot, 1, PLAZA + 2, Direction.SOUTH);
    }

    BoundingBox precinct() {
        int c = PRECINCT;
        return new BoundingBox(center.getX() - c, 0, center.getZ() - c, center.getX() + c, 0, center.getZ() + c);
    }

    /** The lot for a fully grown building: its front edge {@code setback} off the street, starting {@code a} along it. */
    static BoundingBox lot(Street st, Direction side, int a, Rotation rot, Blueprint max, Blueprint bp, int setback) {
        int wAlong = Math.max(st.alongX() ? max.width(rot) : max.depth(rot), st.alongX() ? bp.width(rot) : bp.depth(rot));
        int dAcross = Math.max(st.alongX() ? max.depth(rot) : max.width(rot), st.alongX() ? bp.depth(rot) : bp.width(rot));
        int a0 = st.alongX() ? Math.min(st.x(a), st.x(a + wAlong - 1)) : Math.min(st.z(a), st.z(a + wAlong - 1));
        int h = st.half(), lo = st.cls.width - 1 - h;
        boolean positive = side.getStepX() + side.getStepZ() > 0;
        int c = st.alongX() ? st.az : st.ax;
        int edge = positive ? c + lo + 1 + setback : c - h - 1 - setback;
        int n0 = positive ? edge : edge - dAcross + 1;
        return st.alongX() ? new BoundingBox(a0, 0, n0, a0 + wAlong - 1, 0, n0 + dAcross - 1)
                : new BoundingBox(n0, 0, a0, n0 + dAcross - 1, 0, a0 + wAlong - 1);
    }

    /** The level 1 building inside its lot: front wall on the lot's street edge, centred along the street. */
    static BoundingBox first(Street st, Direction side, BoundingBox lot, Rotation rot, Blueprint bp) {
        int w = bp.width(rot), d = bp.depth(rot);
        boolean positive = side.getStepX() + side.getStepZ() > 0;
        if (st.alongX()) {
            int x = lot.minX() + (lot.getXSpan() - w) / 2;
            int z = positive ? lot.minZ() : lot.maxZ() - d + 1;
            return new BoundingBox(x, 0, z, x + w - 1, 0, z + d - 1);
        }
        int z = lot.minZ() + (lot.getZSpan() - d) / 2;
        int x = positive ? lot.minX() : lot.maxX() - w + 1;
        return new BoundingBox(x, 0, z, x + w - 1, 0, z + d - 1);
    }

    private int jitter(int street, int a, Direction side) {
        return Math.floorMod(Objects.hash(seed, street, a / 8, side.ordinal(), 7), 3) == 0 ? 1 : 0;
    }

    double dist(BoundingBox b) {
        double x = (b.minX() + b.maxX()) / 2.0 - center.getX(), z = (b.minZ() + b.maxZ()) / 2.0 - center.getZ();
        return Math.sqrt(x * x + z * z);
    }

    static boolean inside(Settlement s, BoundingBox b) {
        for (int[] c : new int[][]{{b.minX(), b.minZ()}, {b.maxX(), b.minZ()}, {b.minX(), b.maxZ()}, {b.maxX(), b.maxZ()}})
            if (!s.contains(new BlockPos(c[0], s.center.getY(), c[1]))) return false;
        return true;
    }

    /** Any street other than its own (one block clear), and its own street's corridor itself. */
    private boolean crossesOther(Street own, BoundingBox lot) {
        for (Street st : streets) {
            BoundingBox c = st.corridor();
            if (overlap(st == own ? c : c.inflatedBy(1), lot)) return true;
        }
        return false;
    }

    /** Footprints overlap (heights ignored). */
    public static boolean overlap(BoundingBox a, BoundingBox b) {
        return a.maxX() >= b.minX() && a.minX() <= b.maxX() && a.maxZ() >= b.minZ() && a.minZ() <= b.maxZ();
    }

    private static boolean near(BoundingBox b, List<BoundingBox> others, int range) {
        for (BoundingBox o : others) if (o.intersects(b.minX() - range, b.minZ() - range, b.maxX() + range, b.maxZ() + range)) return true;
        return false;
    }

    /** Fit ground for the level 1 building: loaded, dry, level enough and free of other builds; returns its origin. */
    @Nullable
    static BlockPos ground(Terrain terrain, BuildingType t, Blueprint bp, Rotation rot, BoundingBox b) {
        if (!terrain.loaded(b.minX() - 1, b.minZ() - 1, b.maxX() + 1, b.maxZ() + 1)) return null;
        List<Integer> hs = new ArrayList<>();
        for (int x = b.minX(); x <= b.maxX(); x += 2) for (int z = b.minZ(); z <= b.maxZ(); z += 2) {
            if (terrain.water(x, z)) return null;
            hs.add(terrain.height(x, z));
        }
        for (int[] c : new int[][]{{b.maxX(), b.minZ()}, {b.minX(), b.maxZ()}, {b.maxX(), b.maxZ()}}) {
            if (terrain.water(c[0], c[1])) return null;
            hs.add(terrain.height(c[0], c[1]));
        }
        Collections.sort(hs);
        int spread = hs.get(hs.size() - 1) - hs.get(0);
        boolean hillside = t.category == Category.MINE || t.category == Category.LUMBER || t.category == Category.TOWER || t.category == Category.CHAPEL;
        if (spread > (hillside ? 5 : b.getXSpan() * b.getZSpan() > 120 ? 3 : 2)) return null;
        BlockPos at = new BlockPos(b.minX(), hs.get(hs.size() / 2), b.minZ());
        if (!terrain.clear(at.below(t.sink), bp, rot)) return null;
        return at;
    }

    // ------------------------------------------------------------------ paving

    /** The street lengths to pave so a lot at {@code along} on a street is joined to the market place. */
    public List<Paving> connect(int streetId, int along, int radius) {
        List<Paving> out = new ArrayList<>();
        connect(streetId, along, radius, out);
        return out;
    }

    private void connect(int id, int along, int radius, List<Paving> out) {
        Street st = streets.get(id);
        if (st.parent >= 0) {
            Street parent = streets.get(st.parent);
            connect(st.parent, Math.min(parent.length, st.parentAt + parent.half() + 1), radius, out);
        }
        int open = st.cls == RoadClass.PLAZA ? st.length : open(st, radius);
        int want = st.cls == RoadClass.PLAZA ? st.length : Math.min(open, Math.max(along, ((along + 7) / 8) * 8));
        if (want <= 0) return;
        int have = laid.getOrDefault(id, -1);
        if (have >= want) return;
        for (Paving p : out) if (p.street == st && p.to >= want) return;
        out.add(new Paving(st, Math.max(0, have), want));
    }

    /** At founding: the whole market place and the first stretch of each high street. */
    public List<Paving> founding(int radius) {
        List<Paving> out = new ArrayList<>();
        for (Street st : streets) if (st.cls == RoadClass.HIGH) connect(st.id, 12, radius, out);
        return out;
    }

    public void paved(Paving p) {
        laid.merge(p.street.id, p.to, Math::max);
    }

    /** Laid road columns along the street centre lines: (x,z) packed. */
    public boolean paved(int x, int z) {
        for (Street st : streets) {
            int to = laid.getOrDefault(st.id, -1);
            if (to >= 0 && st.corridor(0, to).isInside(x, 0, z)) return true;
        }
        return false;
    }

    // ------------------------------------------------------------------ surveyed sites for off-screen planning

    /** Finds one open square lot of the given size along a street, for building while nobody is near. */
    @Nullable
    public Site survey(Terrain terrain, Settlement s, int size, boolean outskirts) {
        int radius = s.radius();
        List<BoundingBox> taken = new ArrayList<>();
        for (Plot p : s.plots) if (!p.typeId.equals("road") && p.state != Plot.State.DEMOLISHING && !Walls.isWallPiece(p)) taken.add(grownBox(s, p));
        for (Site o : sites) taken.add(new BoundingBox(o.corner.getX(), 0, o.corner.getZ(), o.corner.getX() + o.size - 1, 0, o.corner.getZ() + o.size - 1));
        List<Object[]> cands = new ArrayList<>();
        for (Street st : streets) {
            if (st.cls == RoadClass.PLAZA) continue;
            int open = open(st, radius);
            for (Direction side : new Direction[]{st.dir.getClockWise(), st.dir.getCounterClockWise()}) {
                for (int a = 3; a + size - 1 <= open; a += 4) {
                    BoundingBox lot = square(st, side, a, size);
                    if (!inside(s, lot) || inCore(lot) || crossesOther(st, lot) || inWallBand(lot)) continue;
                    BoundingBox padded = lot.inflatedBy(2);
                    if (taken.stream().anyMatch(o -> o.intersects(padded.minX(), padded.minZ(), padded.maxX(), padded.maxZ()))) continue;
                    double d = dist(lot);
                    double score = outskirts ? d : -d;
                    cands.add(new Object[]{score, st, side, a, lot});
                }
            }
        }
        cands.sort(Comparator.comparingDouble(o -> -(double) o[0]));
        int tries = 0;
        for (Object[] c : cands) {
            if (++tries > 24) break;
            BoundingBox b = (BoundingBox) c[4];
            if (!terrain.loaded(b.minX(), b.minZ(), b.maxX(), b.maxZ())) continue;
            List<Integer> hs = new ArrayList<>();
            boolean wet = false;
            for (int x = b.minX(); x <= b.maxX() && !wet; x += 2) for (int z = b.minZ(); z <= b.maxZ(); z += 2) {
                if (terrain.water(x, z)) { wet = true; break; }
                hs.add(terrain.height(x, z));
            }
            if (wet) continue;
            Collections.sort(hs);
            if (hs.get(hs.size() - 1) - hs.get(0) > 3) continue;
            Street st = (Street) c[1];
            Direction side = (Direction) c[2];
            int a = (int) c[3];
            return new Site(new BlockPos(b.minX(), hs.get(hs.size() / 2), b.minZ()), size, side.getOpposite(), st.id, Math.min(st.length, a + size + 1));
        }
        return null;
    }

    static BoundingBox square(Street st, Direction side, int a, int size) {
        int a0 = st.alongX() ? Math.min(st.x(a), st.x(a + size - 1)) : Math.min(st.z(a), st.z(a + size - 1));
        int h = st.half(), lo = st.cls.width - 1 - h;
        boolean positive = side.getStepX() + side.getStepZ() > 0;
        int c = st.alongX() ? st.az : st.ax;
        int n0 = positive ? c + lo + 2 : c - h - 1 - size;
        return st.alongX() ? new BoundingBox(a0, 0, n0, a0 + size - 1, 0, n0 + size - 1) : new BoundingBox(n0, 0, a0, n0 + size - 1, 0, a0 + size - 1);
    }

    /** A building on a surveyed site: front wall on the street edge, centred along it. */
    public static BlockPos onSite(Site site, Blueprint bp, Rotation rot) {
        int w = bp.width(rot), d = bp.depth(rot), n = site.size;
        int x = site.corner.getX(), z = site.corner.getZ();
        switch (site.front) {
            case NORTH -> { x += (n - w) / 2; }
            case SOUTH -> { x += (n - w) / 2; z += n - d; }
            case WEST -> { z += (n - d) / 2; }
            default -> { z += (n - d) / 2; x += n - w; }
        }
        return new BlockPos(x, site.corner.getY(), z);
    }

    public double distance(Site site) {
        return dist(new BoundingBox(site.corner.getX(), 0, site.corner.getZ(), site.corner.getX() + site.size - 1, 0, site.corner.getZ() + site.size - 1));
    }

    // ------------------------------------------------------------------ saving

    public CompoundTag save() {
        CompoundTag t = new CompoundTag();
        t.putInt("v", VERSION);
        t.putLong("seed", seed);
        t.putLong("center", center.asLong());
        int[] l = new int[laid.size() * 2];
        int i = 0;
        for (var e : laid.entrySet()) { l[i++] = e.getKey(); l[i++] = e.getValue(); }
        t.putIntArray("laid", l);
        ListTag sl = new ListTag();
        for (Site s : sites) {
            CompoundTag st = new CompoundTag();
            st.putLong("c", s.corner.asLong());
            st.putIntArray("i", new int[]{s.size, s.front.get3DDataValue(), s.street, s.along});
            sl.add(st);
        }
        t.put("sites", sl);
        int[] f = new int[frontage.size() * 2];
        i = 0;
        for (var e : frontage.entrySet()) { f[i++] = e.getKey(); f[i++] = e.getValue(); }
        t.putIntArray("front", f);
        int[] o = new int[owed.size() * 2];
        i = 0;
        for (var e : owed.entrySet()) { o[i++] = e.getKey(); o[i++] = e.getValue(); }
        t.putIntArray("owed", o);
        if (customWalls != null) {
            ListTag wl = new ListTag();
            for (Walls.Line line : customWalls) wl.add(Walls.saveLine(line));
            t.put("walls", wl);
        }
        return t;
    }

    public static TownPlan load(CompoundTag t) {
        TownPlan p = new TownPlan(BlockPos.of(t.getLong("center")), t.getLong("seed"));
        int[] l = t.getIntArray("laid");
        for (int i = 0; i + 1 < l.length; i += 2) p.laid.put(l[i], l[i + 1]);
        for (Tag e : t.getList("sites", Tag.TAG_COMPOUND)) {
            CompoundTag st = (CompoundTag) e;
            int[] v = st.getIntArray("i");
            if (v.length == 4 && v[2] < p.streets.size() && v[2] >= -1)
                p.sites.add(new Site(BlockPos.of(st.getLong("c")), v[0], Direction.from3DDataValue(v[1]), v[2], v[3]));
        }
        int[] f = t.getIntArray("front");
        for (int i = 0; i + 1 < f.length; i += 2) p.frontage.put(f[i], f[i + 1]);
        int[] o = t.getIntArray("owed");
        for (int i = 0; i + 1 < o.length; i += 2) if (o[i] < p.streets.size()) p.owed.put(o[i], o[i + 1]);
        if (t.contains("walls")) {
            p.customWalls = new ArrayList<>();
            for (Tag e : t.getList("walls", Tag.TAG_COMPOUND)) { Walls.Line line = Walls.loadLine((CompoundTag) e); if (line != null) p.customWalls.add(line); }
        }
        return p;
    }
}
