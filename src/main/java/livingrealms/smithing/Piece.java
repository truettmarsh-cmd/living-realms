package livingrealms.smithing;

import net.minecraft.world.entity.EquipmentSlot;

/** What a blacksmith can be asked to make. Work is in smith-days at a level 1 smithy. */
public enum Piece {
    HELMET("Helmet", EquipmentSlot.HEAD, 0.10f),
    CHESTPLATE("Chestplate", EquipmentSlot.CHEST, 0.16f),
    LEGGINGS("Leggings", EquipmentSlot.LEGS, 0.14f),
    BOOTS("Boots", EquipmentSlot.FEET, 0.08f),
    SWORD("Sword", EquipmentSlot.MAINHAND, 0.10f),
    AXE("Axe", EquipmentSlot.MAINHAND, 0.10f),
    MACE("Mace", EquipmentSlot.MAINHAND, 0.12f),
    LANCE("Lance", EquipmentSlot.MAINHAND, 0.12f),
    SHIELD("Shield", EquipmentSlot.OFFHAND, 0.08f),
    BOW("Bow", EquipmentSlot.MAINHAND, 0.06f),
    CROSSBOW("Crossbow", EquipmentSlot.MAINHAND, 0.10f),
    ARROWS("Arrows", EquipmentSlot.OFFHAND, 0.03f);

    public final String label;
    public final EquipmentSlot slot;
    public final float work;

    Piece(String label, EquipmentSlot slot, float work) {
        this.label = label;
        this.slot = slot;
        this.work = work;
    }

    public boolean armor() { return slot.getType() == EquipmentSlot.Type.ARMOR; }

    public boolean melee() { return this == SWORD || this == AXE || this == MACE || this == LANCE; }

    public boolean ranged() { return this == BOW || this == CROSSBOW; }

    public static final Piece[] ARMOR = {HELMET, CHESTPLATE, LEGGINGS, BOOTS};
}
