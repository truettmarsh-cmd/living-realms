package livingrealms.settlement.plan;

import livingrealms.building.Blueprint;
import livingrealms.building.BuildingCatalog;
import livingrealms.building.BuildingType;
import livingrealms.building.Category;
import livingrealms.building.Procedural;
import livingrealms.settlement.Planning;
import livingrealms.settlement.Plot;
import livingrealms.settlement.Settlement;
import livingrealms.settlement.Tier;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LadderBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.structure.BoundingBox;

import javax.annotation.Nullable;
import java.util.*;

/**
 * Town walls on the town plan. A wall line is a square with chamfered corners round the centre (an octagon: every
 * column with {@code max(|dx|-a, |dz|-a, |dx|+|dz|-b)} between -1 and 1 is wall, three thick). Its band is kept free of
 * lots from the town's founding, so the wall never has to cut through anything the villagers built.
 *
 * <ul>
 * <li>The old-town wall runs just outside the first ring street (a 38) and comes at Village rank with Fortifications;
 * the city wall runs outside the third ring street (a 98) at City rank. The old wall stays as an inner wall, its
 * gates open, as in towns that outgrew their first circuit; suburbs, fields, woods and mines lie between and beyond.</li>
 * <li>A gatehouse stands wherever a high street (or, with Village Expansion, a street) crosses the line: two towers
 * and an arch over a passage as wide as the street, paved with it. There are no other openings.</li>
 * <li>Towers stand at the corners and at most 22 blocks apart along the walls (well within bow range of each other),
 * projecting outward to flank the wall face. Gate and side towers have a door and a ladder up to the wall walk.</li>
 * <li>Each column follows the ground: the walk is five blocks above it and steps at most one block per column. Deep water
 * and cliffs that drop six blocks outward are left as they are: they already defend the town.</li>
 * <li>A wall section that meets a building it did not plan for (a player's) bows outward round it and back.</li>
 * </ul>
 * Each gatehouse, tower and wall section is an ordinary paid construction project with its own blueprint.
 */
public final class Walls {
    /** Height of the wall walk above the ground, tower roof above the walk, gate passage clear height. */
    public static final int H = 5, ROOF = 4, PASSAGE = 4;
    /** Longest stretch between towers (blocks) and the gap kept free on each side of the wall's band. */
    public static final int SPAN = 22, BAND = 4;
    public static final String WALL = "livingrealms:wall", TOWER = "livingrealms:wall_tower", GATE = "livingrealms:gatehouse";

    /** One circuit: half-size a of the square, b of the chamfer (|dx|+|dz| = b), the rank it comes at, and its gates. */
    public record Line(int index, int a, int b, Tier tier, List<Integer> gates) {
        public int c() { return b - a; }
    }

    /** The old-town wall outside the first ring street and the city wall outside the third (high streets cross at the middle of each side). */
    public static List<Line> standard() {
        return List.of(line(0, 38, 72, Tier.VILLAGE), line(1, 98, 174, Tier.CITY));
    }

    static Line line(int index, int a, int b, Tier tier) {
        int c = b - a;
        List<Integer> gates = new ArrayList<>();
        for (int e = 0; e < 8; e += 2) gates.add(e * 10_000 + c); // the middle of each straight side
        return new Line(index, a, b, tier, gates);
    }

    /** Distance of a column from the line: negative inside, 0 on its centre, positive outside. */
    public static int level(Line l, int dx, int dz) {
        int x = Math.abs(dx), z = Math.abs(dz);
        return Math.max(Math.max(x - l.a, z - l.a), x + z - l.b);
    }

    /** Smallest and largest level over a footprint (level grows with |dx| and |dz|, so these are exact). */
    public static int minLevel(Line l, BlockPos c, BoundingBox b) {
        int x = clampAbs(b.minX() - c.getX(), b.maxX() - c.getX()), z = clampAbs(b.minZ() - c.getZ(), b.maxZ() - c.getZ());
        return level(l, x, z);
    }

    public static int maxLevel(Line l, BlockPos c, BoundingBox b) {
        int x = Math.max(Math.abs(b.minX() - c.getX()), Math.abs(b.maxX() - c.getX()));
        int z = Math.max(Math.abs(b.minZ() - c.getZ()), Math.abs(b.maxZ() - c.getZ()));
        return level(l, x, z);
    }

