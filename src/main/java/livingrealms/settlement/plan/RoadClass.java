package livingrealms.settlement.plan;

import livingrealms.settlement.Tier;

/** Road hierarchy: the paved market place round the hall, high streets to the gates, ring streets and lanes. */
public enum RoadClass {
    PLAZA(5), HIGH(5), STREET(3), LANE(2);

    public final int width;

    RoadClass(int width) { this.width = width; }

    /** Planning.ROAD_MATERIALS key: better paving for busier roads and bigger towns. */
    public String material(Tier tier, boolean desert) {
        int t = tier.number();
        if (desert) return this == PLAZA && t >= 3 ? "stone_bricks" : "gravel";
        return switch (this) {
            case PLAZA -> t >= 4 ? "stone_bricks" : t >= 3 ? "cobblestone" : "gravel";
            case HIGH -> t >= 4 ? "cobblestone" : t >= 3 ? "gravel" : "path";
            case STREET -> t >= 4 ? "gravel" : "path";
            case LANE -> "path";
        };
    }
}
