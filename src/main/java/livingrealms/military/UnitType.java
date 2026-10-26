package livingrealms.military;

import livingrealms.smithing.Piece;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;

/**
 * Soldiers a barracks can train. Each recruit comes from the town's population and must be handed its core weapon
 * from the armoury when training starts; armour comes from the unit's kit when it musters.
 */
public enum UnitType {
    FOOTMAN("Footman", "Sword or axe infantry. The backbone of any garrison.", null, 3, 0.12f, Items.IRON_SWORD, Piece.SWORD),
    SHIELDBEARER("Shieldbearer", "Holds the line with sword and shield.", "drill", 5, 0.16f, Items.SHIELD, Piece.SWORD, Piece.SHIELD),
    BOWMAN("Bowman", "Looses arrows from behind the line.", "archery", 5, 0.16f, Items.BOW, Piece.BOW),
    CROSSBOWMAN("Crossbowman", "Slow to reload, hits hard.", "crossbows", 7, 0.2f, Items.CROSSBOW, Piece.CROSSBOW),
    HORSEMAN("Horseman", "Mounted knight from the stables.", "cavalry", 10, 0.25f, Items.SADDLE, Piece.SWORD),
    DRAGON_RIDER("Dragon Rider", "Bowman riding your adult tamed dragon near the barracks.", "dragon_riders", 40, 0.6f, Items.DRAGON_BREATH, Piece.BOW);

    public final String label, description, research;
    public final int emeralds;
    /** Training time in settlement days at one level 1 barracks. */
    public final float trainDays;
    public final Item icon;
    /** Pieces the armoury must supply before training starts. SWORD stands for any melee weapon. */
    public final Piece[] needs;

    /** "Footmen", "Shieldbearers" */
    public String plural() { return label.endsWith("man") ? label.substring(0, label.length() - 3) + "men" : label + "s"; }

    UnitType(String label, String description, String research, int emeralds, float trainDays, Item icon, Piece... needs) {
        this.label = label;
        this.description = description;
        this.research = research;
        this.emeralds = emeralds;
        this.trainDays = trainDays;
        this.icon = icon;
        this.needs = needs;
    }
}
