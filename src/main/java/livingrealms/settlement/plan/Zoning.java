package livingrealms.settlement.plan;

import livingrealms.building.BuildingType;
import livingrealms.building.Category;
import livingrealms.settlement.Tier;

import java.util.Locale;

/**
 * Where each kind of building belongs, as distances from the town centre (R = the town's current radius):
 * services by the market place, houses and crafts in the middle, noisy and smelly trades and the military
 * out by the gate roads, fields, woods and mines at the edge.
 */
public final class Zoning {
    /** The hall and market place, and the castle ring a capital will need: no other lots inside this. */
    public static final int CORE = 20;

    public record Rule(int min, int max, int pref, boolean wantsHigh, boolean noHighInCore, int gap, int setback) {}

    public static Rule rule(BuildingType t, int radius, int alreadyBuilt) {
        int noisy = Math.max(24, (int) (0.45 * radius)), edge = Math.max(32, (int) (0.6 * radius));
        // once a town can wall itself in, fields, woods and mines go outside the old-town wall, as they did
        int outside = radius >= Tier.VILLAGE.radius ? Walls.standard().get(0).a() + Walls.BAND + 1 : 0;
        edge = Math.max(edge, outside);
        int far = 10_000;
        return switch (t.category) {
            case CIVIC -> new Rule(0, 12, 0, false, false, 2, 0);
            case MARKET -> new Rule(CORE, 48, CORE + 2, true, false, 2, 0);
            case TAVERN -> new Rule(CORE, 64, CORE + 4, true, false, 2, 1);
            case CHAPEL -> new Rule(CORE, 48, CORE + 6, false, false, 2, 2);
            case LIBRARY -> new Rule(CORE, 64, CORE + 8, false, false, 2, 1);
            case STORAGE -> new Rule(CORE, 80, 32, true, false, 2, 1);
            case HOUSING -> new Rule(CORE, far, CORE, false, true, 1, 1);
            case DECOR -> new Rule(CORE, far, CORE, false, false, 1, 0);
            case WORKSHOP -> smelly(t) ? new Rule(noisy, far, noisy, true, true, 2, 1) : new Rule(CORE + 4, far, 36, false, true, 2, 1);
            case SMITHY, CONSTRUCTION -> new Rule(noisy, far, noisy, true, true, 2, 1);
            case STABLE -> new Rule(Math.max(24, radius / 2), far, (int) (0.7 * radius), true, true, 3, 2);
            case BARRACKS -> new Rule(24, far, (int) (0.7 * radius), true, true, 3, 2);
            case TOWER -> new Rule(Math.max(CORE + 4, (int) (0.75 * radius)), far, radius, false, false, 3, 1);
            case LUMBER, MINE -> new Rule(edge, far, edge, false, true, 3, 1);
            // a camp's or hamlet's first fields may sit close; after that farms keep to the outskirts
            case FARM -> alreadyBuilt < 2 && radius <= Tier.HAMLET.radius ? new Rule(CORE, far, CORE + 4, false, true, 2, 1)
                    : new Rule(Math.max(Math.max(CORE, radius / 2), outside), far, Math.max(Math.max(CORE, radius / 2), outside), false, true, 2, 1);
            case WALL, ROAD -> new Rule(0, far, radius, false, false, 0, 0);
        };
    }

    /** Tanners, butchers and dyers: kept away from homes like the smiths. */
    public static boolean smelly(BuildingType t) {
        String s = (t.trade == null ? "" : t.trade) + " " + t.id;
        s = s.toLowerCase(Locale.ROOT);
        return s.contains("tann") || s.contains("leather") || s.contains("butcher") || s.contains("dye");
    }

    /** Smithies, mines, tanneries: houses keep their distance. */
    public static boolean noisy(BuildingType t) {
        return t.category == Category.SMITHY || t.category == Category.MINE || t.category == Category.CONSTRUCTION
                || t.category == Category.WORKSHOP && smelly(t);
    }

    private Zoning() {}
}
