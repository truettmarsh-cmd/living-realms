package livingrealms.client.ui;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.world.item.Items;

import java.util.ArrayList;
import java.util.List;
import java.util.function.BiConsumer;

import static livingrealms.client.ui.Imm.*;

/** Chronicle page: the town's fire risk, then its history, newest first or oldest first under year headings. */
final class ChroniclePanel {
    static boolean byYear;

    static void draw(Imm ui, int x, int y, int w, int h, CompoundTag view, boolean mine, BiConsumer<String, CompoundTag> act) {
        CompoundTag c = view.getCompound("chronicle"), fire = view.getCompound("fire");
        ListTag entries = c.getList("entries", Tag.TAG_COMPOUND);
        ui.big("Chronicle", x, y, TEXT, 1.4f);
        long today = c.getLong("today");
        ui.text(ui.clip(view.getString("name") + " · " + entries.size() + (entries.size() == 1 ? " entry" : " entries")
                + " · Day " + today + ", Year " + c.getInt("year"), w - 250), x, y + 15, MUTED);
        ui.segments("chronOrder", x + w - 244, y + 2, 140, 16, new String[]{"Newest first", "By year"}, byYear ? 1 : 0, i -> byYear = i == 1);
        ui.button("chronBook", x + w - 100, y + 2, 100, 16, "Written copy", Style.PRIMARY, mine, () -> act.accept("chronicleBook", new CompoundTag()));
        if (ui.over(x + w - 100, y + 2, 100, 16)) ui.tip("The scribe writes the chronicle into a book for you (1 emerald from the stockpile).",
                "Anyone can also use a blank book on the Town Hall.");

        // ---- fire risk
        int fy = y + 30, fh = 44;
        ui.card(x, fy, w - 4, fh);
        ui.item(Items.FLINT_AND_STEEL, x + 6, fy + 6);
        boolean on = fire.getBoolean("enabled");
        String label = fire.getString("label");
        int col = !on ? FAINT : switch (label) { case "High" -> RED; case "Moderate" -> ORANGE; case "Low" -> GOLD; default -> GREEN; };
        ui.text("Fire risk", x + 26, fy + 6, MUTED);
        ui.text(label, x + 30 + ui.width("Fire risk"), fy + 6, col);
        if (on) {
            double daily = fire.getDouble("daily");
            String every = daily <= 0 ? "no outbreaks expected" : "about one fire in " + Math.max(1, Math.round(1 / daily)) + " days";
            ui.right(every, x + w - 12, fy + 6, FAINT);
            String line = fire.getInt("wooden") + " of " + fire.getInt("buildings") + " buildings wooden · Well "
                    + (fire.getBoolean("well") ? "✔" : "✖") + " · Fire Watch " + (fire.getBoolean("watch") ? "✔" : "✖")
                    + (fire.getString("riskiest").isEmpty() ? "" : " · most at risk: " + fire.getString("riskiest"));
            if (fire.contains("burning")) {
                line = "FIRE! " + fire.getInt("burning") + " blocks burning (" + fire.getString("cause") + "), " + fire.getInt("crew") + " townsfolk with buckets";
                ui.text(ui.clip(line, w - 40), x + 26, fy + 20, RED);
            } else ui.text(ui.clip(line, w - 40), x + 26, fy + 20, MUTED);
            if (fire.contains("last")) ui.text(ui.clip("Last fire: " + fire.getString("last"), w - 40), x + 26, fy + 31, FAINT);
        } else ui.text("Town fires are switched off in the server config.", x + 26, fy + 20, FAINT);
        if (ui.over(x, fy, w - 4, fh)) ui.tip("Wooden buildings burn; stone ones hardly do. Crowded streets and forges add to the risk.",
                "A Well halves it (and speeds the bucket brigade); the Fire Watch research halves it again.",
                "Fire never spreads past the town's land. Burnt buildings are repaired by the builders.");

        // ---- the history
        int ly = fy + fh + 6, lh = h - (ly - y);
        List<Object[]> rows = new ArrayList<>();
        if (byYear) {
            int year = -1;
            for (int i = 0; i < entries.size(); i++) {
                CompoundTag e = entries.getCompound(i);
                if (e.getInt("y") != year) { year = e.getInt("y"); rows.add(new Object[]{"Year " + year}); }
                rows.add(new Object[]{e});
            }
        } else for (int i = entries.size() - 1; i >= 0; i--) rows.add(new Object[]{entries.getCompound(i)});
        int tw = w - 104;
        int total = 0;
        for (Object[] r : rows) total += r[0] instanceof String ? 16 : Math.max(20, ui.wrapHeight(((CompoundTag) r[0]).getString("t"), tw)) + 6;
        ui.card(x, ly, w - 4, lh);
        if (rows.isEmpty()) { ui.text("Nothing has been written yet.", x + 10, ly + 8, FAINT); return; }
        int off = ui.beginScroll("chronicle", x + 2, ly + 2, w - 8, lh - 4, total + 6);
        int ry = ly + 6 - off;
        for (Object[] r : rows) {
            if (r[0] instanceof String head) {
                ui.text(head, x + 10, ry + 3, GOLD);
                ui.hline(x + 14 + ui.width(head), ry + 7, w - 34 - ui.width(head), LINE);
                ry += 16;
                continue;
            }
            CompoundTag e = (CompoundTag) r[0];
            String kind = e.getString("k");
            int kc = switch (kind) {
                case "FIRE", "DISASTER", "RAID_LOST", "FALLEN", "REBELLION" -> RED;
                case "WAR", "RAID_REPELLED", "RAID_WON", "RAID_FAILED", "SIEGE", "CONQUERED" -> ORANGE;
                case "GROWTH", "NOTE" -> FAINT;
                case "HERO", "RANK", "CAPITAL", "CASTLE", "FOUNDING" -> GOLD;
                default -> BLUE;
            };
            ui.round(x + 10, ry + 3, 4, 4, 2, kc);
            ui.text("Day " + e.getLong("d"), x + 18, ry, MUTED);
            ui.text(ui.clip(e.getString("label"), 70), x + 18, ry + 10, alpha(kc, 0xB0));
            int hh = ui.wrap(e.getString("t"), x + 92, ry, tw, kind.equals("GROWTH") ? MUTED : TEXT);
            ry += Math.max(20, hh) + 6;
        }
        ui.endScroll();
    }

    private ChroniclePanel() {}
}
