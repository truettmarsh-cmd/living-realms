package livingrealms.client.ui;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;

import java.util.Locale;
import java.util.function.BiConsumer;

import static livingrealms.client.ui.Imm.*;

/** Trade page: this town's goods and prices, its trade routes with their caravans, and recent caravans (docs/trade.md). */
final class TradePanel {
    static final int ROUTE_H = 34, GOOD_H = 18;

    static void draw(Imm ui, int x, int y, int w, int h, CompoundTag tr, BiConsumer<String, CompoundTag> act) {
        ListTag routes = tr.getList("routes", Tag.TAG_COMPOUND), goods = tr.getList("goods", Tag.TAG_COMPOUND), recent = tr.getList("caravans", Tag.TAG_COMPOUND);
        boolean mine = tr.getBoolean("mine");
        int prosperity = (int) Math.round(tr.getDouble("prosperity"));
        ui.big("Trade", x, y, TEXT, 1.4f);
        String family = tr.getString("family");
        ui.text(ui.clip(cap(family) + " goods · " + tr.getInt("sent") + " caravans sent, " + tr.getInt("received") + " received, "
                + tr.getInt("lost") + " lost · " + tr.getInt("earned") + " emeralds earned", w - 140), x, y + 15, MUTED);
        String pill = "Prosperity " + prosperity;
        int pw = ui.pillWidth(pill);
        ui.pill(pill, x + w - 8 - pw, y + 2, alpha(prosperity > 0 ? GREEN : FAINT, 0x50), prosperity > 0 ? GREEN : MUTED);
        if (ui.over(x + w - 8 - pw, y + 2, pw, 12)) ui.tip("Prosperity " + prosperity + " / 100",
                "Each caravan that arrives raises it in both towns; it fades by a tenth a day.",
                "Now: +" + num(prosperity / 10.0) + "% building speed, +" + num(prosperity / 200.0) + " growth a day.");

        int cols = w >= 640 ? 2 : 1;
        int routesH = 22 + Math.max(1, routes.size()) * ROUTE_H + 4;
        int goodsH = 22 + Math.max(1, (goods.size() + cols - 1) / cols) * GOOD_H + 4;
        int recentH = 22 + Math.max(1, recent.size()) * 13 + 4;
        int off = ui.beginScroll("trade", x, y + 30, w, h - 30, routesH + 6 + goodsH + 6 + recentH);
        int cy = y + 30 - off;

        // ---- routes
        ui.card(x, cy, w - 4, routesH);
        ui.text("Trade routes", x + 8, cy + 6, GOLD);
        ui.right("towns at peace within reach trade", x + w - 12, cy + 6, FAINT);
        int ry = cy + 21;
        if (routes.isEmpty()) ui.text(ui.clip("No town at peace is close enough to trade with yet.", w - 20), x + 10, ry + 2, FAINT);
        for (Tag t : routes) {
            route(ui, x + 4, ry, w - 12, (CompoundTag) t, mine, act);
            ry += ROUTE_H;
        }
        cy += routesH + 6;

        // ---- goods
        ui.card(x, cy, w - 4, goodsH);
        ui.text("Goods", x + 8, cy + 6, GOLD);
        ui.right("stock · short of · price · made a day", x + w - 12, cy + 6, FAINT);
        int gy = cy + 21, colW = (w - 16) / cols;
        for (int i = 0; i < goods.size(); i++) {
            good(ui, x + 6 + (i % cols) * colW, gy + (i / cols) * GOOD_H, colW - 6, goods.getCompound(i), mine, act);
        }
        if (goods.isEmpty()) ui.text("Nothing in stock.", x + 10, gy + 2, FAINT);
        cy += goodsH + 6;

        // ---- recent caravans
        ui.card(x, cy, w - 4, recentH);
        ui.text("Caravans", x + 8, cy + 6, GOLD);
        int ly = cy + 21;
        if (recent.isEmpty()) ui.text(ui.clip("None yet. A caravan sets out when one town has a surplus of what the other is short of.", w - 20), x + 10, ly, FAINT);
        for (Tag t : recent) {
            CompoundTag c = (CompoundTag) t;
            String dir = c.getBoolean("out") ? "To " : "From ";
            ui.text(ui.clip(dir + c.getString("other") + ": " + c.getString("cargo"), w / 2 + 40), x + 10, ly, TEXT);
            String st = c.getString("status") + (c.getString("note").isEmpty() ? "" : " · " + c.getString("note"));
            ui.right(ui.clip(st, w / 2 - 70), x + w - 12, ly, statusColor(c.getString("state")));
            ly += 13;
        }
        ui.endScroll();
    }

