package livingrealms.smithing;

import net.minecraft.nbt.CompoundTag;

/** A blacksmith order. Materials are taken from the stockpile when it is placed; finished pieces go back into it. */
public final class ForgeOrder {
    public int id;
    public String pattern;
    public Piece piece;
    public int quantity;
    public int done;
    /** Work done on the current piece, in smith-days. */
    public float progress;

    public CompoundTag save() {
        CompoundTag t = new CompoundTag();
        t.putInt("id", id);
        t.putString("pattern", pattern);
        t.putString("piece", piece.name());
        t.putInt("qty", quantity);
        t.putInt("done", done);
        t.putFloat("progress", progress);
        return t;
    }

    public static ForgeOrder load(CompoundTag t) {
        ForgeOrder o = new ForgeOrder();
        o.id = t.getInt("id");
        o.pattern = t.getString("pattern");
        o.piece = Piece.valueOf(t.getString("piece"));
        o.quantity = t.getInt("qty");
        o.done = t.getInt("done");
        o.progress = t.getFloat("progress");
        return o;
    }
}
