package livingrealms.client.ui;

import net.minecraft.client.gui.components.EditBox;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.world.item.Items;

import java.util.*;
import java.util.function.BiConsumer;

import static livingrealms.client.ui.Imm.*;

/**
 * People page: the mayor and captain with Appoint buttons, a searchable list of the townsfolk (name, job, age, traits,
 * mood) and a card for the one selected (home, mood reasons, traits, family). Data from Citizens.view ("people").
 */
public final class PeoplePanel {
    static final int ROW_H = 24;
    static final Map<String, String> TRAIT_LABEL = Map.of("HARDWORKING", "hardworking", "PIOUS", "pious", "BRAVE", "brave",
            "GREEDY", "greedy", "SICKLY", "sickly", "CHEERFUL", "cheerful");
    static final Map<String, String> TRAIT_EFFECT = Map.of(
            "HARDWORKING", "Content at work, restless when idle. As mayor: builders work 8% faster.",
            "PIOUS", "Happier with a chapel in town. As mayor: +3 town mood.",
            "BRAVE", "Shrugs off half the fear after raids and fires. As mayor: the whole town does.",
            "GREEDY", "Feels every tax twice over. As mayor: +15% taxes, -2 town mood.",
            "SICKLY", "Often unwell (-4 mood) and dies younger.",
            "CHEERFUL", "+6 mood. As mayor: +4 town mood.");

    /** Selected citizen id; list mode: "" people, "mayor" choosing a mayor, "captain" choosing a captain. */
    static int selected;
    static String mode = "";

    /** For the screenshot tour: select a citizen, or switch the list ("", "mayor", "captain"). */
    public static void select(int id) { selected = id; mode = ""; }

    public static void choose(String listMode) { mode = listMode; }

    static void draw(Imm ui, int x, int y, int w, int h, CompoundTag t, boolean mine, EditBox search, BiConsumer<String, CompoundTag> act) {
        ListTag people = t.getList("people", Tag.TAG_COMPOUND);
        Map<Integer, CompoundTag> byId = new HashMap<>();
        for (Tag e : people) byId.put(((CompoundTag) e).getInt("id"), (CompoundTag) e);
        int mood = t.getInt("mood");
        ui.big("People", x, y, TEXT, 1.4f);
        String sub = t.getInt("population") + " townsfolk · " + t.getInt("children") + " children · mood " + mood + " (" + moodLabel(mood).toLowerCase(Locale.ROOT) + ")"
                + " · " + t.getInt("births") + " born, " + t.getInt("deaths") + " died, " + t.getInt("weddings") + " weddings";
        ui.text(ui.clip(sub, w - 130), x, y + 15, MUTED);
        search.setX(x + w - 118);
        search.setY(y + 4);
        search.setWidth(112);
        ui.round(x + w - 122, y, 120, 18, 5, 0xFF0F1216);
        if (!t.getBoolean("started")) {
            ui.text("The townsfolk are being counted; check back in a moment.", x, y + 40, FAINT);
            return;
        }
        int top = y + 30;

        // ---- notables
        int half = (w - 10) / 2, nh = 44;
        CompoundTag mayor = byId.get(t.getInt("mayor"));
        ui.card(x, top, half, nh);
        ui.item(Items.GOLDEN_HELMET, x + 6, top + 6);
        ui.text("Mayor", x + 26, top + 5, GOLD);
        ui.text(ui.clip(mayor == null ? "None" : mayor.getString("n"), half - 100), x + 26 + ui.width("Mayor  "), top + 5, TEXT);
        String how = mayor == null ? "Elected when someone is fit" : t.getBoolean("mayorAppointed") ? "Appointed · " + t.getString("mayorBonus")
                : "Elected · next vote in " + t.getInt("nextElection") + "d · " + t.getString("mayorBonus");
        ui.text(ui.clip(how, half - 32), x + 26, top + 17, MUTED);
        if (mayor != null && ui.over(x, top, half - 130, nh)) ui.tip(mayor.getString("n"), mayor.getString("j") + ", " + mayor.getInt("a"), "Bonus: " + t.getString("mayorBonus"),
                t.getBoolean("mayorAppointed") ? "Appointed: no elections until they die or you call one" : "Re-elected every 12 days unless someone is clearly more respected");
        if (mayor != null) ui.area(x, top, half - 130, nh, () -> { selected = mayor.getInt("id"); mode = ""; });
        ui.button("pplAppointMayor", x + half - 64, top + 28, 58, 13, mode.equals("mayor") ? "Cancel" : "Appoint", mode.equals("mayor") ? Style.SECONDARY : Style.PRIMARY, mine,
                () -> mode = mode.equals("mayor") ? "" : "mayor");
        if (t.getBoolean("mayorAppointed"))
            ui.button("pplElect", x + half - 124, top + 28, 56, 13, "Hold vote", Style.SECONDARY, mine, () -> act.accept("holdElection", new CompoundTag()));

        int kx = x + half + 10;
        ui.card(kx, top, half, nh);
        ui.item(Items.IRON_SWORD, kx + 6, top + 6);
        ui.text("Captain", kx + 26, top + 5, GOLD);
        boolean hasCap = t.hasUUID("captain");
        ui.text(ui.clip(hasCap ? t.getString("captainName") : "None", half - 110), kx + 26 + ui.width("Captain  "), top + 5, TEXT);
        String capHow = !hasCap ? "Needs soldiers" : t.getString("captainTitle") + " · " + t.getInt("captainKills") + " kills · "
                + (t.getBoolean("captainAppointed") ? "appointed" : "the best soldier");
        ui.text(ui.clip(capHow, half - 32), kx + 26, top + 17, MUTED);
        ui.button("pplAppointCaptain", kx + half - 64, top + 28, 58, 13, mode.equals("captain") ? "Cancel" : "Appoint", mode.equals("captain") ? Style.SECONDARY : Style.PRIMARY,
                mine && !t.getList("soldiers", Tag.TAG_COMPOUND).isEmpty(), () -> mode = mode.equals("captain") ? "" : "captain");
        if (t.getBoolean("captainAppointed"))
            ui.button("pplFreeCaptain", kx + half - 124, top + 28, 56, 13, "By merit", Style.SECONDARY, mine, () -> act.accept("freeCaptain", new CompoundTag()));
        top += nh + 6;

        String ask = t.getString("ask");
        if (!ask.isEmpty()) {
            ui.round(x, top, w - 4, 14, 4, alpha(GOLD, 0x22));
            ui.text(ui.clip(ask + " (see the bounty board)", w - 14), x + 6, top + 3, GOLD);
            top += 18;
        }
        int shock = t.getInt("shock");
        if (shock > 0) {
            ui.round(x, top, w - 4, 14, 4, alpha(RED, 0x22));
            ui.text(ui.clip("The town is frightened after " + t.getString("shockWhy") + " (-" + shock + " mood, fading)", w - 14), x + 6, top + 3, RED);
            top += 18;
        }

        // ---- list and card
        int listW = Math.max(150, (w - 8) * 11 / 20), cardX = x + listW + 6, cardW = w - listW - 10, bodyH = y + h - top;
        if (mode.equals("captain")) soldiers(ui, x, top, listW, bodyH, t, act);
        else list(ui, x, top, listW, bodyH, people, byId, t.getInt("mayor"), search.getValue().trim().toLowerCase(Locale.ROOT), act);
        CompoundTag sel = byId.get(selected);
        if (sel != null) card(ui, cardX, top, cardW, bodyH, sel, byId, t.getInt("mayor"));
        else memorial(ui, cardX, top, cardW, bodyH, t.getList("memorial", Tag.TAG_COMPOUND), people.size(), t.getInt("population"));
    }