    static int clampAbs(int lo, int hi) { return lo <= 0 && hi >= 0 ? 0 : Math.min(Math.abs(lo), Math.abs(hi)); }

    /** True if the footprint reaches into the wall's band (the wall plus {@code margin} each side). */
    public static boolean inBand(Line l, BlockPos c, BoundingBox b, int margin) {
        return minLevel(l, c, b) <= margin && maxLevel(l, c, b) >= -margin;
    }

    // ------------------------------------------------------------------ geometry

    /** A straight run of the line: centre column p(t) = start + t * dir, t = 0..len; u points outward (one column per level). */
    public record Edge(int index, int x0, int z0, int dx, int dz, int len, int ux, int uz, boolean straight) {
        public int x(int t) { return x0 + dx * t; }
        public int z(int t) { return z0 + dz * t; }
        /** Outward diagonal of a chamfer, or u for a straight side. */
        int nx() { return straight ? ux : Integer.signum(x0 + dx * len / 2); }
        int nz() { return straight ? uz : Integer.signum(z0 + dz * len / 2); }
        double step() { return straight ? 1 : Math.sqrt(2); }
    }

    /** Eight edges round the line, clockwise from the east side, relative to the centre. */
    public static List<Edge> edges(Line l) {
        int a = l.a, c = l.c(), d = a - c;
        return List.of(
                new Edge(0, a, -c, 0, 1, 2 * c, 1, 0, true),
                new Edge(1, a, c, -1, 1, d, 1, 0, false),
                new Edge(2, c, a, -1, 0, 2 * c, 0, 1, true),
                new Edge(3, -c, a, -1, -1, d, -1, 0, false),
                new Edge(4, -a, c, 0, -1, 2 * c, -1, 0, true),
                new Edge(5, -a, -c, 1, -1, d, -1, 0, false),
                new Edge(6, -c, -a, 1, 0, 2 * c, 0, -1, true),
                new Edge(7, c, -a, 1, 1, d, 1, 0, false));
    }

    public enum Kind { GATE, TOWER, WALL }

    /** One construction: a gatehouse or tower centred at tc, or the wall from t0 to t1, on an edge. */
    public record Piece(int line, int index, Kind kind, int edge, int t0, int t1, int tc) {
        public String key(Settlement s) { return "wall:" + s.id.toString().substring(0, 8) + ":" + line + ":" + index; }
    }

    /** Short chamfers get one tower in their middle instead of one at each end. */
    static boolean shortChamfer(Line l) { return l.a - l.c() < 12; }

    /** Every piece of a line in building order: gatehouses, then towers, then the wall between them. */
    public static List<Piece> pieces(Line l) {
        List<Edge> edges = edges(l);
        List<Piece> gates = new ArrayList<>(), towers = new ArrayList<>(), walls = new ArrayList<>();
        boolean merged = shortChamfer(l);
        for (Edge e : edges) {
            TreeMap<Integer, Kind> stations = new TreeMap<>();
            if (merged && !e.straight) stations.put(e.len / 2, Kind.TOWER);
            else if (!merged) stations.put(0, Kind.TOWER);
            for (int g : l.gates) if (g / 10_000 == e.index) stations.put(g % 10_000, Kind.GATE);
            // more towers so no stretch is longer than SPAN
            List<Integer> fixed = new ArrayList<>(stations.keySet());
            List<Integer> bounds = new ArrayList<>(fixed);
            if (!stations.containsKey(0)) bounds.add(0, 0);
            bounds.add(e.len);
            for (int i = 0; i + 1 < bounds.size(); i++) {
                int from = bounds.get(i), to = bounds.get(i + 1);
                int n = (int) Math.ceil((to - from) * e.step() / SPAN);
                for (int k = 1; k < n; k++) stations.putIfAbsent(from + (to - from) * k / n, Kind.TOWER);
            }
            for (var st : stations.entrySet()) {
                Piece p = new Piece(l.index, 0, st.getValue(), e.index, st.getKey(), st.getKey(), st.getKey());
                (st.getValue() == Kind.GATE ? gates : towers).add(p);
            }
            // wall between consecutive stations (cells already taken by towers and gates are skipped when built)
            List<Integer> ts = new ArrayList<>(stations.keySet());
            if (ts.isEmpty() || ts.get(0) != 0) ts.add(0, 0);
            if (ts.get(ts.size() - 1) != e.len) ts.add(e.len);
            for (int i = 0; i + 1 < ts.size(); i++) walls.add(new Piece(l.index, 0, Kind.WALL, e.index, ts.get(i), ts.get(i + 1), (ts.get(i) + ts.get(i + 1)) / 2));
        }
        List<Piece> out = new ArrayList<>();
        for (List<Piece> group : List.of(gates, towers, walls))
            for (Piece p : group) out.add(new Piece(p.line, out.size(), p.kind, p.edge, p.t0, p.t1, p.tc));
        return out;
    }

