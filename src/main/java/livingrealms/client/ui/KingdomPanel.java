package livingrealms.client.ui;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.world.item.Items;

import java.util.HashMap;
import java.util.Map;
import java.util.function.BiConsumer;

import static livingrealms.client.ui.Imm.*;

/** Kingdom page: loyalty of this city and the provinces, the war ledger with peace terms, tribute and truces. */
final class KingdomPanel {
    static final String[] TERMS = {"White peace", "Tribute", "Cede city", "Pay tribute"};
    static final String[] TERM_IDS = {"WHITE", "TRIBUTE", "CITY", "PAY"};
    static final String[] DAYS = {"1 day", "3 days", "7 days"};
    static final int[] DAY_VALUES = {1, 3, 7};
    /** Chosen term and truce per enemy, kept while the screen is open. */
    private static final Map<String, int[]> CHOICE = new HashMap<>();

    static void draw(Imm ui, int x, int y, int w, int h, CompoundTag sc, BiConsumer<String, CompoundTag> act) {
        ListTag wars = sc.getList("wars", Tag.TAG_COMPOUND), provinces = sc.getList("provinces", Tag.TAG_COMPOUND);
        ListTag tribute = sc.getList("tribute", Tag.TAG_COMPOUND), truces = sc.getList("truces", Tag.TAG_COMPOUND);
        CompoundTag loyalty = sc.getCompound("loyalty");
        boolean ruler = sc.getBoolean("ruler");
        ui.big("Kingdom", x, y, TEXT, 1.4f);
        int n = sc.getInt("cities");
        ui.text(ui.clip(sc.getString("kingdom") + " · " + n + (n == 1 ? " city" : " cities") + " · "
                + (wars.isEmpty() ? "at peace" : wars.size() + (wars.size() == 1 ? " war" : " wars")), w - 4), x, y + 15, MUTED);

        boolean conquered = loyalty.getBoolean("conquered");
        ListTag why = loyalty.getList("why", Tag.TAG_STRING);
        int loyH = conquered ? 36 + (loyalty.getBoolean("danger") ? 12 : 0) + (why.size() + 1) / 2 * 11 + 4 : 30;
        int provH = provinces.isEmpty() ? 0 : 22 + provinces.size() * 13 + 6;
        int warsH = wars.isEmpty() ? 34 + 6 : 0;
        for (Tag t : wars) warsH += warHeight((CompoundTag) t) + 6;
        int ledgerH = 22 + Math.max(1, tribute.size() + truces.size()) * 13 + 4;
        int total = loyH + 6 + (provH > 0 ? provH + 6 : 0) + warsH + ledgerH;
        int off = ui.beginScroll("kingdom", x, y + 30, w, h - 30, total);
        int cy = y + 30 - off;

        // ---- this city's loyalty
        ui.card(x, cy, w - 4, loyH);
        if (!conquered) {
            ui.item(Items.WHITE_BANNER, x + 6, cy + 7);
            ui.text(ui.clip("This city is loyal: it was founded under this crown or has long been part of it.", w - 40), x + 26, cy + 11, MUTED);
        } else {
            int value = loyalty.getInt("value");
            int col = value >= 60 ? GREEN : value >= 40 ? GOLD : value >= 20 ? ORANGE : RED;
            ui.text("Loyalty", x + 8, cy + 6, GOLD);
            ui.text(ui.clip("conquered from " + loyalty.getString("former"), w - 260), x + 12 + ui.width("Loyalty"), cy + 6, FAINT);
            double per = loyalty.getDouble("perDay");
            String trend = (per >= 0 ? "+" : "") + num(per) + " a day";
            ui.right(value + " / 100 · " + trend, x + w - 12, cy + 6, per >= 0 ? GREEN : RED);
            ui.bar(x + 8, cy + 19, w - 104, 7, value / 100f, col, col);
            // markers: unrest below 40, rebellion below 10, loyal at 90
            for (int mark : new int[]{10, 40, 90}) ui.rect(x + 8 + (w - 104) * mark / 100, cy + 17, 1, 11, alpha(TEXT, 0x80));
            if (ui.over(x + 8, cy + 15, w - 104, 14)) ui.tip("Loyalty " + value + " / 100",
                    "Below 40: unrest lowers the city's mood (now -" + loyalty.getInt("unrest") + ").",
                    "Below 10 for half a day: the city rebels and leaves your kingdom.",
                    "At 90 it is loyal for good. Your capital never rebels.");
            boolean ready = loyalty.getInt("pacifyIn") == 0;
            CompoundTag none = new CompoundTag();
            ui.button("pacify", x + w - 92, cy + 15, 80, 15, "Largesse", Style.PRIMARY, ruler && ready, () -> act.accept("scPacify", none));
            if (ui.over(x + w - 92, cy + 15, 80, 15)) ui.tip("Largesse", loyalty.getInt("pacifyCost") + " emeralds from this city's stockpile: +12 loyalty.",
                    ready ? "Once a day." : "Again in " + loyalty.getInt("pacifyIn") + " min.");
            if (loyalty.getBoolean("danger")) ui.pill("REBELLION NEAR", x + 8, cy + 31, alpha(RED, 0x60), RED);
            int ry = cy + 33 + (loyalty.getBoolean("danger") ? 12 : 0), col2 = (w - 24) / 2;
            for (int i = 0; i < why.size(); i++) {
                String r = why.getString(i);
                ui.text(ui.clip(r, col2 - 6), x + 10 + (i % 2) * col2, ry + i / 2 * 11, r.startsWith("-") ? RED : GREEN);
            }
        }
        cy += loyH + 6;

        // ---- provinces
        if (provH > 0) {
            ui.card(x, cy, w - 4, provH);
            ui.text("Conquered cities", x + 8, cy + 6, GOLD);
            ui.right("loyal at 90 · rebels under 10", x + w - 12, cy + 6, FAINT);
            int py = cy + 21;
            for (Tag t : provinces) {
                CompoundTag p = (CompoundTag) t;
                int v = p.getInt("value");
                int c = v >= 60 ? GREEN : v >= 40 ? GOLD : v >= 20 ? ORANGE : RED;
                ui.text(ui.clip(p.getString("name") + (p.getBoolean("here") ? " (this city)" : ""), w / 2 - 20), x + 10, py, p.getBoolean("here") ? TEXT : MUTED);
                ui.bar(x + w / 2, py + 1, w / 2 - 50, 6, v / 100f, c, c);
                ui.right(String.valueOf(v), x + w - 12, py, c);
                py += 13;
            }
            cy += provH + 6;
        }

        // ---- wars
        if (wars.isEmpty()) {
            ui.card(x, cy, w - 4, 34);
            ui.item(Items.WHITE_BANNER, x + 6, cy + 9);
            ui.text(ui.clip("At peace. Raiding another kingdom's city starts a war,", w - 40), x + 26, cy + 7, MUTED);
            ui.text(ui.clip("and so does a Village Expansion faction declaring one.", w - 40), x + 26, cy + 18, FAINT);
            cy += 40;
        }
        for (Tag t : wars) {
            CompoundTag war = (CompoundTag) t;
            int wh = warHeight(war);
            drawWar(ui, x, cy, w - 4, wh, war, ruler, act);
            cy += wh + 6;
        }

        // ---- tribute and truces
        ui.card(x, cy, w - 4, ledgerH);
        ui.text("Tribute & truces", x + 8, cy + 6, GOLD);
        int ly = cy + 21;
        if (tribute.isEmpty() && truces.isEmpty()) ui.text("None. Peace terms set tribute and truces.", x + 10, ly, FAINT);
        for (Tag t : tribute) {
            CompoundTag r = (CompoundTag) t;
            boolean paying = r.getBoolean("paying");
            ui.item(Items.EMERALD, x + 8, ly - 4);
            String s = paying ? "You pay " + r.getString("other") : r.getString("other") + " pays you";
            ui.text(ui.clip(s, w - 200), x + 28, ly, paying ? ORANGE : GREEN);
            ui.right(r.getInt("perDay") + " a day · " + r.getInt("daysLeft") + " days left · next in " + r.getInt("dueIn") + " min", x + w - 12, ly, FAINT);
            if (ui.over(x + 4, ly - 3, w - 12, 13)) ui.tip("Tribute", "Paid each day from the payer's stockpiles (NPC towns from their treasury).",
                    "Raids between payer and payee are barred while it lasts.", "A missed payment ends it and breaks the truce." + (r.getInt("missed") > 0 ? " Short payments so far: " + r.getInt("missed") : ""));
            ly += 13;
        }
        for (Tag t : truces) {
            CompoundTag r = (CompoundTag) t;
            ui.text(ui.clip("Truce with " + r.getString("other"), w - 120), x + 28, ly, BLUE);
            ui.right(r.getInt("minutes") + " min left", x + w - 12, ly, FAINT);
            ly += 13;
        }
        ui.endScroll();
    }