    static void list(Imm ui, int x, int y, int w, int h, ListTag people, Map<Integer, CompoundTag> byId, int mayor, String q, BiConsumer<String, CompoundTag> act) {
        List<CompoundTag> rows = new ArrayList<>();
        boolean choosing = mode.equals("mayor");
        for (Tag e : people) {
            CompoundTag c = (CompoundTag) e;
            if (choosing && (c.getString("st").equals("Child") || c.getString("st").equals("Youth"))) continue;
            if (!q.isEmpty() && !(c.getString("n") + " " + c.getString("j") + " " + traits(c) + " " + c.getString("st")).toLowerCase(Locale.ROOT).contains(q)) continue;
            rows.add(c);
        }
        // the mayor first, then adults by name, children after their elders
        rows.sort(Comparator.comparing((CompoundTag c) -> c.getInt("id") != mayor).thenComparing(c -> c.getString("st").equals("Child") || c.getString("st").equals("Youth"))
                .thenComparing(c -> c.getString("n")));
        ui.card(x, y, w, h);
        if (choosing) ui.text("Choose a new mayor:", x + 8, y + 5, GOLD);
        int ly = y + (choosing ? 16 : 3), lh = h - (choosing ? 19 : 6);
        if (rows.isEmpty()) ui.text(q.isEmpty() ? "Nobody lives here yet." : "Nobody matches \"" + q + "\".", x + 8, ly + 6, FAINT);
        int off = ui.beginScroll("people", x + 2, ly, w - 4, lh, rows.size() * ROW_H);
        int ry = ly - off;
        for (CompoundTag c : rows) {
            if (ry + ROW_H >= ly && ry <= ly + lh) row(ui, x + 4, ry, w - 10, c, c.getInt("id") == mayor, choosing, act);
            ry += ROW_H;
        }
        ui.endScroll();
    }

