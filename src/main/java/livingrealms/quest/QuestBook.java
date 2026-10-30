package livingrealms.quest;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import livingrealms.LivingRealms;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.server.packs.resources.SimpleJsonResourceReloadListener;
import net.minecraft.util.profiling.ProfilerFiller;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;

import java.util.*;

/**
 * The main quest line, loaded from {@code data/<ns>/livingrealms_quests/*.json}. Files are read in path order and their
 * chapters appended, so a data pack can replace {@code livingrealms:crown} or add a file of later chapters.
 */
public final class QuestBook extends SimpleJsonResourceReloadListener {
    public static final QuestBook INSTANCE = new QuestBook();

    /** What a step checks; see {@link Quests#progress}. */
    public record Check(String type, String id, int count) {}

    public record Reward(int emeralds, Map<Item, Integer> items, Map<Item, Integer> stock, int research, int merit) {
        static final Reward NONE = new Reward(0, Map.of(), Map.of(), 0, 0);

        public boolean empty() { return emeralds == 0 && items.isEmpty() && stock.isEmpty() && research == 0 && merit == 0; }

        /** "8 emeralds · 40 research · 16 Bread" */
        public String text() {
            List<String> out = new ArrayList<>();
            if (emeralds > 0) out.add(emeralds + " emerald" + (emeralds == 1 ? "" : "s"));
            items.forEach((it, n) -> out.add(n + " " + it.getDescription().getString()));
            stock.forEach((it, n) -> out.add(n + " " + it.getDescription().getString() + " (stockpile)"));
            if (research > 0) out.add(research + " research");
            if (merit > 0) out.add(merit + " merit to your soldiers");
            return String.join(" · ", out);
        }
    }

    public record Step(String id, String title, String flavour, String objective, Check check, Reward reward) {}

    public record Chapter(String id, String title, String intro, Item icon, List<Step> steps) {}

    private static volatile List<Chapter> chapters = List.of();

    private QuestBook() { super(new Gson(), "livingrealms_quests"); }

    public static List<Chapter> chapters() { return chapters; }

    /** For the self-test and tools: replace the loaded line. */
    public static void set(List<Chapter> line) { chapters = List.copyOf(line); }

    public static int steps() { return chapters.stream().mapToInt(c -> c.steps.size()).sum(); }

    @Override
    protected void apply(Map<ResourceLocation, JsonElement> map, ResourceManager rm, ProfilerFiller profiler) {
        List<ResourceLocation> keys = new ArrayList<>(map.keySet());
        keys.sort(Comparator.comparing(ResourceLocation::getPath).thenComparing(ResourceLocation::getNamespace));
        List<Chapter> out = new ArrayList<>();
        Set<String> ids = new HashSet<>();
        for (ResourceLocation rl : keys) {
            try {
                JsonObject root = map.get(rl).getAsJsonObject();
                for (JsonElement ce : root.getAsJsonArray("chapters")) {
                    Chapter c = chapter(ce.getAsJsonObject());
                    if (c.steps.isEmpty()) continue;
                    boolean clash = false;
                    for (Step s : c.steps) clash |= !ids.add(s.id);
                    if (clash) LivingRealms.LOG.warn("Quest chapter {} in {} repeats a step id; later copies never complete", c.id, rl);
                    out.add(c);
                }
            } catch (Exception e) {
                LivingRealms.LOG.warn("Bad quest file {}: {}", rl, e.toString());
            }
        }
        chapters = List.copyOf(out);
        LivingRealms.LOG.info("Living Realms: {} quest chapters, {} steps", out.size(), steps());
    }

    static Chapter chapter(JsonObject o) {
        String cid = o.get("id").getAsString();
        List<Step> steps = new ArrayList<>();
        for (JsonElement se : o.getAsJsonArray("steps")) {
            JsonObject s = se.getAsJsonObject();
            JsonObject ck = s.getAsJsonObject("check");
            String type = ck.get("type").getAsString();
            if (!Quests.CHECKS.contains(type)) LivingRealms.LOG.warn("Quest step {}/{}: unknown check '{}'", cid, s.get("id").getAsString(), type);
            Check check = new Check(type, ck.has("id") ? ck.get("id").getAsString() : "", ck.has("count") ? Math.max(1, ck.get("count").getAsInt()) : 1);
            steps.add(new Step(cid + "/" + s.get("id").getAsString(), s.get("title").getAsString(), str(s, "flavour"), str(s, "objective"),
                    check, s.has("reward") ? reward(s.getAsJsonObject("reward")) : Reward.NONE));
        }
        return new Chapter(cid, o.get("title").getAsString(), str(o, "intro"), item(str(o, "icon"), Items.WRITABLE_BOOK), steps);
    }

    static Reward reward(JsonObject o) {
        return new Reward(o.has("emeralds") ? Math.max(0, o.get("emeralds").getAsInt()) : 0, items(o, "items"), items(o, "stock"),
                o.has("research") ? Math.max(0, o.get("research").getAsInt()) : 0, o.has("merit") ? Math.max(0, o.get("merit").getAsInt()) : 0);
    }

    static Map<Item, Integer> items(JsonObject o, String key) {
        Map<Item, Integer> out = new LinkedHashMap<>();
        if (!o.has(key)) return out;
        for (var e : o.getAsJsonObject(key).entrySet()) {
            Item it = item(e.getKey(), null);
            if (it == null) { LivingRealms.LOG.warn("Quest reward: unknown item {}", e.getKey()); continue; }
            out.put(it, Math.max(1, e.getValue().getAsInt()));
        }
        return out;
    }

    static Item item(String id, Item fallback) {
        if (id.isEmpty()) return fallback;
        ResourceLocation rl = ResourceLocation.tryParse(id);
        Item it = rl == null ? null : BuiltInRegistries.ITEM.getOptional(rl).orElse(null);
        return it == null || it == Items.AIR ? fallback : it;
    }

    static String str(JsonObject o, String k) { return o.has(k) ? o.get(k).getAsString() : ""; }
}