    static int warHeight(CompoundTag war) { return 92 + (war.contains("offer") ? 18 : 0); }

    static void drawWar(Imm ui, int x, int y, int w, int h, CompoundTag war, boolean ruler, BiConsumer<String, CompoundTag> act) {
        ui.card(x, y, w, h);
        String id = war.getUUID("enemy").toString();
        int score = war.getInt("score");
        ui.item(Items.RED_BANNER, x + 6, y + 5);
        ui.text(ui.clip(war.getString("name"), w - 200), x + 26, y + 4, TEXT);
        String pill = war.getBoolean("fighting") ? "Raid under way" : "At war · " + war.getInt("days") + (war.getInt("days") == 1 ? " day" : " days");
        ui.pill(pill, x + 26, y + 15, alpha(RED, 0x50), RED);
        ui.right("War score " + (score > 0 ? "+" : "") + score, x + w - 8, y + 6, score > 0 ? GREEN : score < 0 ? RED : MUTED);
        // diverging score bar: left is theirs, right is yours
        int bx = x + 8, bw = w - 16, by = y + 32, mid = bx + bw / 2;
        ui.round(bx, by, bw, 6, 3, 0xFF0D1014);
        int fw = (int) (bw / 2f * Math.min(100, Math.abs(score)) / 100f);
        if (fw > 0) ui.round(score > 0 ? mid : mid - fw, by, fw, 6, 3, score > 0 ? GREEN : RED);
        ui.rect(mid, by - 2, 1, 10, alpha(TEXT, 0x90));
        if (ui.over(bx, by - 2, bw, 10)) ui.tip("War score " + (score > 0 ? "+" : "") + score,
                "+15 per city taken, +10 per raid held off, +5 per raid won, +1 per enemy soldier killed on a raid.",
                "The better your score, the harsher the terms they accept.");
        // exhaustion
        int half = (w - 24) / 2, ey = y + 44;
        ui.text("Your exhaustion", x + 8, ey, FAINT);
        ui.text("Theirs", x + 16 + half, ey, FAINT);
        int mine = war.getInt("mine"), theirs = war.getInt("theirs");
        ui.bar(x + 8, ey + 10, half - 30, 5, mine / 100f, ORANGE, ORANGE);
        ui.right(String.valueOf(mine), x + 8 + half - 4, ey + 9, ORANGE);
        ui.bar(x + 16 + half, ey + 10, half - 30, 5, theirs / 100f, ORANGE, ORANGE);
        ui.right(String.valueOf(theirs), x + 16 + half * 2 - 4, ey + 9, ORANGE);
        if (ui.over(x + 4, ey - 2, w - 8, 20)) ui.tip("War exhaustion", "+10 a day at war, +3 per soldier lost, +12 per city lost, +5 per failed raid.",
                "At 100 a side must make peace; the winner's score sets the terms.");
        // terms
        int[] c = CHOICE.computeIfAbsent(id, k -> new int[]{0, 0});
        int ty = y + 66;
        int segW = w - 16 - 150;
        ui.segments("terms" + id, x + 8, ty, segW, 15, TERMS, c[0], i -> c[0] = i);
        ui.segments("truce" + id, x + 12 + segW, ty, 138 - 4, 15, DAYS, c[1], i -> c[1] = i);
        int[] accept = war.getIntArray("accept");
        boolean player = war.getBoolean("player");
        boolean yes = accept.length > c[0] * 3 + c[1] && accept[c[0] * 3 + c[1]] == 1;
        boolean cedeOk = c[0] != 2 || !war.getString("cede").isEmpty();
        String verdict = player ? "Their ruler must accept" : !cedeOk ? "Only their capital is left" : yes ? "They would accept" : "They would refuse";
        String detail = switch (c[0]) {
            case 1 -> "They pay " + war.getInt("tributeIn") + " emeralds a day for 5 days.";
            case 2 -> war.getString("cede").isEmpty() ? "They hold no city but their capital." : "They cede " + war.getString("cede") + ", their city nearest your capital.";
            case 3 -> "You pay " + war.getInt("tributeOut") + " emeralds a day for 5 days.";
            default -> "Both sides keep what they hold.";
        };
        if (ui.over(x + 8, ty, segW, 15)) ui.tip(TERMS[c[0]], detail, "A truce of " + DAYS[c[1]] + " follows; no raids either way until it ends.");
        boolean fighting = war.getBoolean("fighting") || war.getBoolean("locked");
        boolean pending = war.contains("offer") && war.getBoolean("offerMine");
        ui.text(ui.clip(verdict + " · " + detail, w - 110), x + 8, y + 84, player ? MUTED : yes && cedeOk ? GREEN : RED);
        CompoundTag prop = new CompoundTag();
        prop.putUUID("enemy", war.getUUID("enemy"));
        prop.putString("term", TERM_IDS[c[0]]);
        prop.putInt("days", DAY_VALUES[c[1]]);
        ui.button("propose" + id, x + w - 92, y + 80, 84, 15, player ? "Offer peace" : "Propose", Style.SUCCESS,
                ruler && !fighting && !pending && cedeOk, () -> act.accept("scPropose", prop));
        if (ui.over(x + w - 92, y + 80, 84, 15)) ui.tip(player ? "Offer peace" : "Propose peace", fighting ? war.getBoolean("locked") ? "Village Expansion allows no peace for a few minutes after an attack." : "Not while a raid is under way." : pending ? "Your offer awaits their answer." : detail,
                player ? "They answer on their Kingdom page within a day." : "An NPC kingdom accepts when your war score and their exhaustion are high enough.");
        if (war.contains("offer")) {
            int oy = y + 98;
            ui.round(x + 4, oy - 2, w - 8, 16, 3, CARD_HI);
            if (pending) {
                ui.text(ui.clip("Your offer: " + war.getString("offer"), w - 20), x + 10, oy + 2, BLUE);
            } else {
                ui.text(ui.clip("They offer: " + war.getString("offer"), w - 130), x + 10, oy + 2, GOLD);
                CompoundTag who = new CompoundTag();
                who.putUUID("enemy", war.getUUID("enemy"));
                ui.button("accept" + id, x + w - 118, oy - 1, 54, 14, "Accept", Style.SUCCESS, ruler, () -> act.accept("scAccept", who));
                ui.button("reject" + id, x + w - 60, oy - 1, 54, 14, "Reject", Style.DANGER, ruler, () -> act.accept("scReject", who));
            }
        }
    }

    private KingdomPanel() {}
}
