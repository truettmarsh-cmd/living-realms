package livingrealms.smithing;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import livingrealms.LivingRealms;
import livingrealms.net.Net;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.server.packs.resources.SimpleJsonResourceReloadListener;
import net.minecraft.util.profiling.ProfilerFiller;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraftforge.fml.ModList;
import net.minecraftforge.server.ServerLifecycleHooks;

import java.util.*;

/**
 * Smithing patterns: a material or armour style and the pieces a smith can make from it, with their costs and the
 * research that unlocks them. Loaded from {@code data/<ns>/livingrealms_smithing/*.json}; patterns that need a mod
 * which isn't installed are skipped.
 */
public final class Smithing extends SimpleJsonResourceReloadListener {
    public static final Smithing INSTANCE = new Smithing();

    public record Recipe(Item item, int count, Map<String, Integer> cost, String research) {}

    public record Pattern(String id, String name, String research, int order, int color, Map<Piece, Recipe> pieces) {}

    private static volatile Map<String, Pattern> patterns = Map.of();
    private static Map<String, Pattern> configured = Map.of();

    public static void discover(net.minecraft.server.MinecraftServer server) {
        Map<String, Pattern> combined = new LinkedHashMap<>(configured);
        EquipmentDiscovery.add(server, combined);
        patterns = combined;
    }

    private Smithing() { super(new Gson(), "livingrealms_smithing"); }

    public static Map<String, Pattern> patterns() { return patterns; }

    public static Pattern get(String id) { return patterns.get(id); }

    @Override
    protected void apply(Map<ResourceLocation, JsonElement> map, ResourceManager rm, ProfilerFiller profiler) {
        List<Pattern> list = new ArrayList<>();
        map.forEach((rl, json) -> {
            try {
                JsonObject o = json.getAsJsonObject();
                if (o.has("requires_mod") && !ModList.get().isLoaded(o.get("requires_mod").getAsString())) return;
                String research = o.has("research") ? o.get("research").getAsString() : null;
                Map<Piece, Recipe> pieces = new EnumMap<>(Piece.class);
                for (var e : o.getAsJsonObject("pieces").entrySet()) {
                    JsonObject p = e.getValue().getAsJsonObject();
                    Item item = BuiltInRegistries.ITEM.get(new ResourceLocation(p.get("item").getAsString()));
                    if (item == Items.AIR) continue;
                    Map<String, Integer> cost = new LinkedHashMap<>();
                    p.getAsJsonObject("cost").entrySet().forEach(c -> cost.put(c.getKey(), c.getValue().getAsInt()));
                    pieces.put(Piece.valueOf(e.getKey().toUpperCase(Locale.ROOT)), new Recipe(item,
                            p.has("count") ? p.get("count").getAsInt() : 1, cost, p.has("research") ? p.get("research").getAsString() : research));
                }
                if (pieces.isEmpty()) return;
                list.add(new Pattern(rl.getPath(), o.get("name").getAsString(), research, o.has("order") ? o.get("order").getAsInt() : 50,
                        o.has("color") ? Integer.parseInt(o.get("color").getAsString().replace("#", ""), 16) : 0xAAAAAA, pieces));
            } catch (Exception e) {
                LivingRealms.LOG.warn("Bad smithing pattern {}: {}", rl, e.toString());
            }
        });
        list.sort(Comparator.comparingInt(Pattern::order).thenComparing(Pattern::name));
        Map<String, Pattern> out = new LinkedHashMap<>();
        list.forEach(p -> out.put(p.id, p));
        patterns = out;
        configured = new LinkedHashMap<>(out);
        var server = ServerLifecycleHooks.getCurrentServer();
        if (server != null) Net.sendCatalogToAll(server);
    }

    public static CompoundTag toTag() {
        CompoundTag t = new CompoundTag();
        ListTag l = new ListTag();
        for (Pattern p : patterns.values()) {
            CompoundTag c = new CompoundTag();
            c.putString("id", p.id);
            c.putString("name", p.name);
            c.putString("research", p.research == null ? "" : p.research);
            c.putInt("order", p.order);
            c.putInt("color", p.color);
            CompoundTag pcs = new CompoundTag();
            p.pieces.forEach((piece, r) -> {
                CompoundTag rc = new CompoundTag();
                rc.putString("item", BuiltInRegistries.ITEM.getKey(r.item).toString());
                rc.putInt("count", r.count);
                rc.putString("research", r.research == null ? "" : r.research);
                CompoundTag cost = new CompoundTag();
                r.cost.forEach(cost::putInt);
                rc.put("cost", cost);
                pcs.put(piece.name(), rc);
            });
            c.put("pieces", pcs);
            l.add(c);
        }
        t.put("patterns", l);
        return t;
    }

    public static void fromTag(CompoundTag t) {
        Map<String, Pattern> out = new LinkedHashMap<>();
        for (Tag e : t.getList("patterns", Tag.TAG_COMPOUND)) {
            CompoundTag c = (CompoundTag) e;
            Map<Piece, Recipe> pieces = new EnumMap<>(Piece.class);
            CompoundTag pcs = c.getCompound("pieces");
            for (String k : pcs.getAllKeys()) {
                CompoundTag rc = pcs.getCompound(k);
                Map<String, Integer> cost = new LinkedHashMap<>();
                CompoundTag ct = rc.getCompound("cost");
                for (String ck : ct.getAllKeys()) cost.put(ck, ct.getInt(ck));
                String r = rc.getString("research");
                pieces.put(Piece.valueOf(k), new Recipe(BuiltInRegistries.ITEM.get(new ResourceLocation(rc.getString("item"))),
                        rc.getInt("count"), cost, r.isEmpty() ? null : r));
            }
            String r = c.getString("research");
            out.put(c.getString("id"), new Pattern(c.getString("id"), c.getString("name"), r.isEmpty() ? null : r,
                    c.getInt("order"), c.getInt("color"), pieces));
        }
        patterns = out;
    }
}
