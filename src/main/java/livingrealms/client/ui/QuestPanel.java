package livingrealms.client.ui;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;

import java.util.ArrayList;
import java.util.List;
import java.util.function.BiConsumer;

import static livingrealms.client.ui.Imm.*;

/** Quests page: the current chapter of the quest line with each step's progress and reward, and this town's bounty board. */
final class QuestPanel {
    static final int STEP_H = 36, BOUNTY_H = 40;

    static void draw(Imm ui, int x, int y, int w, int h, CompoundTag q, BiConsumer<String, CompoundTag> act) {
        int chapter = q.getInt("chapter"), chapters = q.getInt("chapters");
        boolean finished = chapters > 0 && chapter >= chapters;
        ListTag steps = q.getList("steps", Tag.TAG_COMPOUND), titles = q.getList("titles", Tag.TAG_COMPOUND);
        ListTag board = q.getList("board", Tag.TAG_COMPOUND), elsewhere = q.getList("elsewhere", Tag.TAG_COMPOUND);
        ui.big("Quests", x, y, TEXT, 1.4f);
        String sub = chapters == 0 ? "No quest line loaded" : finished ? "Every chapter complete"
                : "Chapter " + (chapter + 1) + " of " + chapters + " · " + q.getString("title");
        ui.text(ui.clip(sub + " · " + q.getInt("stepsDone") + "/" + q.getInt("stepsAll") + " steps", w - 10 - chapters * 14), x, y + 15, MUTED);
        // a pip per chapter: done, current, ahead
        for (int i = 0; i < chapters; i++) {
            int px = x + w - 8 - (chapters - i) * 14, col = i < chapter ? GREEN : i == chapter ? GOLD : LINE;
            ui.round(px, y + 4, 10, 10, 5, col);
            if (ui.over(px, y + 4, 10, 10) && i < titles.size())
                ui.tip("Chapter " + (i + 1) + ": " + titles.getCompound(i).getString("title"), i < chapter ? "Complete" : i == chapter ? "In progress" : "Ahead");
        }

        String intro = q.getString("intro");
        int introH = intro.isEmpty() ? 0 : ui.wrapHeight(intro, w - 24) + 4;
        int questH = finished ? 40 : 22 + introH + Math.max(1, steps.size()) * STEP_H + 4;
        int boardH = 22 + Math.max(1, board.size()) * BOUNTY_H + 4;
        int elseH = elsewhere.isEmpty() ? 0 : 22 + elsewhere.size() * BOUNTY_H + 4;
        int off = ui.beginScroll("quests", x, y + 30, w, h - 30, questH + 6 + boardH + (elseH > 0 ? elseH + 6 : 0));
        int cy = y + 30 - off;

        // ---- the chapter
        ui.card(x, cy, w - 4, questH);
        if (finished) {
            ui.item(Items.GOLDEN_HELMET, x + 8, cy + 12);
            ui.text("The quest line is complete. Long live the crown.", x + 30, cy + 11, GOLD);
            ui.text(ui.clip("Bounties below still pay; pack makers can add chapters in livingrealms_quests.", w - 40), x + 30, cy + 23, FAINT);
        } else {
            Item icon = item(chapter < titles.size() ? titles.getCompound(chapter).getString("icon") : "", Items.WRITABLE_BOOK);
            ui.item(icon, x + 6, cy + 3);
            ui.text(ui.clip(q.getString("title"), w - 40), x + 26, cy + 7, GOLD);
            int sy = cy + 22;
            if (!intro.isEmpty()) { ui.wrap(intro, x + 10, sy, w - 24, MUTED); sy += introH; }
            for (Tag t : steps) {
                step(ui, x + 4, sy, w - 12, (CompoundTag) t);
                sy += STEP_H;
            }
        }
        cy += questH + 6;

        // ---- this town's bounty board
        ui.card(x, cy, w - 4, boardH);
        ui.text("Bounty board", x + 8, cy + 6, GOLD);
        ui.right("taken " + q.getInt("taken") + "/" + q.getInt("maxTaken") + " · completed " + q.getInt("completed"), x + w - 12, cy + 6, FAINT);
        int by = cy + 20;
        if (!q.getBoolean("offers")) ui.text(ui.clip("Village Expansion villages post no bounties.", w - 24), x + 10, by + 12, FAINT);
        else if (board.isEmpty()) ui.text(ui.clip("No bounties posted. The next in about " + Math.max(1, q.getLong("nextPostIn")) + " min.", w - 24), x + 10, by + 12, FAINT);
        for (Tag t : board) { bounty(ui, x + 4, by, w - 12, (CompoundTag) t, act); by += BOUNTY_H; }
        cy += boardH + 6;

        // ---- bounties taken in other towns
        if (elseH > 0) {
            ui.card(x, cy, w - 4, elseH);
            ui.text("Your bounties in other towns", x + 8, cy + 6, GOLD);
            int ey = cy + 20;
            for (Tag t : elsewhere) { bounty(ui, x + 4, ey, w - 12, (CompoundTag) t, act); ey += BOUNTY_H; }
        }
        ui.endScroll();
    }