    /** Columns of a tower (5 x 5, pushed one block outward so it flanks the wall) or gatehouse (two towers and the passage). */
    static List<long[]> footprint(Line l, Piece p, BlockPos c) {
        Edge e = edges(l).get(p.edge);
        List<long[]> out = new ArrayList<>();
        if (p.kind == Kind.TOWER) {
            int tx = c.getX() + e.x(p.tc) + e.nx(), tz = c.getZ() + e.z(p.tc) + e.nz();
            for (int i = -2; i <= 2; i++) for (int j = -2; j <= 2; j++) out.add(new long[]{tx + i, tz + j, i, j});
        } else if (p.kind == Kind.GATE) {
            for (int side = -1; side <= 1; side += 2) {
                int t = p.tc + side * 5;
                int tx = c.getX() + e.x(t) + e.ux, tz = c.getZ() + e.z(t) + e.uz;
                for (int i = -2; i <= 2; i++) for (int j = -2; j <= 2; j++) out.add(new long[]{tx + i, tz + j, i, j, side});
            }
            for (int t = p.tc - 2; t <= p.tc + 2; t++) for (int k = -1; k <= 1; k++)
                out.add(new long[]{c.getX() + e.x(t) + k * e.ux, c.getZ() + e.z(t) + k * e.uz, t, k, 0});
        }
        return out;
    }

    /** Columns every tower and gatehouse of the line takes (walls leave them alone). */
    static Set<Long> structures(Line l, BlockPos c) {
        Set<Long> out = new HashSet<>();
        for (Piece p : pieces(l)) if (p.kind != Kind.WALL) for (long[] f : footprint(l, p, c)) out.add(BlockPos.asLong((int) f[0], 0, (int) f[1]));
        return out;
    }

    // ------------------------------------------------------------------ which line, and is it the town's turn

    /** The lines this town has unlocked, oldest first. */
    public static List<Line> unlocked(Settlement s) {
        List<Line> out = new ArrayList<>();
        for (Line l : s.plan().wallLines()) if (s.tier.number() >= l.tier.number() && s.has("fortifications")) out.add(l);
        return out;
    }

    /** Footprint of a planned piece (any state), from the plot's own blueprint. */
    public static boolean isWallPiece(Plot p) { return p.blueprintKey != null && p.blueprintKey.startsWith("wall:"); }

    static Set<String> planned(Settlement s) {
        Set<String> out = new HashSet<>();
        for (Plot p : s.plots) if (isWallPiece(p)) out.add(p.blueprintKey);
        return out;
    }

    /** A piece to build and its blueprint, ready to queue. */
    public record Plan(Piece piece, Blueprint blueprint, BlockPos origin, BuildingType type, @Nullable Edge edge) {}

    /** Status for the City Manager: what the walls are doing, or why not. */
    public static String status(Settlement s) {
        if (!s.autoWalls) return "Automatic walls off";
        List<Line> lines = s.plan().wallLines();
        if (lines.isEmpty()) return "No wall line yet";
        Line first = lines.get(0);
        if (s.tier.number() < first.tier.number()) return "Walls come at " + first.tier.label + " rank";
        if (!s.has("fortifications")) return "Walls need Fortifications";
        Set<String> done = planned(s);
        int total = 0, built = 0;
        for (Line l : unlocked(s)) for (Piece p : pieces(l)) {
            total++;
            if (done.contains(p.key(s))) built++;
        }
        String blocked = s.plan().wallBlocked;
        return built + "/" + total + " wall sections planned" + (blocked.isEmpty() ? "" : " · " + blocked);
    }

