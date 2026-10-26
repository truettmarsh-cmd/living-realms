package livingrealms.research;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import livingrealms.LivingRealms;
import livingrealms.net.Net;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.server.packs.resources.SimpleJsonResourceReloadListener;
import net.minecraft.util.profiling.ProfilerFiller;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraftforge.server.ServerLifecycleHooks;

import java.util.*;

/** Research projects, loaded from {@code data/<ns>/livingrealms_research/*.json}. Ids are the file names. */
public final class ResearchTree extends SimpleJsonResourceReloadListener {
    public static final ResearchTree INSTANCE = new ResearchTree();

    public record Node(String id, String name, String branch, String description, int cost, List<String> requires,
                       Map<String, Integer> items, Item icon, int x, int y, List<String> unlocks) {}

    private static volatile Map<String, Node> nodes = Map.of();

    private ResearchTree() { super(new Gson(), "livingrealms_research"); }

    public static Map<String, Node> nodes() { return nodes; }

    public static Node get(String id) { return nodes.get(id); }

    @Override
    protected void apply(Map<ResourceLocation, JsonElement> map, ResourceManager rm, ProfilerFiller profiler) {
        Map<String, Node> out = new LinkedHashMap<>();
        List<ResourceLocation> keys = new ArrayList<>(map.keySet());
        keys.sort(Comparator.comparing(ResourceLocation::getPath));
        for (ResourceLocation rl : keys) {
            try {
                JsonObject o = map.get(rl).getAsJsonObject();
                List<String> req = new ArrayList<>();
                if (o.has("requires")) o.getAsJsonArray("requires").forEach(e -> req.add(e.getAsString()));
                Map<String, Integer> items = new LinkedHashMap<>();
                if (o.has("items")) o.getAsJsonObject("items").entrySet().forEach(e -> items.put(e.getKey(), e.getValue().getAsInt()));
                List<String> unlocks = new ArrayList<>();
                if (o.has("unlocks")) o.getAsJsonArray("unlocks").forEach(e -> unlocks.add(e.getAsString()));
                Item icon = o.has("icon") ? BuiltInRegistries.ITEM.get(new ResourceLocation(o.get("icon").getAsString())) : Items.BOOK;
                out.put(rl.getPath(), new Node(rl.getPath(), o.get("name").getAsString(), o.get("branch").getAsString(),
                        o.has("description") ? o.get("description").getAsString() : "", o.get("cost").getAsInt(), req, items,
                        icon, o.get("x").getAsInt(), o.get("y").getAsInt(), unlocks));
            } catch (Exception e) {
                LivingRealms.LOG.warn("Bad research {}: {}", rl, e.toString());
            }
        }
        nodes = out;
        var server = ServerLifecycleHooks.getCurrentServer();
        if (server != null) Net.sendCatalogToAll(server);
    }

    public static CompoundTag toTag() {
        CompoundTag t = new CompoundTag();
        ListTag l = new ListTag();
        for (Node n : nodes.values()) {
            CompoundTag c = new CompoundTag();
            c.putString("id", n.id);
            c.putString("name", n.name);
            c.putString("branch", n.branch);
            c.putString("desc", n.description);
            c.putInt("cost", n.cost);
            ListTag r = new ListTag();
            n.requires.forEach(s -> r.add(StringTag.valueOf(s)));
            c.put("req", r);
            CompoundTag it = new CompoundTag();
            n.items.forEach(it::putInt);
            c.put("items", it);
            c.putString("icon", BuiltInRegistries.ITEM.getKey(n.icon).toString());
            c.putInt("x", n.x);
            c.putInt("y", n.y);
            ListTag u = new ListTag();
            n.unlocks.forEach(s -> u.add(StringTag.valueOf(s)));
            c.put("unlocks", u);
            l.add(c);
        }
        t.put("nodes", l);
        return t;
    }

    public static void fromTag(CompoundTag t) {
        Map<String, Node> out = new LinkedHashMap<>();
        for (Tag e : t.getList("nodes", Tag.TAG_COMPOUND)) {
            CompoundTag c = (CompoundTag) e;
            List<String> req = new ArrayList<>();
            c.getList("req", Tag.TAG_STRING).forEach(s -> req.add(s.getAsString()));
            Map<String, Integer> items = new LinkedHashMap<>();
            CompoundTag it = c.getCompound("items");
            for (String k : it.getAllKeys()) items.put(k, it.getInt(k));
            List<String> unlocks = new ArrayList<>();
            c.getList("unlocks", Tag.TAG_STRING).forEach(s -> unlocks.add(s.getAsString()));
            out.put(c.getString("id"), new Node(c.getString("id"), c.getString("name"), c.getString("branch"), c.getString("desc"),
                    c.getInt("cost"), req, items, BuiltInRegistries.ITEM.get(new ResourceLocation(c.getString("icon"))),
                    c.getInt("x"), c.getInt("y"), unlocks));
        }
        nodes = out;
    }
}
