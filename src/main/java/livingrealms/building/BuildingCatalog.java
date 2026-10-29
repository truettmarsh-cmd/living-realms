package livingrealms.building;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import livingrealms.Config;
import livingrealms.LivingRealms;
import livingrealms.net.Net;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.server.packs.resources.SimplePreparableReloadListener;
import net.minecraft.util.datafix.DataFixTypes;
import net.minecraft.util.datafix.DataFixers;
import net.minecraft.util.profiling.ProfilerFiller;
import net.minecraftforge.server.ServerLifecycleHooks;

import java.io.InputStream;
import java.io.Reader;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Every building a settlement can raise: the procedural ones plus all suitable village buildings found among the
 * structure templates of the loaded mods and data packs (vanilla, CTOV, Towns and Towers...). Data packs can rename,
 * re-categorise or hide any of them with {@code data/<ns>/livingrealms_buildings/*.json}.
 */
public final class BuildingCatalog {
    private static volatile Map<String, BuildingType> types = Map.of();
    private static final Map<String, Blueprint> BLUEPRINTS = new ConcurrentHashMap<>();

    public static final SimplePreparableReloadListener<Map<String, BuildingType>> RELOADER = new SimplePreparableReloadListener<>() {
        @Override
        protected Map<String, BuildingType> prepare(ResourceManager rm, ProfilerFiller profiler) {
            return scan(rm);
        }

        @Override
        protected void apply(Map<String, BuildingType> result, ResourceManager rm, ProfilerFiller profiler) {
            types = result;
            LivingRealms.LOG.info("Living Realms: {} building types", result.size());
            var server = ServerLifecycleHooks.getCurrentServer();
            if (server != null) Net.sendCatalogToAll(server);
        }
    };

    public static Map<String, BuildingType> types() { return types; }

    public static BuildingType get(String id) { return types.get(id); }

    public static void setClientTypes(Map<String, BuildingType> t) { types = t; }

    /** Server-side blueprint lookup. Template blueprints are read when the catalog loads; procedural ones are generated on demand. */
    public static Blueprint blueprint(String key) {
        Blueprint b = BLUEPRINTS.get(key);
        if (b == null && key.startsWith("proc:")) {
            b = Procedural.make(key);
            BLUEPRINTS.put(key, b);
        }
        return b;
    }

    /** Whether a building suits a town of this style: procedural ones take the town's palette, templates must share its
     *  family, and styles a data pack excludes (festive, fantasy, far-off lands) are only ever built by hand. */
    public static boolean fits(BuildingType t, String style) {
        if (t.procedural()) return true;
        return !excludedStyle(t.style) && Procedural.styleFamily(t.style).equals(Procedural.styleFamily(style));
    }

    // ------------------------------------------------------------------ the style map (livingrealms_styles/*.json)

    private static volatile Map<String, String> styleFamilies = Map.of();
    private static volatile Set<String> excludedStyles = Set.of();

    /** The family a data pack gives a style or biome id ({@code ns:*} for a whole namespace), or null. */
    @javax.annotation.Nullable
    static String mappedFamily(String style) {
        Map<String, String> m = styleFamilies;
        if (m.isEmpty()) return null;
        String f = m.get(style);
        if (f == null && style.indexOf(':') > 0) f = m.get(style.substring(0, style.indexOf(':')) + ":*");
        return f;
    }

    public static boolean excludedStyle(String style) {
        Set<String> x = excludedStyles;
        return x.contains(style) || style.indexOf(':') > 0 && x.contains(style.substring(0, style.indexOf(':')) + ":*");
    }

    static void readStyles(ResourceManager rm) {
        Map<String, String> families = new HashMap<>();
        Set<String> exclude = new HashSet<>();
        var found = new TreeMap<>(rm.listResources("livingrealms_styles", rl -> rl.getPath().endsWith(".json")));
        found.forEach((rl, res) -> {
            try (Reader r = res.openAsReader()) {
                JsonObject o = JsonParser.parseReader(r).getAsJsonObject();
                if (o.has("families")) for (var e : o.getAsJsonObject("families").entrySet()) {
                    if (!Procedural.FAMILIES.contains(e.getKey())) throw new IllegalArgumentException("unknown family " + e.getKey());
                    e.getValue().getAsJsonArray().forEach(s -> { families.put(s.getAsString().toLowerCase(Locale.ROOT), e.getKey()); exclude.remove(s.getAsString().toLowerCase(Locale.ROOT)); });
                }
                if (o.has("exclude")) o.getAsJsonArray("exclude").forEach(s -> exclude.add(s.getAsString().toLowerCase(Locale.ROOT)));
            } catch (Exception e) {
                LivingRealms.LOG.warn("Bad style map {}: {}", rl, e.toString());
            }
        });
        styleFamilies = Map.copyOf(families);
        excludedStyles = Set.copyOf(exclude);
    }

    /** The neighbouring family whose templates come closest when a town's own has none of a kind. */
    public static String kin(String family) {
        return switch (family) {
            case "snowy" -> "taiga";
            case "taiga" -> "snowy";
            case "desert" -> "savanna";
            case "savanna" -> "desert";
            default -> "taiga";
        };
    }

    /** Roads and other one-off blueprints that live in a plot. */
    public static void putBlueprint(Blueprint b) { BLUEPRINTS.put(b.key, b); }

    // ------------------------------------------------------------------ scanning

    record Tweak(String name, Category category, Integer tier, String research, boolean hide) {}

    static Map<String, BuildingType> scan(ResourceManager rm) {
        Map<String, BuildingType> out = new LinkedHashMap<>();
        addProcedural(out);
        Map<String, Tweak> overrides = readOverrides(rm);
        readStyles(rm);
        int maxFoot;
        try {
            maxFoot = Config.MAX_TEMPLATE_FOOTPRINT.get();
        } catch (IllegalStateException notLoadedYet) {
            maxFoot = Config.MAX_TEMPLATE_FOOTPRINT.getDefault();
        }
        Map<String, Integer> nameCounts = new HashMap<>();
        var found = rm.listResources("structures", rl -> rl.getPath().endsWith(".nbt"));
        List<ResourceLocation> keys = new ArrayList<>(found.keySet());
        keys.sort(Comparator.comparing(ResourceLocation::toString));
        for (ResourceLocation file : keys) {
            String path = file.getPath().substring("structures/".length(), file.getPath().length() - 4);
            String[] seg = path.split("/");
            // village/<style>/..., villages/<style>/... (Repurposed Structures) or village_<style>/... (Dungeons and Taverns)
            int vi = -1;
            for (int i = 0; i < seg.length - 1 && vi < 0; i++) if (seg[i].equals("village") || seg[i].equals("villages") || seg[i].startsWith("village_")) vi = i;
            boolean suffixed = vi >= 0 && seg[vi].startsWith("village_");
            if (vi < 0 || !suffixed && vi + 2 >= seg.length) continue;
            // zombie villages and the like: whole folders of ruined variants
            if (Arrays.stream(seg).anyMatch(g -> g.equals("zombie") || g.equals("abandoned"))) continue;
            String templateId = file.getNamespace() + ":" + path;
            Tweak ov = overrides.get(templateId);
            if (ov != null && ov.hide) continue;
            String fileName = seg[seg.length - 1];
            String folder = seg[seg.length - 2];
            String styleSeg = suffixed ? seg[vi].substring("village_".length())
                    : seg[vi + 1].equals("exclusives") && vi + 2 < seg.length - 1 ? seg[vi + 2] : seg[vi + 1];
            if (excluded(folder) || excluded(fileName)) continue;
            Category cat = ov != null && ov.category != null ? ov.category : classify(folder + "/" + fileName);
            if (cat == null && !(folder.contains("house") || folder.contains("jobsite") || folder.contains("building"))) continue;
            Blueprint bp;
            try (InputStream in = found.get(file).open()) {
                CompoundTag tag = NbtIo.readCompressed(in);
                tag = DataFixTypes.STRUCTURE.updateToCurrentVersion(DataFixers.getDataFixer(), tag, tag.getInt("DataVersion"));
                bp = Blueprint.fromTemplate("tpl:" + templateId, tag);
            } catch (Exception e) {
                continue;
            }
            if (bp.sx > maxFoot || bp.sz > maxFoot || bp.sy > 40 || bp.sx < 4 || bp.sz < 4 || bp.solidCount < 25) continue;
            if (cat == null) cat = bp.beds > 0 ? Category.HOUSING : bp.workstations > 0 ? Category.WORKSHOP : null;
            if (cat == null) continue;
            if (cat == Category.HOUSING && bp.beds == 0) cat = bp.workstations > 0 ? Category.WORKSHOP : Category.DECOR;
            String style = file.getNamespace() + ":" + styleSeg;
            String baseName = prettyName(fileName, styleSeg, folder);
            int n = nameCounts.merge(style + "/" + baseName, 1, Integer::sum);
            String name = ov != null && ov.name != null ? ov.name : n > 1 ? baseName + " " + roman(n) : baseName;
            int area = bp.sx * bp.sz;
            int tier = ov != null && ov.tier != null ? ov.tier : defaultTier(cat, area);
            String research = ov != null && ov.research != null ? (ov.research.isEmpty() ? null : ov.research) : researchFor(cat);
            String id = "tpl/" + templateId.replace(':', '/');
            BuildingType t = new BuildingType(id, name, cat, style, pretty(styleSeg), List.of(bp.key), 3, tier, research, -1, 0,
                    sourceName(file.getNamespace()), cat == Category.WORKSHOP ? trade(fileName + " " + folder) : null);
            fillDims(t, bp);
            BLUEPRINTS.put(bp.key, bp);
            out.put(id, t);
        }
        return out;
    }

    static void addProcedural(Map<String, BuildingType> out) {
        proc(out, "capital_castle", "Capital Castle", Category.BARRACKS, 6, "royal_charter", 1);
        proc(out, "town_hall", "Town Hall", Category.CIVIC, 1, null, 1);
        proc(out, "cottage", "Cottage", Category.HOUSING, 1, null, -1);
        proc(out, "builders_yard", "Builder's Yard", Category.CONSTRUCTION, 1, null, -1);
        proc(out, "storehouse", "Storehouse", Category.STORAGE, 1, null, -1);
        proc(out, "farm_field", "Farm Field", Category.FARM, 1, null, -1);
        proc(out, "lumber_camp", "Lumber Camp", Category.LUMBER, 1, null, -1);
        proc(out, "well", "Well", Category.DECOR, 1, null, -1);
        proc(out, "barracks", "Barracks", Category.BARRACKS, 2, null, -1);
        proc(out, "tavern", "Tavern", Category.TAVERN, 2, null, -1);
        proc(out, "garden", "Garden", Category.DECOR, 2, null, -1);
        proc(out, "smithy", "Smithy", Category.SMITHY, 2, "smithing", -1);
        proc(out, "library", "Library", Category.LIBRARY, 2, "scholarship", -1);
        proc(out, "mine", "Mine", Category.MINE, 2, "mining", -1);
        proc(out, "watchtower", "Watchtower", Category.TOWER, 2, "masonry", -1);
        proc(out, "wall", "Wall Section", Category.WALL, 3, "fortifications", -1);
        proc(out, "gatehouse", "Gatehouse", Category.WALL, 3, "fortifications", -1);
        proc(out, "wall_tower", "Wall Tower", Category.WALL, 3, "fortifications", -1);
        proc(out, "chapel", "Chapel", Category.CHAPEL, 3, "architecture", -1);
        proc(out, "market", "Market Square", Category.MARKET, 3, "trade", -1);
        proc(out, "stables", "Stables", Category.STABLE, 3, "cavalry", -1);
    }

    static void proc(Map<String, BuildingType> out, String kind, String name, Category cat, int tier, String research, int max) {
        int levels = Procedural.KINDS.get(kind);
        List<String> keys = new ArrayList<>();
        for (int l = 1; l <= levels; l++) keys.add("proc:" + kind + "/" + l);
        String id = "livingrealms:" + kind;
        BuildingType t = new BuildingType(id, name, cat, "any", "Settlement style", keys, levels, tier, research, max,
                Procedural.sink(kind), "Living Realms", null);
        fillDims(t, Procedural.make(keys.get(0) + "/plains"));
        out.put(id, t);
    }

    static void fillDims(BuildingType t, Blueprint bp) {
        t.w = bp.sx;
        t.h = bp.sy;
        t.d = bp.sz;
        t.beds = bp.beds;
        t.blocks = bp.solidCount;
    }

    static Map<String, Tweak> readOverrides(ResourceManager rm) {
        Map<String, Tweak> map = new HashMap<>();
        rm.listResources("livingrealms_buildings", rl -> rl.getPath().endsWith(".json")).forEach((rl, res) -> {
            try (Reader r = res.openAsReader()) {
                JsonObject o = JsonParser.parseReader(r).getAsJsonObject();
                String tpl = o.get("template").getAsString();
                map.put(tpl, new Tweak(o.has("name") ? o.get("name").getAsString() : null,
                        o.has("category") ? Category.valueOf(o.get("category").getAsString().toUpperCase(Locale.ROOT)) : null,
                        o.has("tier") ? o.get("tier").getAsInt() : null,
                        o.has("research") ? o.get("research").getAsString() : null,
                        o.has("hide") && o.get("hide").getAsBoolean()));
            } catch (Exception e) {
                LivingRealms.LOG.warn("Bad building override {}: {}", rl, e.toString());
            }
        });
        return map;
    }

    private static final String[] EXCLUDE = {"street", "road", "deco", "lamp", "town_center", "center", "terrain", "zombie",
            "abandoned", "ruin", "villager", "pillager", "outpost", "tree", "animal", "plaza", "bridge", "path", "pieces",
            "connector", "end", "turn", "corner", "decay", "camp", "fence", "pond", "spawn", "mob", "cat", "iron_golem", "pool",
            "meeting_point", "accessory", "waystone", "bell"};

    static boolean excluded(String s) {
        for (String e : EXCLUDE) if (s.contains(e)) return true;
        return false;
    }

    static Category classify(String s) {
        if (has(s, "smith", "armorer", "forge")) return Category.SMITHY;
        if (has(s, "library", "librarian", "cartographer", "scribe", "school")) return Category.LIBRARY;
        if (has(s, "farm", "field", "crop", "garden", "orchard")) return Category.FARM;
        if (has(s, "temple", "church", "cleric", "chapel", "shrine", "monastery")) return Category.CHAPEL;
        if (has(s, "tavern", "inn", "pub")) return Category.TAVERN;
        if (has(s, "stable", "pen", "barn")) return Category.STABLE;
        if (has(s, "market", "shop", "merchant", "trader", "bazaar", "stall")) return Category.MARKET;
        if (has(s, "storage", "warehouse", "granary", "storehouse")) return Category.STORAGE;
        if (has(s, "barracks", "guard", "watch", "tower")) return Category.TOWER;
        if (has(s, "lumber", "sawmill", "woodcutter")) return Category.LUMBER;
        if (has(s, "mine", "quarry")) return Category.MINE;
        if (has(s, "butcher", "fisher", "shepherd", "fletcher", "mason", "tanner", "leather", "weaver", "brew", "bake", "mill", "potter", "job")) return Category.WORKSHOP;
        if (has(s, "fountain", "well", "statue")) return Category.DECOR;
        if (has(s, "house", "home", "hut", "cottage", "residence", "dwelling", "small", "medium", "big", "large")) return Category.HOUSING;
        return null;
    }

    static String trade(String s) {
        for (String k : new String[]{"butcher", "fisher", "shepherd", "fletcher", "mason", "tanner", "leather", "weaver", "brew", "bake", "mill", "potter"})
            if (s.contains(k)) return k;
        return "general";
    }

    static boolean has(String s, String... keys) {
        for (String k : keys) if (s.contains(k)) return true;
        return false;
    }

    static int defaultTier(Category c, int area) {
        return switch (c) {
            case HOUSING, FARM, DECOR -> area > 300 ? 3 : area > 150 ? 2 : 1;
            case CHAPEL, MARKET, STABLE -> 3;
            default -> 2;
        };
    }

    static String researchFor(Category c) {
        return switch (c) {
            case SMITHY -> "smithing";
            case LIBRARY -> "scholarship";
            case MARKET -> "trade";
            case MINE -> "mining";
            case STABLE -> "cavalry";
            case CHAPEL -> "architecture";
            case TOWER -> "masonry";
            default -> null;
        };
    }

    static String sourceName(String ns) {
        return switch (ns) {
            case "minecraft" -> "Vanilla";
            case "ctov" -> "ChoiceTheorem's Overhauled Village";
            case "kaisyn" -> "Towns and Towers";
            case "repurposed_structures" -> "Repurposed Structures";
            default -> pretty(ns);
        };
    }

    static String prettyName(String file, String style, String folder) {
        Set<String> drop = new HashSet<>(Arrays.asList(style.split("_")));
        drop.addAll(List.of("village", "1", "2", "3", "4", "5", "6", "7", "8", "9"));
        StringBuilder sb = new StringBuilder();
        for (String t : file.split("_")) {
            if (drop.contains(t) || t.matches("\\d+") || t.isEmpty()) continue;
            if (sb.length() > 0) sb.append(' ');
            sb.append(Character.toUpperCase(t.charAt(0))).append(t.substring(1));
        }
        if (sb.length() == 0) return pretty(folder);
        return sb.toString();
    }

    public static String pretty(String s) {
        StringBuilder sb = new StringBuilder();
        for (String t : s.split("[_/]")) {
            if (t.isEmpty()) continue;
            if (sb.length() > 0) sb.append(' ');
            sb.append(Character.toUpperCase(t.charAt(0))).append(t.substring(1));
        }
        return sb.toString();
    }

    static String roman(int n) {
        String[] r = {"", "I", "II", "III", "IV", "V", "VI", "VII", "VIII", "IX", "X"};
        return n < r.length ? r[n] : String.valueOf(n);
    }

    // ------------------------------------------------------------------ network form

    public static CompoundTag toTag() {
        CompoundTag t = new CompoundTag();
        ListTag l = new ListTag();
        for (BuildingType b : types.values()) l.add(b.toTag());
        t.put("types", l);
        return t;
    }

    public static Map<String, BuildingType> fromTag(CompoundTag t) {
        Map<String, BuildingType> m = new LinkedHashMap<>();
        for (Tag e : t.getList("types", Tag.TAG_COMPOUND)) {
            BuildingType b = BuildingType.fromTag((CompoundTag) e);
            m.put(b.id, b);
        }
        return m;
    }

    private BuildingCatalog() {}
}