    /**
     * The next piece the villagers should build, or null with the reason in {@code s.plan().wallBlocked}. Gatehouses come
     * first (their high street is paved out through them), then towers, then the wall; the old wall before the city wall.
     */
    @Nullable
    public static Plan next(Terrain terrain, Settlement s) {
        TownPlan plan = s.plan();
        plan.wallBlocked = "";
        Set<String> done = planned(s);
        for (Line l : unlocked(s)) {
            for (Piece p : pieces(l)) {
                if (done.contains(p.key(s))) continue;
                Plan made = build(terrain, s, l, p);
                if (made != null) return made;
            }
        }
        return null;
    }

    // ------------------------------------------------------------------ blueprints that follow the ground

    /** Builds one piece's blueprint against the ground, or null (with the reason noted) if something is in the way. */
    @Nullable
    public static Plan build(Terrain terrain, Settlement s, Line l, Piece p) {
        BlockPos c = s.center;
        Edge e = edges(l).get(p.edge);
        Map<Long, BlockState> blocks = new HashMap<>();
        BlockState stone = Procedural.masonry(s.style), parapet = Procedural.parapet(s.style);
        BlockState air = Blocks.AIR.defaultBlockState();
        String type;
        int ta = p.kind == Kind.WALL ? p.t0 : p.tc - 8, tb = p.kind == Kind.WALL ? p.t1 : p.tc + 8;
        int x0 = c.getX() + Math.min(e.x(Math.max(0, ta)), e.x(Math.min(e.len, tb))), x1 = c.getX() + Math.max(e.x(Math.max(0, ta)), e.x(Math.min(e.len, tb)));
        int z0 = c.getZ() + Math.min(e.z(Math.max(0, ta)), e.z(Math.min(e.len, tb))), z1 = c.getZ() + Math.max(e.z(Math.max(0, ta)), e.z(Math.min(e.len, tb)));
        if (!terrain.loaded(x0 - 16, z0 - 16, x1 + 16, z1 + 16)) { blocked(s, "waiting for the wall line to be visited"); return null; }
        Set<Long> others = obstacles(s, c, l);
        // our own walls already standing are not someone else's building
        Set<Long> ours = new HashSet<>();
        for (Plot o : s.plots) if (isWallPiece(o) && o.columns() != null) ours.addAll(o.columns());
        if (p.kind == Kind.WALL) {
            type = WALL;
            Set<Long> structures = structures(l, c);
            // the wall's columns: three thick, minus what towers and gates take, bowed outward round obstacles
            int n = p.t1 - p.t0 + 1;
            int[] need = new int[n];
            for (int i = 0; i < n; i++) {
                int t = p.t0 + i;
                for (int o = 0; o <= 12; o++) {
                    boolean clear = true;
                    for (int k = -2; k <= 2 && clear; k++) {
                        long col = BlockPos.asLong(c.getX() + e.x(t) + (o + k) * e.ux, 0, c.getZ() + e.z(t) + (o + k) * e.uz);
                        if (others.contains(col) || terrain.built(BlockPos.getX(col), BlockPos.getZ(col)) && !structures.contains(col) && !ours.contains(col)) clear = false;
                    }
                    if (clear) { need[i] = o; break; }
                    if (o == 12) { blocked(s, "a building blocks the wall at " + (c.getX() + e.x(t)) + ", " + (c.getZ() + e.z(t))); return null; }
                }
            }
            int[] off = new int[n];
            for (int i = 0; i < n; i++) for (int j = 0; j < n; j++) off[i] = Math.max(off[i], need[j] - Math.abs(i - j));
            if (off[0] > 0 || off[n - 1] > 0) { blocked(s, "a building stands where a tower or gate meets the wall near " + (c.getX() + e.x(p.tc)) + ", " + (c.getZ() + e.z(p.tc))); return null; }
            // walk height: five above the highest ground across the wall, at most one step per column
            int[] top = new int[n];
            boolean[] gap = new boolean[n];
            // columns shared with the neighbouring run (at a corner) belong to the earlier piece
            Set<Long> claimed = new HashSet<>(earlierWall(l, p, c));
            List<long[]>[] cols = new List[n];
            for (int i = 0; i < n; i++) {
                int t = p.t0 + i;
                cols[i] = new ArrayList<>();
                int hi = Integer.MIN_VALUE;
                for (int k = -1; k <= 1; k++) {
                    int x = c.getX() + e.x(t) + (off[i] + k) * e.ux, z = c.getZ() + e.z(t) + (off[i] + k) * e.uz;
                    long key = BlockPos.asLong(x, 0, z);
                    if (structures.contains(key) || !claimed.add(key)) continue;
                    int depth = terrain.water(x, z) ? terrain.waterDepth(x, z) : 0;
                    int g = terrain.height(x, z) - depth;
                    cols[i].add(new long[]{x, z, g, k});
                    hi = Math.max(hi, terrain.height(x, z));
                }
                int wx = c.getX() + e.x(t), wz = c.getZ() + e.z(t);
                boolean deep = terrain.water(wx, wz) && terrain.waterDepth(wx, wz) >= 3;
                int inside = terrain.height(wx - 3 * e.ux, wz - 3 * e.uz), outside = terrain.height(wx + 4 * e.ux, wz + 4 * e.uz);
                gap[i] = deep || inside - outside >= 6 || cols[i].isEmpty();
                top[i] = hi + H;
            }
            for (int i = 1; i < n; i++) if (!gap[i] && !gap[i - 1]) top[i] = Math.max(top[i], top[i - 1] - 1);
            for (int i = n - 2; i >= 0; i--) if (!gap[i] && !gap[i + 1]) top[i] = Math.max(top[i], top[i + 1] - 1);
            for (int i = 0; i < n; i++) {
                if (gap[i]) continue;
                for (long[] col : cols[i]) {
                    int x = (int) col[0], z = (int) col[1], g = (int) col[2];
                    boolean outer = col[3] >= 1;
                    for (int y = g; y <= top[i]; y++) blocks.put(BlockPos.asLong(x, y, z), stone);
                    if (outer && (p.t0 + i) % 2 == 0) blocks.put(BlockPos.asLong(x, top[i] + 1, z), stone);
                    else if (outer) blocks.put(BlockPos.asLong(x, top[i] + 1, z), parapet);
                    else for (int y = top[i] + 1; y <= top[i] + 3; y++) blocks.put(BlockPos.asLong(x, y, z), air);
                }
            }
            if (blocks.isEmpty()) {
                // nothing to build here (all water, cliff or tower): record it as done with a marker-free plan
                return new Plan(p, null, null, BuildingCatalog.get(WALL), e);
            }
        } else {
            List<long[]> foot = footprint(l, p, c);
            for (long[] f : foot) {
                long col = BlockPos.asLong((int) f[0], 0, (int) f[1]);
                if (others.contains(col) || terrain.built((int) f[0], (int) f[1]) && !ours.contains(col)) {
                    blocked(s, "a building stands where a " + (p.kind == Kind.GATE ? "gatehouse" : "tower") + " should go at " + f[0] + ", " + f[1]);
                    return null;
                }
                if (terrain.water((int) f[0], (int) f[1]) && terrain.waterDepth((int) f[0], (int) f[1]) >= 3 && p.kind == Kind.TOWER) {
                    blocked(s, "");
                    return new Plan(p, null, null, BuildingCatalog.get(TOWER), e);
                }
            }
            if (p.kind == Kind.TOWER) {
                type = TOWER;
                tower(terrain, blocks, foot, Integer.MIN_VALUE, e, stone, true);
            } else {
                type = GATE;
                int walk = Integer.MIN_VALUE;
                for (long[] f : foot) walk = Math.max(walk, terrain.height((int) f[0], (int) f[1]) + H);
                for (int side = -1; side <= 1; side += 2) {
                    List<long[]> half = new ArrayList<>();
                    for (long[] f : foot) if (f.length == 5 && f[4] == side) half.add(f);
                    tower(terrain, blocks, half, walk, e, stone, true);
                }
                String material = RoadClass.HIGH.material(s.tier, s.style);
                BlockState floor = Planning.ROAD_MATERIALS.getOrDefault(material, Blocks.DIRT_PATH.defaultBlockState());
                for (long[] f : foot) {
                    if (f[4] != 0) continue;
                    int x = (int) f[0], z = (int) f[1];
                    int depth = terrain.water(x, z) ? terrain.waterDepth(x, z) : 0;
                    int g = terrain.height(x, z);
                    // a plank deck where the street crosses water; else the street's paving
                    for (int y = g - depth + 1; y < g; y++) blocks.put(BlockPos.asLong(x, y, z), stone);
                    blocks.put(BlockPos.asLong(x, g, z), depth > 0 ? Blocks.SPRUCE_PLANKS.defaultBlockState() : floor);
                    for (int y = g + 1; y <= g + PASSAGE; y++) blocks.put(BlockPos.asLong(x, y, z), air);
                    for (int y = g + PASSAGE + 1; y <= Math.max(walk, g + PASSAGE + 1); y++) blocks.put(BlockPos.asLong(x, y, z), stone);
                    if (f[3] == 1) blocks.put(BlockPos.asLong(x, Math.max(walk, g + PASSAGE + 1) + 1, z), (f[2] % 2 == 0) ? stone : parapet);
                }
            }
        }
        return make(s, p, type, blocks, e);
    }

