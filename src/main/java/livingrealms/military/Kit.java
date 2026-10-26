package livingrealms.military;

import livingrealms.smithing.Piece;
import net.minecraft.nbt.CompoundTag;

/** What a unit type should wear and wield, as smithing pattern ids ("" = nothing / keep). */
public final class Kit {
    public String armor = "";
    public String weapon = "";
    public Piece weaponPiece = Piece.SWORD;
    public boolean shield;

    public CompoundTag save() {
        CompoundTag t = new CompoundTag();
        t.putString("armor", armor);
        t.putString("weapon", weapon);
        t.putString("piece", weaponPiece.name());
        t.putBoolean("shield", shield);
        return t;
    }

    public static Kit load(CompoundTag t) {
        Kit k = new Kit();
        k.armor = t.getString("armor");
        k.weapon = t.getString("weapon");
        try { k.weaponPiece = Piece.valueOf(t.getString("piece")); } catch (Exception ignored) {}
        k.shield = t.getBoolean("shield");
        return k;
    }
}
