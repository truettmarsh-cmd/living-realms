package livingrealms.citizen;

import livingrealms.building.Procedural;
import net.minecraft.util.RandomSource;

/** Given and family names for townsfolk, in the flavour of the town's building style (plains, desert, savanna, taiga, snowy). */
public final class PersonNames {
    record Pool(String[] men, String[] women, String[] families, boolean patronymic) {}

    static final Pool PLAINS = new Pool(
            new String[]{"Bertram", "Cuthbert", "Dunstan", "Fenwick", "Godric", "Ingram", "Kenric", "Ned", "Odo", "Ralf", "Tybalt", "Wat",
                    "Hob", "Elric", "Walter", "Simkin", "Geoffrey", "Hugh", "Piers", "Roger", "Martin", "Alan", "Jack", "Robin"},
            new String[]{"Alys", "Edith", "Hilda", "Jocelyn", "Lettice", "Mabel", "Petronel", "Sybil", "Agnes", "Rowena", "Maud", "Joan",
                    "Isabel", "Margery", "Cecily", "Avice", "Emma", "Juliana", "Beatrice", "Agatha", "Elena", "Rose", "Nell", "Tilda"},
            new String[]{"Miller", "Thatcher", "Fletcher", "Cooper", "Brewer", "Tanner", "Carter", "Baker", "Mason", "Webb", "Ashdown",
                    "Fairweather", "Hollis", "Brook", "Hayward", "Underwood", "Penny", "Greenleaf", "Shepherd", "Wright", "Cotter", "Reeve"}, false);
    static final Pool DESERT = new Pool(
            new String[]{"Amir", "Farid", "Nasir", "Tariq", "Zahir", "Kamal", "Hamid", "Idris", "Rashid", "Sami", "Yusuf", "Bashir"},
            new String[]{"Darya", "Leila", "Samira", "Yara", "Rana", "Soraya", "Nadia", "Amira", "Zainab", "Laleh", "Mina", "Shirin"},
            new String[]{"Haddad", "Nasser", "Karimi", "Bahar", "Sadri", "Farah", "Azizi", "Mansour", "Qasim", "Rostami", "Saleh", "Tahan"}, false);
    static final Pool SAVANNA = new Pool(
            new String[]{"Mateo", "Rafael", "Tomas", "Diego", "Marco", "Andres", "Bruno", "Elias", "Iker", "Lucas", "Nuno", "Pablo"},
            new String[]{"Lucia", "Ines", "Elena", "Sofia", "Alba", "Carmen", "Irene", "Marta", "Nora", "Paloma", "Rosa", "Vera"},
            new String[]{"Ferreira", "Navarro", "Costa", "Duarte", "Moreno", "Vidal", "Serrano", "Pires", "Ortega", "Lobo", "Prieto", "Soler"}, false);
    static final Pool NORTH = new Pool(
            new String[]{"Bjorn", "Leif", "Ulf", "Haldor", "Ragnar", "Einar", "Sven", "Gunnar", "Arne", "Torvald", "Orm", "Kettil"},
            new String[]{"Astrid", "Sigrid", "Ingrid", "Freydis", "Solveig", "Thora", "Gudrun", "Helga", "Ragna", "Liv", "Asa", "Signy"},
            new String[0], true);

    static Pool pool(String style) {
        return switch (Procedural.styleFamily(style)) {
            case "desert" -> DESERT;
            case "savanna" -> SAVANNA;
            case "taiga", "snowy" -> NORTH;
            default -> PLAINS;
        };
    }

    public static String first(String style, boolean female, RandomSource r) {
        Pool p = pool(style);
        String[] l = female ? p.women : p.men;
        return l[r.nextInt(l.length)];
    }

    /** A family name for a newcomer: in the north, son or daughter of a made-up father. */
    public static String family(String style, boolean female, RandomSource r) {
        Pool p = pool(style);
        if (p.patronymic) return patronymic(p.men[r.nextInt(p.men.length)], female);
        return p.families[r.nextInt(p.families.length)];
    }

    /** A child's family name: the parent's, or in the north a patronymic of the parent's given name. */
    public static String child(String style, Citizen parent, boolean female) {
        return pool(style).patronymic ? patronymic(parent.first, female) : parent.family;
    }

    static String patronymic(String father, boolean female) {
        String stem = father.endsWith("s") ? father : father + "s";
        return stem + (female ? "dottir" : "son");
    }

    private PersonNames() {}
}