    /** Wall columns of the pieces before this one on the same line (base geometry), so neighbours never share a column. */
    static Set<Long> earlierWall(Line l, Piece p, BlockPos c) {
        Set<Long> out = new HashSet<>();
        List<Edge> es = edges(l);
        for (Piece o : pieces(l)) {
            if (o.kind != Kind.WALL || o.index >= p.index) continue;
            Edge e = es.get(o.edge);
            if (Math.abs(o.edge - p.edge) > 1 && Math.abs(o.edge - p.edge) != 7) continue;
            for (int t = o.t0; t <= o.t1; t++) for (int k = -1; k <= 1; k++)
                out.add(BlockPos.asLong(c.getX() + e.x(t) + k * e.ux, 0, c.getZ() + e.z(t) + k * e.uz));
        }
        return out;
    }

    /** A 5 x 5 tower: hollow, a floor at the wall walk, a ladder from the ground to the roof, a door to the town, crenels. */
    static void tower(Terrain terrain, Map<Long, BlockState> blocks, List<long[]> foot, int minWalk, Edge e, BlockState stone, boolean door) {
        int walk = minWalk;
        for (long[] f : foot) walk = Math.max(walk, terrain.height((int) f[0], (int) f[1]) + H);
        int roof = walk + ROOF;
        // which local side faces the town: opposite the outward step u (diagonal towers: their x side)
        int ix = -Integer.signum(e.ux), iz = -Integer.signum(e.uz);
        BlockState air = Blocks.AIR.defaultBlockState();
        for (long[] f : foot) {
            int x = (int) f[0], z = (int) f[1], i = (int) f[2], j = (int) f[3];
            int depth = terrain.water(x, z) ? terrain.waterDepth(x, z) : 0;
            int g = terrain.height(x, z) - depth;
            boolean edge = Math.abs(i) == 2 || Math.abs(j) == 2;
            // the ladder hangs on the inner face of the town-side wall
            boolean ladder = ix != 0 ? i == ix && j == 0 : j == iz && i == 0;
            for (int y = g; y <= roof; y++) {
                BlockState st;
                if (edge) st = stone;
                else if (ladder && y > g) st = ladderState(ix, iz);
                else if (y == g || y == walk || y == roof) st = stone;
                else st = air;
                blocks.put(BlockPos.asLong(x, y, z), st);
            }
            if (edge && (Math.abs(i) + Math.abs(j)) % 2 == 0) blocks.put(BlockPos.asLong(x, roof + 1, z), stone);
            // door to the town at the foot, openings to the wall walk on the sides along the wall
            boolean townSide = ix != 0 ? i == 2 * ix && j == 0 : j == 2 * iz && i == 0;
            if (door && townSide) { blocks.put(BlockPos.asLong(x, g + 1, z), air); blocks.put(BlockPos.asLong(x, g + 2, z), air); }
            boolean alongWall = ix != 0 ? Math.abs(j) == 2 && i == 0 : Math.abs(i) == 2 && j == 0;
            if (alongWall) { blocks.put(BlockPos.asLong(x, walk + 1, z), air); blocks.put(BlockPos.asLong(x, walk + 2, z), air); }
            boolean outward = ix != 0 ? i == -2 * ix && j == 0 : j == -2 * iz && i == 0;
            if (outward) blocks.put(BlockPos.asLong(x, walk + 2, z), air);
        }
    }

