package livingrealms.client.ui;

import livingrealms.battle.BattleReport;
import livingrealms.battle.BattleReports;
import livingrealms.net.Net;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static livingrealms.client.ui.Imm.*;

/** Recruit Command → Reports: the owner's last battles (BattleReports), a list and the chosen report in detail. */
public final class ReportsPanel {
    public static final int KEEP = livingrealms.battle.BattleData.KEEP;
    private static final long REFRESH_MS = 3000;
    private static List<BattleReport> reports = List.of();
    private static int active;
    private static boolean received;
    private static long requested;

    private final Imm ui;
    private long selected = -1;
    private boolean mineOnly, detail;
    private int detailHeight = 200;

    ReportsPanel(Imm ui) { this.ui = ui; }

    /** Forgets the last server's reports (on logout), so another world or server never shows them. */
    public static void clear() { reports = List.of(); active = 0; received = false; requested = 0; }

    /** The "reports" packet from the server. */
    public static void receive(CompoundTag data) {
        List<BattleReport> list = new ArrayList<>();
        for (Tag t : data.getList("reports", Tag.TAG_COMPOUND)) list.add(BattleReport.load((CompoundTag) t));
        reports = list;
        active = data.getInt("active");
        received = true;
    }

    static void refresh() {
        long now = System.currentTimeMillis();
        if (now - requested < REFRESH_MS) return;
        requested = now;
        Net.toServer("reports", new CompoundTag());
    }

    static Item icon(BattleReport.Kind kind) {
        ResourceLocation rl = ResourceLocation.tryParse(BattleReports.icon(kind));
        return rl == null ? Items.STONE_SWORD : BuiltInRegistries.ITEM.getOptional(rl).orElse(Items.STONE_SWORD);
    }

    static int colour(BattleReport.Kind kind) {
        return switch (kind) {
            case DRAGON -> ORANGE;
            case RAID_DEFENDED, SIEGE_DEFENDED -> GOLD;
            case RAID_ATTACKED, SIEGE_LAID -> RED;
            default -> TEXT;
        };
    }

    void draw(int x, int y, int w, int h, UUID recruit) {
        refresh();
        List<BattleReport> shown = mineOnly ? reports.stream().filter(r -> r.fighter(recruit) != null).toList() : reports;
        int segW = Math.min(200, w - 4);
        ui.segments("rpFilter", x, y, segW, 16, new String[]{"All battles", "This recruit"}, mineOnly ? 1 : 0, i -> { mineOnly = i == 1; ui.resetScroll("rpList"); });
        String status = active > 0 ? active + (active == 1 ? " battle" : " battles") + " being fought" : shown.size() + " of " + KEEP + " kept · newest first";
        if (w - segW - 12 > 60) ui.right(ui.clip(status, w - segW - 12), x + w - 4, y + 4, active > 0 ? ORANGE : FAINT);
        int top = y + 22, bh = h - 22;
        if (shown.isEmpty()) { empty(x, top, w - 4); return; }
        BattleReport pick = shown.stream().filter(r -> r.id == selected).findFirst().orElse(shown.get(0));
        selected = pick.id;
        boolean split = w >= 340;
        int listW = split ? Math.max(150, w * 2 / 5) : w;
        if (split || !detail) list(shown, recruit, x, top, listW - (split ? 4 : 0), bh);
        if (split) report(pick, recruit, x + listW + 2, top, w - listW - 2, bh, false);
        else if (detail) report(pick, recruit, x, top, w, bh, true);
    }

    void empty(int x, int y, int w) {
        ui.card(x, y, w, 64);
        ui.text(received ? (mineOnly ? "This recruit has no battles on record" : "No battles yet") : "Loading reports…", x + 10, y + 8, GOLD);
        ui.wrap("When your recruits fight, a report is written once things have been quiet for 20 seconds: what it was, the enemies slain, "
                + "who fell, who was wounded, the MVP and who rose in rank.", x + 10, y + 22, w - 20, MUTED);
    }

