package livingrealms.settlement;

import net.minecraft.util.RandomSource;

public final class Names {
    static final String[] PRE = {"Ash", "Bram", "Cold", "Elder", "Fair", "Glen", "Hart", "Iron", "Kings", "Long", "Mill", "North", "Oak",
            "Red", "Stone", "Thorn", "West", "Wolf", "Ember", "Frost", "Raven", "Silver", "Gold", "High", "Black", "White", "Green", "Bright",
            "Hollow", "Marsh", "Elm", "Briar", "Wind", "Dun", "Ox", "Fox", "Swan", "Crow", "Amber", "Brook"};
    static final String[] SUF = {"ford", "wick", "holm", "bury", "stead", "haven", "ton", "dale", "mere", "field", "gate", "crest",
            "moor", "brook", "hollow", "watch", "reach", "fell", "vale", "keep", "hill", "wood", "marsh", "barrow", "ridge"};

    public static String town(RandomSource r) {
        return PRE[r.nextInt(PRE.length)] + SUF[r.nextInt(SUF.length)];
    }

    private Names() {}
}