    static BlockState ladderState(int ix, int iz) {
        // attached to the town-side wall, so it faces away from it
        Direction facing = ix > 0 ? Direction.WEST : ix < 0 ? Direction.EAST : iz > 0 ? Direction.NORTH : Direction.SOUTH;
        return Blocks.LADDER.defaultBlockState().setValue(LadderBlock.FACING, facing);
    }

    static void blocked(Settlement s, String why) {
        if (s.plan().wallBlocked.isEmpty()) s.plan().wallBlocked = why;
    }

    /** Columns of buildings and roads that are not part of this line's wall (player buildings, other plots). */
    static Set<Long> obstacles(Settlement s, BlockPos c, Line l) {
        Set<Long> out = new HashSet<>();
        for (Plot o : s.plots) {
            if (o.state == Plot.State.DEMOLISHING || o.typeId.equals("road") || isWallPiece(o)) continue;
            BoundingBox b = o.box();
            if (!inBand(l, c, b, 3) && !inBand(l, c, b.inflatedBy(13), 3)) continue;
            for (int x = b.minX() - 1; x <= b.maxX() + 1; x++) for (int z = b.minZ() - 1; z <= b.maxZ() + 1; z++) out.add(BlockPos.asLong(x, 0, z));
        }
        return out;
    }