    static void row(Imm ui, int x, int y, int w, CompoundTag c, boolean isMayor, boolean choosing, BiConsumer<String, CompoundTag> act) {
        int id = c.getInt("id");
        boolean sel = id == selected;
        ui.round(x, y + 1, w, ROW_H - 2, 4, sel ? alpha(BLUE, 0x30) : ui.over(x, y + 1, w, ROW_H - 2) ? CARD_HI : 0x00000000);
        int m = c.getInt("m"), right = choosing ? 50 : 0;
        ui.text(ui.clip(c.getString("n"), w - 40 - right), x + 5, y + 3, isMayor ? GOLD : TEXT);
        if (isMayor) ui.pill("Mayor", x + 10 + ui.width(ui.clip(c.getString("n"), w - 40 - right)), y + 2, alpha(GOLD, 0x50), GOLD);
        String line = c.getString("j") + " · " + c.getInt("a") + (traits(c).isEmpty() ? "" : " · " + traits(c));
        ui.text(ui.clip(line, w - 12 - right), x + 5, y + 13, MUTED);
        ui.round(x + w - 26 - right, y + 5, 6, 6, 3, moodColor(m));
        ui.right(String.valueOf(m), x + w - 4 - right, y + 4, moodColor(m));
        if (choosing) {
            CompoundTag d = new CompoundTag();
            d.putInt("citizen", id);
            ui.button("pplPick" + id, x + w - 46, y + 4, 44, 14, "Choose", Style.PRIMARY, true, () -> { act.accept("appointMayor", d); mode = ""; selected = id; });
        }
        ui.area(x, y + 1, w - right, ROW_H - 2, () -> selected = id);
    }

    static void soldiers(Imm ui, int x, int y, int w, int h, CompoundTag t, BiConsumer<String, CompoundTag> act) {
        ListTag l = t.getList("soldiers", Tag.TAG_COMPOUND);
        ui.card(x, y, w, h);
        ui.text("Choose a captain (best first):", x + 8, y + 5, GOLD);
        int ly = y + 16, lh = h - 19;
        int off = ui.beginScroll("pplSoldiers", x + 2, ly, w - 4, lh, l.size() * ROW_H);
        int ry = ly - off;
        for (Tag e : l) {
            CompoundTag so = (CompoundTag) e;
            boolean cur = t.hasUUID("captain") && so.getUUID("e").equals(t.getUUID("captain"));
            ui.round(x + 4, ry + 1, w - 10, ROW_H - 2, 4, cur ? alpha(GOLD, 0x22) : CARD_HI);
            ui.text(ui.clip(so.getString("n"), w - 70), x + 9, ry + 3, cur ? GOLD : TEXT);
            ui.text(ui.clip(so.getString("t") + " · " + so.getInt("k") + " kills · merit " + so.getInt("m"), w - 70), x + 9, ry + 13, MUTED);
            CompoundTag d = new CompoundTag();
            d.putUUID("soldier", so.getUUID("e"));
            ui.button("pplCap" + so.getUUID("e"), x + w - 56, ry + 4, 44, 14, "Choose", Style.PRIMARY, !cur, () -> { act.accept("appointCaptain", d); mode = ""; });
            ry += ROW_H;
        }
        ui.endScroll();
    }