    static void step(Imm ui, int x, int y, int w, CompoundTag s) {
        boolean done = s.getBoolean("done");
        int have = s.getInt("have"), need = Math.max(1, s.getInt("need"));
        ui.round(x, y, w, STEP_H - 3, 4, done ? alpha(GREEN, 0x22) : CARD_HI);
        // tick box
        ui.round(x + 6, y + 6, 12, 12, 3, done ? GREEN : LINE);
        if (done) ui.center("✔", x + 12, y + 8, 0xFF10301A);
        else ui.round(x + 7, y + 7, 10, 10, 3, CARD);
        int barW = Math.min(90, w / 4), room = w - 30 - barW - 14;
        ui.text(ui.clip(s.getString("title"), room), x + 24, y + 4, done ? GREEN : TEXT);
        ui.text(ui.clip(s.getString("objective"), room), x + 24, y + 14, MUTED);
        String reward = s.getString("reward");
        if (!reward.isEmpty()) ui.text(ui.clip("Reward: " + reward, w - 30), x + 24, y + 24, done ? FAINT : GOLD_DK);
        int bx = x + w - barW - 8;
        float frac = Math.min(1f, have / (float) need);
        ui.bar(bx, y + 7, barW, 6, frac, done ? GREEN : BLUE, done ? GREEN : BLUE);
        ui.right(done ? "done" : have + "/" + need, x + w - 8, y + 16, done ? GREEN : TEXT);
        if (ui.over(x, y, w, STEP_H - 3)) {
            List<String> tip = new ArrayList<>();
            tip.add(s.getString("title"));
            if (!s.getString("flavour").isEmpty()) tip.add("\"" + s.getString("flavour") + "\"");
            tip.add(s.getString("objective") + " (" + have + "/" + need + ")");
            if (!reward.isEmpty()) tip.add("Reward: " + reward);
            ui.tip(tip);
        }
    }

    static void bounty(Imm ui, int x, int y, int w, CompoundTag b, BiConsumer<String, CompoundTag> act) {
        boolean mine = b.getBoolean("mine"), taken = !b.getString("takerName").isEmpty();
        String kind = b.getString("kind");
        ui.round(x, y, w, BOUNTY_H - 3, 4, mine ? alpha(BLUE, 0x22) : CARD_HI);
        ui.item(item(b.getString("item"), Items.PAPER), x + 5, y + 5);
        CompoundTag data = new CompoundTag();
        data.putUUID("bounty", b.getUUID("id"));
        // buttons on the right
        int bw = 58, bx = x + w - bw - 6;
        if (!taken) ui.button("qa" + b.getUUID("id"), bx, y + 4, bw, 14, "Accept", Style.PRIMARY, true, () -> act.accept("questAccept", data));
        else if (mine) {
            if (kind.equals("DELIVER")) ui.button("qd" + b.getUUID("id"), bx, y + 4, bw, 14, "Deliver", Style.SUCCESS, b.getInt("carried") > 0, () -> act.accept("questDeliver", data));
            ui.button("qx" + b.getUUID("id"), bx, y + 20, bw, 14, "Abandon", Style.GHOST, true, () -> act.accept("questAbandon", data));
        } else ui.right(ui.clip("Taken by " + b.getString("takerName"), bw + 20), x + w - 8, y + 8, FAINT);
        int room = bx - (x + 26) - 8;
        String town = b.getString("town");
        ui.text(ui.clip(b.getString("title") + (town.isEmpty() ? "" : " · " + town), room), x + 26, y + 4, TEXT);
        long left = b.getLong("left"), day = Math.max(1, b.getLong("day"));
        String time = left >= day ? (left / day) + "d " + (left % day * 24 / day) + "h left" : (left * 24 / day) + "h left";
        String status = b.getInt("reward") + " emeralds · " + time;
        if (kind.equals("DELIVER") && mine) status += " · carrying " + b.getInt("carried");
        if ((kind.equals("PATROL") || kind.equals("ESCORT")) && mine && !b.getString("next").isEmpty()) status = b.getString("next") + " · " + status;
        ui.text(ui.clip(status, room), x + 26, y + 14, GOLD_DK);
        int have = b.getInt("have"), need = Math.max(1, b.getInt("need"));
        int barW = Math.max(30, Math.min(120, room - 40));
        ui.bar(x + 26, y + 27, barW, 5, have / (float) need, BLUE, BLUE);
        ui.text(kind.equals("ESCORT") ? have + "% of the way" : have + "/" + need, x + 30 + barW, y + 25, MUTED);
        if (ui.over(x, y, bx - x - 4, BOUNTY_H - 3)) {
            List<String> tip = new ArrayList<>();
            tip.add(b.getString("title"));
            tip.add(b.getString("detail"));
            if (!b.getString("next").isEmpty()) tip.add(b.getString("next"));
            tip.add(b.getInt("reward") + " emeralds on completion · " + time);
            ui.tip(tip);
        }
    }

    static Item item(String id, Item fallback) {
        ResourceLocation rl = ResourceLocation.tryParse(id);
        return rl == null ? fallback : BuiltInRegistries.ITEM.getOptional(rl).orElse(fallback);
    }

    private QuestPanel() {}
}