    static Plan make(Settlement s, Piece p, String type, Map<Long, BlockState> blocks, Edge e) {
        int minX = Integer.MAX_VALUE, minY = Integer.MAX_VALUE, minZ = Integer.MAX_VALUE, maxX = Integer.MIN_VALUE, maxY = Integer.MIN_VALUE, maxZ = Integer.MIN_VALUE;
        for (long k : blocks.keySet()) {
            int x = BlockPos.getX(k), y = BlockPos.getY(k), z = BlockPos.getZ(k);
            minX = Math.min(minX, x); minY = Math.min(minY, y); minZ = Math.min(minZ, z);
            maxX = Math.max(maxX, x); maxY = Math.max(maxY, y); maxZ = Math.max(maxZ, z);
        }
        List<Blueprint.Entry> entries = new ArrayList<>();
        for (var en : blocks.entrySet())
            entries.add(new Blueprint.Entry(BlockPos.getX(en.getKey()) - minX, BlockPos.getY(en.getKey()) - minY, BlockPos.getZ(en.getKey()) - minZ, en.getValue()));
        Blueprint bp = new Blueprint(p.key(s), maxX - minX + 1, maxY - minY + 1, maxZ - minZ + 1, entries);
        return new Plan(p, bp, new BlockPos(minX, minY, minZ), BuildingCatalog.get(type), e);
    }

    /** Queues a planned piece as an ordinary paid project (or marks an empty one done). Returns an error or null. */
    @Nullable
    public static String queue(Settlement s, Plan plan, boolean automatic) {
        Plot p = new Plot();
        p.typeId = plan.type().id;
        p.blueprintKey = plan.piece().key(s);
        p.automatic = automatic;
        p.ai = s.isAi();
        p.priority = automatic ? -2 : 0;
        if (plan.blueprint() == null) {
            // water or cliff: nothing to build, kept as a standing marker so the piece counts as done
            Blueprint none = new Blueprint(p.blueprintKey, 1, 1, 1, List.of(new Blueprint.Entry(0, 0, 0, Blocks.AIR.defaultBlockState())));
            BuildingCatalog.putBlueprint(none);
            p.customBlueprint = none.toTag();
            Edge e = plan.edge();
            p.origin = new BlockPos(s.center.getX() + e.x(plan.piece().tc), s.center.getY(), s.center.getZ() + e.z(plan.piece().tc));
            p.id = s.nextId++;
            p.state = Plot.State.BUILT;
            s.plots.add(p);
            s.dirty = true;
            return null;
        }
        BuildingCatalog.putBlueprint(plan.blueprint());
        p.customBlueprint = plan.blueprint().toTag();
        p.origin = plan.origin();
        String err = Planning.checkWall(s, p);
        if (err != null) return err;
        p.id = s.nextId++;
        p.state = Plot.State.BUILDING;
        s.plots.add(p);
        s.dirty = true;
        return null;
    }

