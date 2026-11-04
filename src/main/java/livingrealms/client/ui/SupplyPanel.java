package livingrealms.client.ui;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;

import java.util.ArrayList;
import java.util.List;
import java.util.function.BiConsumer;

import static livingrealms.client.ui.Imm.*;

/** The Supply card on the Recruit Command overview: ammo, food and gear, and the two resupply switches. */
final class SupplyPanel {
    static final int HEIGHT = 64;

    static void draw(Imm ui, CompoundTag s, int x, int y, int w, BiConsumer<String, CompoundTag> op) {
        ui.card(x, y, w, HEIGHT);
        ui.text("Supply", x + 8, y + 6, MUTED);
        String from = s.getString("sources");
        String fromLine = from.isEmpty() ? "No source in reach" : "From " + from;
        ui.right(ui.clip(fromLine, w - 64), x + w - 8, y + 6, FAINT);
        if (ui.over(x + 50, y + 4, w - 58, 11)) ui.tip("Where this recruit restocks, in order",
                "1. Its town's armoury, while it is inside the town", "2. The chest or cart Recruits' upkeep command gave it (within 48 blocks)",
                "3. Your inventory, if you are within 16 blocks and allow it");
        // ammo, food, gear
        int sw = (w - 32) / 3, sy = y + 20;
        boolean ranged = s.getBoolean("ranged");
        int ammo = s.getInt("ammo"), ammoT = Math.max(1, s.getInt("ammoTarget"));
        String ammoName = s.getString("ammoName");
        if (ranged) stat(ui, cap(ammoName), ammo + " / " + ammoT, x + 8, sy, sw, ammo / (float) ammoT, ammo == 0 ? RED : ammo < ammoT / 2 ? GOLD : GREEN);
        else stat(ui, "Ammo", "not needed", x + 8, sy, sw, 0, FAINT);
        if (ui.over(x + 8, sy, sw, 16)) ui.tip(ranged ? cap(ammoName) : "Ammo", ranged ? "Tops up to " + ammoT + " when below " + (ammoT * 3 / 4) : "Only bowmen and crossbowmen carry arrows");
        int food = s.getInt("food"), foodT = Math.max(1, s.getInt("foodTarget"));
        stat(ui, "Food", food + " / " + foodT, x + 16 + sw, sy, sw, food / (float) foodT, food == 0 ? RED : food < foodT / 2 ? GOLD : GREEN);
        if (ui.over(x + 16 + sw, sy, sw, 16)) ui.tip("Food", "Recruits eat from their pack when hungry or hurt, and heal as they eat.", "Tops up to " + foodT + " when below " + foodT / 2 + "; nothing with effects is taken.");
        float gear = s.getFloat("gear");
        int broken = s.getInt("broken");
        String gv = broken > 0 ? broken + " broken" : Math.round(gear * 100) + "%";
        stat(ui, "Gear", gv, x + 24 + 2 * sw, sy, sw, broken > 0 ? 0 : gear, broken > 0 || gear <= .25f ? RED : gear <= .5f ? GOLD : GREEN);
        if (ui.over(x + 24 + 2 * sw, sy, sw, 16)) ui.tip("Gear", s.getString("gearPiece").isEmpty() ? "Everything is sound" : "Most worn: " + s.getString("gearPiece") + " (" + Math.round(gear * 100) + "%)",
                "Below 25% it is swapped for a spare as good or better, or mended by the town's smiths.", "A broken weapon, shield or armour piece is replaced in kind.");
        // switches, then what happened last
        int ty = y + HEIGHT - 17;
        boolean auto = s.getBoolean("auto"), mine = s.getBoolean("ownerInv");
        ui.text("Auto resupply", x + 8, ty + 2, TEXT);
        int tx = x + 12 + ui.width("Auto resupply");
        ui.toggle("rcSupplyAuto", tx, ty + 1, auto, () -> { CompoundTag t = new CompoundTag(); t.putBoolean("on", !auto); op.accept("supplyAuto", t); });
        if (ui.over(x + 8, ty, tx + 22 - x - 8, 13)) ui.tip("Auto resupply", "This recruit restocks itself out of combat, every few seconds.", "Off: nothing is ever taken for it.");
        String inv = w < 330 ? "My inventory" : "Allow from my inventory";
        int ix = tx + 34;
        ui.text(inv, ix, ty + 2, TEXT);
        int ix2 = ix + 4 + ui.width(inv);
        ui.toggle("rcSupplyOwner", ix2, ty + 1, mine, () -> { CompoundTag t = new CompoundTag(); t.putBoolean("on", !mine); op.accept("supplyOwner", t); });
        if (ui.over(ix, ty, ix2 + 22 - ix, 13)) ui.tip("Allow from my inventory", "All your recruits may take arrows, food and spare gear from your inventory", "while you are within 16 blocks. Never the item in your hand, your armour or off hand.");
        int lx = ix2 + 30, lw = x + w - 8 - lx;
        if (lw < 40) return;
        ListTag lack = s.getList("short", Tag.TAG_STRING);
        if (!lack.isEmpty() && auto) {
            List<String> l = new ArrayList<>();
            for (Tag t : lack) l.add(t.getAsString());
            ui.right(ui.clip("Short of " + String.join(", ", l), lw), x + w - 8, ty + 2, ORANGE);
        } else if (s.contains("note")) {
            long age = s.getLong("noteAge");
            String when = age < 60 ? age + "s ago" : age / 60 + "m ago";
            ui.right(ui.clip(cap(s.getString("note")) + " · " + when, lw), x + w - 8, ty + 2, FAINT);
            if (ui.over(lx, ty, lw, 12)) ui.tip(cap(s.getString("note")), when);
        } else if (!auto) ui.right(ui.clip("Resupply is off for this recruit", lw), x + w - 8, ty + 2, FAINT);
    }

    static void stat(Imm ui, String label, String value, int x, int y, int w, float frac, int color) {
        ui.text(ui.clip(label, w - ui.width(value) - 6), x, y, TEXT);
        ui.right(value, x + w, y, MUTED);
        ui.bar(x, y + 11, w, 5, Math.max(0, Math.min(1, frac)), color, Imm.lerp(color, 0xFF000000, 0.3f));
    }

    static String cap(String s) { return s.isEmpty() ? s : Character.toUpperCase(s.charAt(0)) + s.substring(1); }

    private SupplyPanel() {}
}