    static void card(Imm ui, int x, int y, int w, int h, CompoundTag c, Map<Integer, CompoundTag> byId, int mayor) {
        ui.card(x, y, w, h);
        int off = ui.beginScroll("pplCard", x + 2, y + 2, w - 4, h - 4, cardHeight(ui, c, w));
        int cy = y + 6 - off, tx = x + 8, tw = w - 16;
        ui.text(ui.clip(c.getString("n"), tw), tx, cy, c.getInt("id") == mayor ? GOLD : TEXT);
        cy += 11;
        String who = c.getString("st") + ", " + c.getInt("a") + " · " + (c.getBoolean("w") ? "woman" : "man") + (c.getInt("id") == mayor ? " · Mayor" : "");
        ui.text(ui.clip(who, tw), tx, cy, MUTED);
        cy += 10;
        ui.text(ui.clip(c.getString("j") + " · lives in " + c.getString("h"), tw), tx, cy, MUTED);
        cy += 10;
        ui.text(ui.clip("Respect " + c.getInt("r") + (c.getBoolean("shown") ? " · out and about in town" : ""), tw), tx, cy, FAINT);
        cy += 14;
        int m = c.getInt("m");
        ui.text("Mood", tx, cy, TEXT);
        ui.right(m + " · " + moodLabel(m), tx + tw, cy, moodColor(m));
        cy += 10;
        ui.bar(tx, cy, tw, 5, m / 100f, moodColor(m), moodColor(m));
        cy += 8;
        for (String why : c.getString("why").split("\n")) {
            if (why.isEmpty()) continue;
            ui.text(ui.clip(why, tw - 4), tx + 4, cy, why.startsWith("-") ? RED : why.startsWith("+") ? GREEN : FAINT);
            cy += 9;
        }
        cy += 5;
        ui.text("Traits", tx, cy, TEXT);
        cy += 11;
        for (Tag tr : c.getList("tr", Tag.TAG_STRING)) {
            String id = tr.getAsString();
            ui.text(cap(TRAIT_LABEL.getOrDefault(id, id)), tx + 4, cy, GOLD_DK);
            cy += 10;
            cy += ui.wrap(TRAIT_EFFECT.getOrDefault(id, ""), tx + 8, cy, tw - 12, FAINT);
        }
        cy += 5;
        ui.text("Family", tx, cy, TEXT);
        cy += 11;
        int before = cy;
        cy = relative(ui, tx + 4, cy, tw - 4, "Spouse", c.getInt("sp"), byId, cy);
        cy = relative(ui, tx + 4, cy, tw - 4, "Mother", c.getInt("mo"), byId, cy);
        cy = relative(ui, tx + 4, cy, tw - 4, "Father", c.getInt("fa"), byId, cy);
        for (int k : c.getIntArray("k")) cy = relative(ui, tx + 4, cy, tw - 4, "Child", k, byId, cy);
        if (cy == before) ui.text("No family in town", tx + 4, cy, FAINT);
        ui.endScroll();
    }

    static int cardHeight(Imm ui, CompoundTag c, int w) {
        int n = 70 + c.getString("why").split("\n").length * 9 + 16 + 30;
        for (Tag tr : c.getList("tr", Tag.TAG_STRING)) n += 10 + ui.wrapHeight(TRAIT_EFFECT.getOrDefault(tr.getAsString(), ""), w - 28);
        return n + 10 * (3 + c.getIntArray("k").length);
    }

    /** A clickable family line; returns the next y. Kin who died or left are not listed. */
    static int relative(Imm ui, int x, int y, int w, String role, int id, Map<Integer, CompoundTag> byId, int cy) {
        CompoundTag r = byId.get(id);
        if (id <= 0 || r == null) return cy;
        boolean hover = ui.over(x, y, w, 10);
        ui.text(role, x, y, FAINT);
        ui.text(ui.clip(r.getString("n") + " (" + r.getInt("a") + ")", w - 46), x + 44, y, hover ? BLUE : TEXT);
        ui.area(x, y, w, 10, () -> selected = id);
        return cy + 10;
    }

    static void memorial(Imm ui, int x, int y, int w, int h, ListTag mem, int recorded, int population) {
        ui.card(x, y, w, h);
        int cy = y + 6;
        ui.text("Pick someone to see their life.", x + 8, cy, MUTED);
        cy += 12;
        if (population > recorded) { ui.text(ui.clip("Plus " + (population - recorded) + " more not on the rolls", w - 16), x + 8, cy, FAINT); cy += 12; }
        cy += 4;
        ui.text("Remembered", x + 8, cy, GOLD);
        cy += 12;
        if (mem.isEmpty()) ui.text("No one has died yet.", x + 10, cy, FAINT);
        for (Tag e : mem) {
            CompoundTag m = (CompoundTag) e;
            if (cy + 20 > y + h) break;
            ui.text(ui.clip(m.getString("n") + ", " + m.getInt("a") + (m.getBoolean("mayor") ? " · mayor" : ""), w - 18), x + 10, cy, m.getBoolean("mayor") ? GOLD_DK : TEXT);
            ui.text(ui.clip(m.getString("j") + ", died " + m.getString("c") + " · day " + m.getLong("d"), w - 18), x + 10, cy + 9, FAINT);
            cy += 21;
        }
    }

    static String traits(CompoundTag c) {
        StringBuilder b = new StringBuilder();
        for (Tag tr : c.getList("tr", Tag.TAG_STRING)) b.append(b.length() == 0 ? "" : ", ").append(TRAIT_LABEL.getOrDefault(tr.getAsString(), tr.getAsString()));
        return b.toString();
    }

    static String moodLabel(int m) { return m >= 75 ? "Joyful" : m >= 55 ? "Content" : m >= 40 ? "Uneasy" : m >= 25 ? "Unhappy" : "Miserable"; }

    static int moodColor(int m) { return m >= 55 ? GREEN : m >= 40 ? GOLD : m >= 25 ? ORANGE : RED; }

    static String cap(String s) { return s.isEmpty() ? s : Character.toUpperCase(s.charAt(0)) + s.substring(1); }

    private PeoplePanel() {}
}