    static void route(Imm ui, int x, int y, int w, CompoundTag r, boolean mine, BiConsumer<String, CompoundTag> act) {
        boolean active = r.getBoolean("active"), closedHere = r.getBoolean("closedHere");
        ui.round(x, y, w, ROUTE_H - 4, 4, CARD_HI);
        ui.item(active ? Items.LEAD : Items.BARRIER, x + 4, y + 7);
        ui.text(ui.clip(r.getString("name"), w / 2 - 30), x + 24, y + 4, TEXT);
        ui.text(ui.clip(r.getString("standing") + " · " + r.getInt("distance") + " blocks · " + r.getInt("trips") + " trips"
                + (r.getInt("lost") > 0 ? ", " + r.getInt("lost") + " lost" : ""), w / 2 - 30), x + 24, y + 16, FAINT);
        int sx = x + w / 2, sw = w / 2 - 40;
        if (r.contains("caravan")) {
            CompoundTag c = r.getCompound("caravan");
            String state = c.getString("state");
            String label = (c.getBoolean("out") ? "Outbound: " : "Inbound: ") + c.getString("status") + (c.getBoolean("walking") ? " (walking)" : "");
            ui.text(ui.clip(label, sw), sx, y + 4, statusColor(state));
            if (state.equals("LOADING") || state.equals("ROAD")) {
                ui.bar(sx, y + 16, sw, 6, (float) c.getDouble("progress"), statusColor(state), statusColor(state));
            } else ui.text(ui.clip(c.getString("note").isEmpty() ? c.getInt("ago") + " min ago" : c.getString("note"), sw), sx, y + 15, FAINT);
            if (ui.over(sx, y, sw, ROUTE_H - 4)) ui.tip(label, "Carrying " + c.getString("cargo") + " (worth about " + c.getInt("value") + " emeralds)",
                    c.getInt("guards") == 0 ? "No guards" : c.getInt("guards") + (c.getInt("guards") == 1 ? " guard" : " guards") + "; your soldiers walking with it make an ambush less likely",
                    c.getString("note"));
        } else {
            ui.text(ui.clip(active ? "Open · waiting for goods to trade" : r.getString("blocked"), sw), sx, y + 9, active ? MUTED : ORANGE);
        }
        if (!active && !r.getString("blocked").isEmpty() && !r.contains("caravan") && ui.over(sx, y, sw, ROUTE_H - 4))
            ui.tip(r.getString("blocked"), "Towns at war never trade. Attacking a caravan suspends trade for three days; a second attack means war.");
        CompoundTag d = new CompoundTag();
        d.putUUID("partner", r.getUUID("partner"));
        d.putBoolean("open", closedHere);
        if (mine) ui.toggle("route" + r.getUUID("partner"), x + w - 30, y + 9, !closedHere, () -> act.accept("tradeRoute", d));
        if (ui.over(x + w - 32, y + 5, 28, 20)) ui.tip(closedHere ? "Closed by you" : "Open", mine ? "Click to " + (closedHere ? "reopen" : "close") + " this route." : "Only the town's ruler can close a route.");
    }

    static void good(Imm ui, int x, int y, int w, CompoundTag g, boolean mine, BiConsumer<String, CompoundTag> act) {
        Item it = BuiltInRegistries.ITEM.get(new ResourceLocation(g.getString("id")));
        boolean ban = g.getBoolean("ban");
        int need = g.getInt("need"), stock = g.getInt("stock");
        double price = g.getDouble("price"), base = g.getDouble("base"), made = g.getDouble("made");
        ui.item(it, x, y);
        ui.text(ui.clip(it.getDescription().getString(), w - 184), x + 20, y + 4, ban ? FAINT : TEXT);
        int rx = x + w - 40;
        ui.right(made > 0 ? num(made) : "-", rx, y + 4, made > 0 ? BLUE : FAINT);
        int pc = price > base * 1.15 ? RED : price < base * 0.85 ? GREEN : MUTED;
        ui.right(String.format(Locale.ROOT, "%.2f", price), rx - 34, y + 4, pc);
        ui.right(need > 0 ? String.valueOf(need) : "-", rx - 76, y + 4, need > 0 ? ORANGE : FAINT);
        ui.right(String.valueOf(stock), rx - 110, y + 4, TEXT);
        if (ui.over(x, y, w - 36, GOOD_H - 2)) ui.tip(it.getDescription().getString(),
                stock + " in stock" + (need > 0 ? ", short of " + need : "") + (g.getInt("spare") > 0 ? ", " + g.getInt("spare") + " to spare" : ""),
                "Price " + String.format(Locale.ROOT, "%.2f", price) + " emeralds (base " + String.format(Locale.ROOT, "%.2f", base) + "): dearer when short, cheaper when plentiful",
                made > 0 ? "Made here: about " + num(made) + " a day" : "Not made here");
        CompoundTag d = new CompoundTag();
        d.putString("item", g.getString("id"));
        d.putBoolean("ban", !ban);
        if (mine) ui.button("ban" + g.getString("id"), x + w - 34, y + 1, 32, 13, ban ? "Ban" : "Free", ban ? Style.DANGER : Style.SECONDARY, true, () -> act.accept("tradeBan", d));
        if (ui.over(x + w - 34, y + 1, 32, 13)) ui.tip(ban ? "Export banned" : "Exports allowed", ban ? "Caravans never carry this out of town. Click to allow." : "Click to ban its export.");
    }

    static int statusColor(String state) {
        return switch (state) { case "LOADING" -> GOLD; case "ROAD" -> BLUE; case "ARRIVED" -> GREEN; default -> RED; };
    }

    static String cap(String s) { return s.isEmpty() ? s : Character.toUpperCase(s.charAt(0)) + s.substring(1); }

    private TradePanel() {}
}