    void list(List<BattleReport> shown, UUID recruit, int x, int y, int w, int h) {
        int row = 30;
        int off = ui.beginScroll("rpList", x, y, w, h, shown.size() * row);
        int ry = y - off;
        for (BattleReport r : shown) {
            boolean on = r.id == selected, hover = ui.over(x, ry, w - 4, row - 2) && ui.over(x, y, w, h);
            if (on) { ui.round(x, ry, w - 4, row - 2, 4, 0xFF2E3542, 0xFF272D38); ui.round(x, ry + 3, 2, row - 8, 1, GOLD); }
            else if (hover) ui.round(x, ry, w - 4, row - 2, 4, 0x18FFFFFF);
            ui.item(icon(r.kind), x + 5, ry + 6);
            String day = "Day " + r.day();
            ui.right(day, x + w - 9, ry + 4, FAINT);
            ui.text(ui.clip(r.kind.label, w - 40 - ui.width(day)), x + 25, ry + 4, on ? GOLD : colour(r.kind));
            String where = r.place.isEmpty() ? r.pos.getX() + ", " + r.pos.getZ() : r.place;
            boolean was = r.fighter(recruit) != null;
            int room = w - 36 - (was ? 8 : 0);
            ui.text(ui.clip(where + " · " + r.tally(), room), x + 25, ry + 15, r.fallen().isEmpty() ? MUTED : 0xFFC88A8A);
            if (was) ui.round(x + w - 12, ry + 17, 4, 4, 2, BLUE);
            if (hover) {
                List<String> tip = new ArrayList<>(List.of(r.title(), r.tally() + " · " + BattleReport.duration(r.seconds())));
                if (!r.kills.isEmpty()) tip.add("Slain: " + r.killList());
                if (was) tip.add("This recruit fought here");
                ui.tip(tip);
            }
            final long id = r.id;
            ui.area(x, ry, w - 4, row - 2, () -> { selected = id; detail = true; ui.resetScroll("rpDetail"); });
            ry += row;
        }
        ui.endScroll();
    }

