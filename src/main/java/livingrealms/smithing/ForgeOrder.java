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
    /** Share of the recipe's materials actually charged (below 1 with the Grand Forge); refunds pay back this share. */
    public float costShare = 1;

    public CompoundTag save() {
        CompoundTag t = new CompoundTag();
        t.putInt("id", id);
        t.putString("pattern", pattern);
        t.putString("piece", piece.name());
        t.putInt("qty", quantity);
        t.putInt("done", done);
        t.putFloat("progress", progress);
        if (costShare != 1) t.putFloat("costShare", costShare);
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
        if (t.contains("costShare")) o.costShare = Math.max(0, Math.min(1, t.getFloat("costShare")));
        return o;
    }
}
