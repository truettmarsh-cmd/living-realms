package livingrealms.wonders;

import livingrealms.building.Category;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;

import javax.annotation.Nullable;

/**
 * The five Wonders (docs/wonders.md). Each is an ordinary procedural building of its category, raised only in a Capital,
 * once per kingdom, after its own research. Its effects reach every city of the kingdom that holds it.
 */
public enum Wonder {
    CATHEDRAL("grand_cathedral", "Grand Cathedral", Category.CHAPEL, Items.BELL,
            "+" + Wonders.CATHEDRAL_MOOD + " mood in every city. Outbreaks " + pct(Wonders.CATHEDRAL_OUTBREAK) + " as likely, spread slower, heal faster. Once a day a recruit falling in the kingdom may be spared."),
    ACADEMY("royal_academy", "Royal Academy", Category.LIBRARY, Items.ENCHANTING_TABLE,
            "Research ×" + Wonders.ACADEMY_RESEARCH + " in every city. Saved study holds twice as much and pours in twice as fast."),
    FORGE("grand_forge", "Grand Forge", Category.SMITHY, Items.ANVIL,
            "Smiths work ×" + Wonders.FORGE_SPEED + " faster and orders cost " + pct(1 - Wonders.FORGE_COST) + " less. Armoury gear handed to recruits is Masterwork."),
    EXCHANGE("grand_exchange", "Grand Exchange", Category.MARKET, Items.EMERALD_BLOCK,
            "Caravans sell for ×" + Wonders.EXCHANGE_CARAVAN + ". Every city's treasury gains +" + Wonders.EXCHANGE_TRICKLE + " emeralds a day."),
    CITADEL("citadel_of_heroes", "Citadel of Heroes", Category.BARRACKS, Items.GOLDEN_SWORD,
            "Recruits trained in the kingdom start " + Wonders.CITADEL_LEVELS + " levels higher with a skill point. Garrison cap +" + pct(Wonders.CITADEL_GARRISON - 1) + " (at least +" + Wonders.CITADEL_GARRISON_MIN + ").");

    /** Procedural kind, also the research id. */
    public final String kind;
    public final String label;
    public final Category category;
    public final Item icon;
    public final String effect;

    Wonder(String kind, String label, Category category, Item icon, String effect) {
        this.kind = kind;
        this.label = label;
        this.category = category;
        this.icon = icon;
        this.effect = effect;
    }

    /** The building type id, livingrealms:kind. */
    public String id() { return "livingrealms:" + kind; }

    /** The research that unlocks it (data/livingrealms/livingrealms_research/kind.json). */
    public String research() { return kind; }

    @Nullable
    public static Wonder byType(@Nullable String typeId) {
        if (typeId == null || !typeId.startsWith("livingrealms:")) return null;
        for (Wonder w : values()) if (w.id().equals(typeId)) return w;
        return null;
    }

    @Nullable
    public static Wonder byKind(String kind) {
        for (Wonder w : values()) if (w.kind.equals(kind)) return w;
        return null;
    }

    static String pct(double f) { return Math.round(f * 100) + "%"; }
}