    void report(BattleReport r, UUID recruit, int x, int y, int w, int h, boolean back) {
        int off = ui.beginScroll("rpDetail", x, y, w, h, detailHeight);
        int top = y - off, cy = top;
        int iw = w - 6;
        // heading
        ui.card(x, cy, iw, 46);
        ui.item(icon(r.kind), x + 7, cy + 7);
        int bw = back ? 48 : 0;
        ui.text(ui.clip(r.title(), iw - 34 - bw), x + 28, cy + 6, colour(r.kind));
        String when = "Day " + r.day() + ", " + BattleReport.clockText(r.clock) + " · lasted " + BattleReport.duration(r.seconds()) + " · " + r.fighters.size()
                + (r.fighters.size() == 1 ? " recruit" : " recruits");
        ui.text(ui.clip(when, iw - 34), x + 28, cy + 18, MUTED);
        String foe = r.foe.isEmpty() ? (r.dim.equals("minecraft:overworld") ? "" : r.dim) : "Against " + r.foe;
        ui.text(ui.clip(foe.isEmpty() ? r.pos.getX() + ", " + r.pos.getY() + ", " + r.pos.getZ() : foe, iw - 34), x + 28, cy + 30, FAINT);
        if (back) ui.button("rpBack", x + iw - bw - 4, cy + 4, bw, 15, "Back", Style.SECONDARY, true, () -> { detail = false; });
        cy += 50;
        // tiles
        int tw = (iw - 8) / 3;
        tile(x, cy, tw, "Slain", String.valueOf(r.slain()), GREEN);
        tile(x + tw + 4, cy, tw, "Fallen", String.valueOf(r.fallen().size()), r.fallen().isEmpty() ? MUTED : RED);
        tile(x + 2 * (tw + 4), cy, iw - 2 * (tw + 4), "Wounded", String.valueOf(r.wounded().size()), r.wounded().isEmpty() ? MUTED : ORANGE);
        cy += 32;
        // MVP
        BattleReport.Fighter mvp = r.mvp();
        cy = section(x, cy, iw, "Most valuable", mvp == null ? 1 : 2);
        if (mvp == null) cy = line(x, cy, iw, "Nobody landed a blow.", FAINT);
        else {
            cy = line(x, cy, iw, "♛ " + mvp.name + (mvp.id.equals(recruit) ? " (this recruit)" : "") + " · " + mvp.unit, GOLD);
            cy = line(x, cy, iw, Imm.num(mvp.dealt) + " damage, " + mvp.kills + (mvp.kills == 1 ? " kill" : " kills") + " · merit +" + Math.round(mvp.score()), MUTED);
        }
        cy += 6;
        // enemies
        String slain = r.kills.isEmpty() ? "None slain." : r.killList();
        cy = section(x, cy, iw, "Enemies slain", 0);
        cy += ui.wrap(slain, x + 8, cy, iw - 16, r.kills.isEmpty() ? FAINT : TEXT) + 6;
        // fallen
        List<BattleReport.Fighter> fallen = r.fallen();
        if (!fallen.isEmpty()) {
            cy = section(x, cy, iw, "Fallen", fallen.size());
            for (BattleReport.Fighter f : fallen) cy = line(x, cy, iw, "✝ " + f.name + " · " + f.unit + (f.kills > 0 ? " · " + f.kills + (f.kills == 1 ? " kill" : " kills") : "") + mark(f, recruit), RED);
            cy += 6;
        }
        // wounded
        List<BattleReport.Fighter> wounded = r.wounded();
        if (!wounded.isEmpty()) {
            cy = section(x, cy, iw, "Wounded", wounded.size());
            for (BattleReport.Fighter f : wounded) {
                String hp = f.max > 0 ? " · " + Imm.num(f.hp) + " / " + Imm.num(f.max) + " HP" : "";
                cy = line(x, cy, iw, f.name + " · took " + Imm.num(f.taken) + hp + mark(f, recruit), f.max > 0 && f.hp < f.max * .3f ? ORANGE : TEXT);
            }
            cy += 6;
        }
        // advances
        List<BattleReport.Fighter> advanced = r.advanced();
        if (!advanced.isEmpty()) {
            int n = 0;
            for (BattleReport.Fighter f : advanced) n += f.advances.size();
            cy = section(x, cy, iw, "Advances", n);
            for (BattleReport.Fighter f : advanced) for (String a : f.advances) cy = line(x, cy, iw, f.name + ": " + a, PURPLE);
            cy += 6;
        }
        // roll
        cy = section(x, cy, iw, "Roll", r.fighters.size());
        int cDmg = x + iw - 112, cK = x + iw - 66, cT = x + iw - 34;
        ui.right("dealt", cDmg + 30, cy, FAINT); ui.right("kills", cK + 22, cy, FAINT); ui.right("taken", cT + 26, cy, FAINT);
        cy += 11;
        List<BattleReport.Fighter> roll = new ArrayList<>(r.fighters);
        roll.sort((a, b) -> Float.compare(b.score(), a.score()));
        for (BattleReport.Fighter f : roll) {
            boolean me = f.id.equals(recruit);
            if (me) ui.rect(x + 4, cy - 2, iw - 8, 11, 0x203A6FA8);
            String name = (f.royal ? "♛ " : "") + (f.fell ? "✝ " : "") + f.name;
            ui.text(ui.clip(name, cDmg - x - 16), x + 8, cy, f.fell ? RED : me ? BLUE : TEXT);
            ui.right(Imm.num(f.dealt), cDmg + 30, cy, MUTED); ui.right(String.valueOf(f.kills), cK + 22, cy, MUTED); ui.right(Imm.num(f.taken), cT + 26, cy, MUTED);
            cy += 11;
        }
        cy += 4;
        detailHeight = cy - top;
        ui.endScroll();
    }

    static String mark(BattleReport.Fighter f, UUID recruit) { return f.id.equals(recruit) ? " (this recruit)" : ""; }

    void tile(int x, int y, int w, String label, String value, int colour) {
        ui.card(x, y, w, 28);
        ui.text(ui.clip(label, w - 12), x + 6, y + 4, MUTED);
        ui.text(value, x + 6, y + 15, colour);
    }

    int section(int x, int y, int w, String title, int count) {
        ui.text(title, x + 4, y, GOLD);
        if (count > 0 && !title.equals("Most valuable")) ui.text(String.valueOf(count), x + 10 + ui.width(title), y, FAINT);
        ui.hline(x + 4, y + 10, w - 8, LINE);
        return y + 14;
    }

    int line(int x, int y, int w, String text, int colour) {
        ui.text(ui.clip(text, w - 16), x + 8, y, colour);
        return y + 11;
    }
}
