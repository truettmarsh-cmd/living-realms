package livingrealms;

import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;

/** Small English helpers for player-facing text, shared by server and client code. */
public final class Text {
    /** Words that stay the same in the plural, matched as the last word of a name ("Raw Cod", "Cooked Beef"). */
    private static final java.util.Set<String> SAME = java.util.Set.of("bread", "glass", "sheep", "fish", "wheat", "iron", "gold", "stone",
            "cobblestone", "food", "armour", "armor", "hay", "wool", "gear", "drowned", "silverfish", "cod", "salmon", "deer", "townsfolk",
            "folk", "beef", "mutton", "pork", "dirt", "sand", "gravel", "flint", "leather", "coal", "redstone", "string", "paper", "sugar",
            "clay", "snow", "ice", "honey", "milk", "lava", "water", "dust", "gunpowder", "meal", "scrap", "lazuli", "netherrack", "obsidian",
            "kelp", "bamboo", "moss", "grass", "seagrass", "timber", "lumber", "copper", "quartz", "prismarine", "terracotta", "concrete");
    /** Irregular plurals, again by last word. */
    private static final java.util.Map<String, String> IRREGULAR = java.util.Map.ofEntries(
            java.util.Map.entry("wolf", "wolves"), java.util.Map.entry("leaf", "leaves"), java.util.Map.entry("loaf", "loaves"),
            java.util.Map.entry("shelf", "shelves"), java.util.Map.entry("half", "halves"), java.util.Map.entry("calf", "calves"),
            java.util.Map.entry("thief", "thieves"), java.util.Map.entry("knife", "knives"), java.util.Map.entry("potato", "potatoes"),
            java.util.Map.entry("tomato", "tomatoes"), java.util.Map.entry("hero", "heroes"), java.util.Map.entry("child", "children"),
            java.util.Map.entry("person", "people"), java.util.Map.entry("foot", "feet"), java.util.Map.entry("tooth", "teeth"),
            java.util.Map.entry("mouse", "mice"), java.util.Map.entry("goose", "geese"), java.util.Map.entry("ox", "oxen"),
            java.util.Map.entry("human", "humans"), java.util.Map.entry("dwarf", "dwarves"), java.util.Map.entry("elf", "elves"));

    /** "smithy" → "smithies", "craftsman" → "craftsmen", "barracks" → "barracks", "Wheat Seeds" → "Wheat Seeds", "wolf" → "wolves". */
    public static String plural(String word) {
        if (word.isEmpty()) return word;
        int space = word.lastIndexOf(' ');
        String head = word.substring(0, space + 1), last = word.substring(space + 1);
        String lower = last.toLowerCase(java.util.Locale.ROOT);
        if (lower.isEmpty() || SAME.contains(lower) || lower.endsWith("fish")) return word;
        String irr = IRREGULAR.get(lower);
        if (irr != null) return head + matchCase(last, irr);
        if (lower.endsWith("man") && !lower.endsWith("human") && !lower.endsWith("german") && !lower.endsWith("shaman"))
            return head + last.substring(0, last.length() - 3) + (Character.isUpperCase(last.charAt(last.length() - 3)) ? "Men" : "men");
        if (lower.endsWith("y") && lower.length() > 1 && "aeiou".indexOf(lower.charAt(lower.length() - 2)) < 0)
            return head + last.substring(0, last.length() - 1) + "ies";
        if (lower.endsWith("ss") || lower.endsWith("x") || lower.endsWith("z") || lower.endsWith("ch") || lower.endsWith("sh")) return word + "es";
        if (lower.endsWith("s")) return word; // already plural: barracks, stables, planks, bricks, seeds
        return word + "s";
    }

    private static String matchCase(String like, String word) {
        return Character.isUpperCase(like.charAt(0)) ? Character.toUpperCase(word.charAt(0)) + word.substring(1) : word;
    }

    /** Plural spot checks, run from a self-test; returns the failures as "word → got (want)". */
    public static java.util.List<String> selfCheck() {
        String[][] cases = {{"smithy", "smithies"}, {"library", "libraries"}, {"barracks", "barracks"}, {"stables", "stables"},
                {"craftsman", "craftsmen"}, {"Zombie", "Zombies"}, {"Enderman", "Endermen"}, {"wolf", "wolves"}, {"Drowned", "Drowned"},
                {"Silverfish", "Silverfish"}, {"Oak Planks", "Oak Planks"}, {"Wheat Seeds", "Wheat Seeds"}, {"Stone Bricks", "Stone Bricks"},
                {"Potato", "Potatoes"}, {"Iron Ingot", "Iron Ingots"}, {"Bread", "Bread"}, {"Raw Cod", "Raw Cod"}, {"Fox", "Foxes"},
                {"Witch", "Witches"}, {"Bowman", "Bowmen"}, {"Human", "Humans"}, {"Iron Golem", "Iron Golems"}, {"cottage", "cottages"},
                {"Townsfolk", "Townsfolk"}, {"Cobblestone", "Cobblestone"}, {"Pufferfish", "Pufferfish"}, {"bakery", "bakeries"}, {"day", "days"}};
        java.util.List<String> bad = new java.util.ArrayList<>();
        for (String[] c : cases) if (!plural(c[0]).equals(c[1])) bad.add(c[0] + " → " + plural(c[0]) + " (" + c[1] + ")");
        if (!count(1, "kill").equals("1 kill") || !count(3, "kill").equals("3 kills")) bad.add("count");
        if (!a("ice dragon").equals("an ice dragon") || !a("fire dragon").equals("a fire dragon")) bad.add("article");
        return bad;
    }

    /** "1 kill", "3 kills", "2 smithies". */
    public static String count(long n, String word) { return n + " " + (n == 1 ? word : plural(word)); }

    /** "a fire dragon", "an ice dragon", "an Iron Golem". */
    public static String a(String noun) {
        if (noun.isEmpty()) return noun;
        char c = Character.toLowerCase(noun.charAt(0));
        return ("aeiou".indexOf(c) >= 0 ? "an " : "a ") + noun;
    }

    /** "is" or "are" for a count. */
    public static String isAre(long n) { return n == 1 ? "is" : "are"; }

    /** How a killer or foe is named in chat and chronicles: a player's or named mob's own name, else "a Zombie" / "an Enderman". */
    public static String who(Entity e) {
        if (e instanceof Player || e.hasCustomName()) return e.getName().getString();
        return a(e.getType().getDescription().getString());
    }

    private Text() {}
}
