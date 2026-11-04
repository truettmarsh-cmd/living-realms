package livingrealms.client.ui;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;

import static livingrealms.client.ui.Imm.*;

/** The Brothers-in-arms card on the Recruit Command Overview: each bond's partner, level and progress. */
final class BondsPanel {
    static final String EMPTY = "No bonds yet. Recruits of yours who fight side by side and both live become Comrades, then Sworn Kin, then a Blood Oath.";
    static final int ROW = 22;

    static int height(Imm ui, CompoundTag b, int w) {
        ListTag list = b.getList("list", Tag.TAG_COMPOUND);
        int h = 20 + (list.isEmpty() ? ui.wrapHeight(EMPTY, w - 16) + 2 : list.size() * ROW);
        if (b.contains("status")) h += 12;
        return h + 4;
    }

    static int color(int level) { return switch (level) { case 1 -> BLUE; case 2 -> PURPLE; case 3 -> RED; default -> FAINT; }; }

    /** Draws the card at y; returns its height. */
    static int draw(Imm ui, CompoundTag b, int x, int y, int w) {
        int h = height(ui, b, w);
        ListTag list = b.getList("list", Tag.TAG_COMPOUND);
        ui.card(x, y, w, h);
        ui.text("Brothers-in-arms", x + 8, y + 6, MUTED);
        ui.right(b.getInt("count") + " / " + Math.max(1, b.getInt("max")) + " bonds", x + w - 8, y + 6, FAINT);
        if (ui.over(x, y, w, 18)) ui.tip("Brothers-in-arms", "Your recruits who fight within 12 blocks of each other and both live build a bond:",
                "at most 3 points a battle and 6 a day. Comrades +3% damage, Sworn Kin +6%,", "Blood Oath +10% and 10% less damage taken, all within 10 blocks.",
                "Up to 3 bonds, one of them a Blood Oath. When a partner falls, the rest grieve, then seek vengeance.");
        int ry = y + 20;
        if (list.isEmpty()) ry += ui.wrap(EMPTY, x + 8, ry, w - 16, FAINT) + 2;
        for (int i = 0; i < list.size(); i++) {
            CompoundTag r = list.getCompound(i);
            int level = r.getInt("level"), c = color(level), points = r.getInt("points"), from = r.getInt("from"), to = r.getInt("to");
            boolean forming = r.getBoolean("forming"), near = r.getBoolean("near");
            String value = to > 0 ? points + " / " + to : "sworn for life";
            int vx = x + w - 8 - ui.width(value);
            if (near) ui.text("●", x + 8, ry, GREEN);
            ui.text(ui.clip(r.getString("name") + " · " + (forming ? "forming" : r.getString("label")), vx - x - 26), x + 18, ry, forming ? MUTED : TEXT);
            ui.right(value, x + w - 8, ry, r.contains("held") ? ORANGE : MUTED);
            float frac = to > 0 ? (points - from) / (float) Math.max(1, to - from) : 1;
            ui.bar(x + 8, ry + 11, w - 16, 5, frac, c, Imm.lerp(c, 0xFF000000, 0.3f));
            if (ui.over(x, ry - 2, w, ROW)) ui.tip(r.getString("name") + (forming ? ": not yet a bond" : ": " + r.getString("label")), r.getString("effect"),
                    to > 0 ? (to - points) + " more points to " + r.getString("nextLabel") : "The strongest bond there is",
                    r.contains("held") ? r.getString("held") : near ? "Fighting beside this recruit now" : "Not within 10 blocks");
            ry += ROW;
        }
        if (b.contains("status")) {
            ui.text(ui.clip(b.getString("status"), w - 16), x + 8, ry, b.getBoolean("grief") ? ORANGE : GOLD);
            if (ui.over(x, ry - 1, w, 11)) ui.tip(b.getString("status"), b.getString("statusTip"));
        }
        return h;
    }

    private BondsPanel() {}
}