    /** The paved street length a gatehouse needs so its street runs through it and on beyond. */
    @Nullable
    public static int[] gateStreet(Settlement s, Line l, Piece p) {
        if (p.kind != Kind.GATE) return null;
        Edge e = edges(l).get(p.edge);
        int gx = s.center.getX() + e.x(p.tc), gz = s.center.getZ() + e.z(p.tc);
        for (Street st : s.plan().streets) {
            if (st.cls != RoadClass.HIGH) continue;
            int along = st.along(gx, gz);
            if (along < 0 || along > st.length) continue;
            if (st.x(along) == gx && st.z(along) == gz) return new int[]{st.id, Math.min(st.length, along + 8)};
        }
        return null;
    }

    // ------------------------------------------------------------------ for maps, raids and tests

    /** Vertices of each line in world columns, for the City Map and Architect view: [x0, z0, x1, z1, ...] per line. */
    public static List<int[]> outlines(Settlement s) {
        List<int[]> out = new ArrayList<>();
        for (Line l : s.plan().wallLines()) {
            if (s.radius() < l.a) continue;
            List<Edge> es = edges(l);
            int[] v = new int[es.size() * 2];
            for (int i = 0; i < es.size(); i++) { v[2 * i] = s.center.getX() + es.get(i).x0; v[2 * i + 1] = s.center.getZ() + es.get(i).z0; }
            out.add(v);
        }
        return out;
    }

    /** Standing gatehouses of the town. */
    public static List<Plot> gates(Settlement s) {
        List<Plot> out = new ArrayList<>();
        for (Plot p : s.plots) if (p.typeId.equals(GATE) && isWallPiece(p) && p.standing()) out.add(p);
        return out;
    }

    /** The gate to hold against attackers gathered outside the walls at {@code front}, or null if they are not outside one. */
    @Nullable
    public static Plot gateFacing(Settlement s, double fx, double fz) {
        List<Plot> gates = gates(s);
        if (gates.isEmpty()) return null;
        int dx = (int) Math.round(fx - s.center.getX()), dz = (int) Math.round(fz - s.center.getZ());
        boolean outside = false;
        for (Line l : s.plan().wallLines()) if (level(l, dx, dz) > 2) outside = true;
        if (!outside) return null;
        Plot best = null;
        double bd = Double.MAX_VALUE;
        for (Plot g : gates) {
            double d = g.center().distToCenterSqr(fx, g.center().getY(), fz);
            if (d < bd) { bd = d; best = g; }
        }
        return best;
    }

    /** A street column on or next to a wall line, away from that line's gatehouses (a street there would breach the wall). */
    public static boolean breach(Settlement s, int x, int z) {
        int dx = x - s.center.getX(), dz = z - s.center.getZ();
        for (Line l : s.plan().wallLines()) {
            if (Math.abs(level(l, dx, dz)) > 3) continue;
            boolean gate = false;
            List<Edge> es = edges(l);
            for (int g : l.gates) {
                Edge e = es.get(g / 10_000);
                int t = g % 10_000;
                if (Math.max(Math.abs(e.x(t) - dx), Math.abs(e.z(t) - dz)) <= 4) gate = true;
            }
            if (!gate) return true;
        }
        return false;
    }

    /** North, south, east or west of the centre, for messages. */
    public static String side(Settlement s, BlockPos p) {
        int dx = p.getX() - s.center.getX(), dz = p.getZ() - s.center.getZ();
        return Math.abs(dx) >= Math.abs(dz) ? (dx >= 0 ? "east" : "west") : (dz >= 0 ? "south" : "north");
    }

    public static CompoundTag saveLine(Line l) {
        CompoundTag t = new CompoundTag();
        t.putIntArray("l", new int[]{l.index, l.a, l.b, l.tier.number()});
        t.putIntArray("g", l.gates.stream().mapToInt(Integer::intValue).toArray());
        return t;
    }

    @Nullable
    public static Line loadLine(CompoundTag t) {
        int[] v = t.getIntArray("l");
        if (v.length != 4) return null;
        List<Integer> g = new ArrayList<>();
        for (int x : t.getIntArray("g")) g.add(x);
        return new Line(v[0], v[1], v[2], Tier.of(v[3]), g);
    }

    private Walls() {}
}
