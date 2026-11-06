package livingrealms;

import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;

/** Small English helpers for player-facing text, shared by server and client code. */
public final class Text {
    /** "smithy" → "smithies", "craftsman" → "craftsmen", "bread" → "bread", "arrow" → "arrows". */
    public static String plural(String word) {
        if (word.isEmpty()) return word;
        String lower = word.toLowerCase(java.util.Locale.ROOT);
        for (String same : new String[]{"bread", "glass", "sheep", "fish", "wheat", "iron", "gold", "stone", "food", "armour", "armor", "hay", "wool", "gear"})
            if (lower.equals(same) || lower.endsWith(" " + same)) return word;
        if (lower.endsWith("man")) return word.substring(0, word.length() - 3) + (Character.isUpperCase(word.charAt(word.length() - 3)) ? "Men" : "men");
        if (lower.endsWith("y") && word.length() > 1 && "aeiou".indexOf(lower.charAt(lower.length() - 2)) < 0)
            return word.substring(0, word.length() - 1) + "ies";
        if (lower.endsWith("s") || lower.endsWith("x") || lower.endsWith("ch") || lower.endsWith("sh")) return word + "es";
        if (lower.endsWith("f") && !lower.endsWith("ff")) return word.substring(0, word.length() - 1) + "ves";
        return word + "s";
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
