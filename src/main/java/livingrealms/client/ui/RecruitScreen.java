package livingrealms.client.ui;

import livingrealms.military.RecruitOrigin;
import livingrealms.military.SoldierSkills;
import livingrealms.military.UnitType;
import livingrealms.net.Net;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import org.lwjgl.glfw.GLFW;

import java.util.UUID;

import static livingrealms.client.ui.Imm.*;

/** Recruit Command: one recruit's orders, promotions, skills and inventory. Opened by shift + right-click. */
public class RecruitScreen extends Screen {
    public enum Page {
        OVERVIEW("Overview", Items.BELL), PROMOTIONS("Promotions", Items.GOLDEN_HELMET), SKILLS("Skills", Items.ENCHANTED_BOOK),
        WEAPONS("Weapons", Items.IRON_SWORD), LEGENDARY("Legendary", Items.NETHER_STAR), SPECIAL("Special", Items.BLAZE_POWDER);
        final String label;
        final Item icon;
        Page(String label, Item icon) { this.label = label; this.icon = icon; }
    }

    private static Page lastPage = Page.OVERVIEW;
    private final Imm ui = new Imm();
    private final SoldierPanels panels = new SoldierPanels(ui, new SoldierPanels.Host() {
        public boolean canCommand() { return true; }
        public void act(String action, CompoundTag data) { settlementAct(action, data); }
        public boolean hasResearch(String id) { return id.equals("command") && d.getBoolean("hasCommand"); }
        public int emeralds() { return d.getInt("emeralds"); }
        public boolean royalFooter() { return false; }
    });
    private CompoundTag d;
    private Page page;
    private EditBox nameBox;
    private int confirm = -99;
    private int px, py, pw, ph, cx, cy, cw, ch;
    private long openedAt;
    private int ticks;
    private float fit = 1;

    public RecruitScreen(CompoundTag data) {
        super(Component.literal("Recruit Command"));
        d = data;
        page = lastPage;
    }

    /** A "recruit" packet: opens the screen, or refreshes it if it's already showing. */
    public static void receive(CompoundTag data) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.screen instanceof RecruitScreen r) r.update(data);
        else if (data.getBoolean("open")) mc.setScreen(new RecruitScreen(data));
    }

    public void update(CompoundTag data) {
        boolean renamed = !data.getString("name").equals(d.getString("name"));
        d = data;
        if (nameBox != null && (renamed || !nameBox.isFocused())) nameBox.setValue(d.getString("name"));
    }

    public UUID entity() { return d.getUUID("e"); }

    public CompoundTag data() { return d; }

    public void showPage(Page p) { page = p; lastPage = p; panels.resetScroll(); ui.resetScroll("rcPage"); }

    CompoundTag soldier() { return d.contains("soldier", Tag.TAG_COMPOUND) ? d.getCompound("soldier") : null; }

    void op(String op) { op(op, new CompoundTag()); }

    void op(String op, CompoundTag extra) {
        extra.putUUID("e", entity());
        extra.putString("op", op);
        Net.toServer("recruit", extra);
    }

    /** Settlement actions go to the soldier's town, then the screen asks for fresh data. */
    void settlementAct(String action, CompoundTag data) {
        if (!d.hasUUID("settlement")) return;
        CompoundTag t = new CompoundTag();
        t.putUUID("id", d.getUUID("settlement"));
        t.putString("action", action);
        t.put("data", data);
        Net.toServer("action", t);
        op("refresh");
    }

    // ------------------------------------------------------------------ screen

    @Override
    protected void init() {
        fit = ui.scale = Imm.fit(CityScreen.MIN_W, CityScreen.MIN_H);
        width = Math.round(minecraft.getWindow().getGuiScaledWidth() / fit);
        height = Math.round(minecraft.getWindow().getGuiScaledHeight() / fit);
        pw = Math.min(width - 12, CityScreen.MAX_W);
        ph = Math.min(height - 12, CityScreen.MAX_H);
        px = (width - pw) / 2;
        py = (height - ph) / 2;
        cx = px + 112;
        cy = py + 10;
        cw = pw - 122;
        ch = ph - 20;
        nameBox = new EditBox(font, cx + 6, cy + 34, cw - 96, 12, Component.literal("Name"));
        nameBox.setBordered(false);
        nameBox.setMaxLength(32);
        nameBox.setValue(d.getString("name"));
        addRenderableWidget(nameBox);
        openedAt = System.currentTimeMillis();
    }

    @Override
    public boolean isPauseScreen() { return false; }

    @Override
    public void tick() {
        nameBox.tick();
        if (++ticks % 40 == 0) op("refresh");
    }

    @Override
    public void render(GuiGraphics g, int rawX, int rawY, float partial) {
        int mx = (int) (rawX / fit), my = (int) (rawY / fit);
        g.pose().pushPose();
        g.pose().scale(fit, fit, 1);
        renderBackground(g);
        ui.begin(g, mx, my, partial);
        float open = Math.min(1, (System.currentTimeMillis() - openedAt) / 160f);
        g.pose().pushPose();
        g.pose().translate(0, (1 - open) * 8, 0);
        ui.shadow(px, py, pw, ph);
        ui.round(px, py, pw, ph, 8, 0xFF2A313B);
        ui.round(px + 1, py + 1, pw - 2, ph - 2, 7, 0xFF181C22, 0xFF13161B);
        nameBox.visible = page == Page.OVERVIEW;
        sidebar();
        try {
            switch (page) {
                case OVERVIEW -> overview();
                case PROMOTIONS -> promotions();
                default -> details();
            }
        } catch (Exception e) {
            ui.text("Something went wrong drawing this page: " + e, cx, cy + 20, RED);
        }
        g.pose().popPose();
        super.render(g, mx, my, partial);
        ui.end();
        g.pose().popPose();
    }

    Item unitIcon() {
        try { return UnitType.valueOf(d.getString("unit")).icon; } catch (Exception e) { return Items.IRON_SWORD; }
    }

    void sidebar() {
        int x = px + 8, y = py + 10, w = 96;
        CompoundTag so = soldier();
        boolean royal = so != null && so.getBoolean("royalGuard");
        ui.round(x, y, 28, 28, 6, royal ? 0xFFF2CF6E : 0xFF3A4250, royal ? 0xFFB98E2E : 0xFF2A303B);
        ui.item(unitIcon(), x + 6, y + 6);
        ui.text(ui.clip(d.getString("name"), w - 34), x + 34, y + 3, royal ? GOLD : TEXT);
        ui.text(ui.clip(royal ? "Royal Guard" : d.getString("type").replaceFirst("^Recruit (.)", "$1"), w - 34), x + 34, y + 15, MUTED);
        y += 36;
        ui.hline(x, y, w, LINE);
        y += 6;
        int pages = so != null && SoldierPanels.hasSpecials(so) ? Page.values().length : Page.values().length - 1;
        boolean town = d.hasUUID("settlement");
        // short windows: the two footer buttons share a row and the tabs tighten
        boolean compact = py + ph - (town ? 58 : 36) - 6 - y < pages * 21;
        int fy = py + ph - (town && !compact ? 58 : 36), pitch = Math.max(16, Math.min(21, (fy - 6 - y) / pages));
        var hotbar = Minecraft.getInstance().options.keyHotbarSlots;
        for (Page p : Page.values()) {
            if (p == Page.SPECIAL && pages < Page.values().length) continue;
            final Page pp = p;
            if (ui.iconButton("rcnav" + p, x - 2, y, w + 4, pitch - 1, p.icon, p.label, page == p, () -> showPage(pp)) && p.ordinal() < hotbar.length)
                ui.tip(p.label, "Shortcut: " + hotbar[p.ordinal()].getTranslatedKeyMessage().getString() + "  ·  Tab / Shift+Tab cycles pages");
            y += pitch;
        }
        ui.hline(x, fy - 6, w, LINE);
        boolean near = d.getBoolean("near");
        int iw = town && compact ? w - 32 : w;
        ui.button("rcInventory", x, fy, iw, 18, "Inventory", Style.PRIMARY, near, () -> op("inventory"));
        if (ui.over(x, fy, iw, 18)) ui.tip(near ? "Open the recruit's own inventory and equipment" : "Walk within 8 blocks to open its inventory");
        if (town) {
            final UUID id = d.getUUID("settlement");
            ui.button("rcTown", compact ? x + iw + 4 : x, compact ? fy : fy + 22, compact ? w - iw - 4 : w, 18, compact ? "City" : "City Manager", Style.SECONDARY, true, () -> {
                CompoundTag t = new CompoundTag(); t.putUUID("id", id); t.putString("tab", "military"); Net.toServer("open", t);
            });
            if (compact && ui.over(x + iw + 4, fy, w - iw - 4, 18)) ui.tip("Open " + d.getString("town") + " in the City Manager");
        }
    }

    void header(String title, String sub) {
        ui.big(ui.clip(title, (int) ((cw - 4) / 1.4f)), cx, cy, TEXT, 1.4f);
        if (sub != null) ui.text(ui.clip(sub, cw - 4), cx, cy + 15, MUTED);
    }

    // ------------------------------------------------------------------ overview

    void overview() {
        CompoundTag so = soldier();
        String town = so != null ? d.getString("town") : "not in a town garrison";
        header(d.getString("name"), d.getString("type") + " · Recruits level " + d.getInt("lvl") + " · " + town);
        // rename
        int ry = cy + 30;
        ui.round(cx, ry, cw - 84, 18, 4, nameBox.isFocused() ? GOLD_DK : BORDER);
        ui.round(cx + 1, ry + 1, cw - 86, 16, 3, 0xFF0F1216);
        boolean changed = !nameBox.getValue().trim().isEmpty() && !nameBox.getValue().trim().equals(d.getString("name"));
        ui.button("rcRename", cx + cw - 80, ry, 78, 18, "Rename", Style.SECONDARY, changed, this::rename);
        int top = ry + 24, bottom = cy + ch;
        int off = ui.beginScroll("rcPage", cx, top, cw, bottom - top, 228);
        int y = top - off;
        int w2 = (cw - 6) / 2;
        // condition
        ui.card(cx, y, w2, 98);
        ui.text("Condition", cx + 8, y + 6, MUTED);
        float max = d.getFloat("max"), hp = max <= 0 ? 0 : d.getFloat("hp") / max;
        stat("Health", Imm.num(d.getFloat("hp")) + " / " + Imm.num(max), cx + 8, y + 20, w2 - 16, hp, hp > .5 ? GREEN : hp > .25 ? GOLD : RED);
        float morale = d.getFloat("morale");
        stat("Morale", (int) morale + "%", cx + 8, y + 42, w2 - 16, morale / 100f, morale >= 50 ? BLUE : ORANGE);
        int need = Math.max(1, d.getInt("xpNeeded"));
        stat("Recruits level " + d.getInt("lvl"), d.getInt("xp") + " / " + need + " XP", cx + 8, y + 64, w2 - 16, d.getInt("xp") / (float) need, GOLD);
        ui.text(d.getInt("kills") + " kills", cx + 8, y + 85, FAINT);
        // Living Realms
        int rx = cx + w2 + 6;
        ui.card(rx, y, w2, 98);
        ui.text("Living Realms", rx + 8, y + 6, MUTED);
        RecruitOrigin origin = RecruitOrigin.byId(d.getString("origin"));
        if (so != null) {
            int xp = so.getInt("skillXp");
            int rank = so.contains("rank") ? so.getInt("rank") : SoldierSkills.earnedPoints(xp);
            int into = so.contains("xpInto") ? so.getInt("xpInto") : xp % SoldierSkills.XP_PER_POINT;
            int next = so.contains("xpNeeded") ? Math.max(1, so.getInt("xpNeeded")) : SoldierSkills.XP_PER_POINT;
            String title = so.contains("rankTitle") ? so.getString("rankTitle") + " · rank " + rank : "Skill rank " + rank;
            stat(title, into + " / " + next + " XP", rx + 8, y + 20, w2 - 16, into / (float) next, PURPLE);
            ui.text(ui.clip("Town: " + d.getString("town"), w2 - 16), rx + 8, y + 44, TEXT);
            ui.text(ui.clip("Unit: " + unitLabel(so.getString("type")), w2 - 16), rx + 8, y + 56, TEXT);
        } else {
            ui.text("Skill rank: none yet", rx + 8, y + 20, FAINT);
            ui.wrap("Enlist in a town to earn skills and perks.", rx + 8, y + 32, w2 - 16, MUTED);
        }
        ui.text(ui.clip("Born " + origin.label, w2 - 16), rx + 8, y + 70, TEXT);
        ui.text(ui.clip(d.getString("gear"), w2 - 16), rx + 8, y + 84, FAINT);
        if (ui.over(rx, y + 68, w2, 12)) ui.tip(origin.label, origin.description);
        // orders
        y += 104;
        ui.card(cx, y, cw, 46);
        ui.text("Quick orders", cx + 8, y + 6, MUTED);
        int follow = d.getInt("follow"), sel = follow == 1 ? 0 : follow == 2 || follow == 3 ? 1 : follow == 0 ? 2 : -1;
        String[] orders = {"follow", "hold", "wander"};
        int segW = Math.min(240, cw - 16);
        ui.segments("rcOrders", cx + 8, y + 22, segW, 17, new String[]{"Follow me", "Hold here", "Wander"}, sel, i -> op(orders[i]));
        if (so != null && cw - segW - 24 > 60) ui.wrap("The town's stance may reissue orders.", cx + segW + 16, y + 20, cw - segW - 24, FAINT);
        // advancement
        y += 52;
        ui.card(cx, y, cw, 70);
        ui.text("Advancement", cx + 8, y + 6, MUTED);
        long unlocked = 0;
        for (Tag t : d.getList("promotions", Tag.TAG_COMPOUND)) if (((CompoundTag) t).getString("state").equals("ok")) unlocked++;
        int bw = (cw - 24) / 3;
        int by = y + 22;
        ui.button("rcGoPromote", cx + 8, by, bw, 18, unlocked > 0 ? "Promote (" + unlocked + ")" : "Promotions", unlocked > 0 ? Style.PRIMARY : Style.SECONDARY, true, () -> showPage(Page.PROMOTIONS));
        if (so == null) {
            boolean can = d.hasUUID("enlistId") && !d.contains("enlistWhy");
            String label = d.contains("enlistTown") ? "Enlist in " + d.getString("enlistTown") : "Enlist in a town";
            ui.button("rcEnlist", cx + bw + 12, by, bw * 2 + 4, 18, ui.clip(label, bw * 2 - 4), Style.SUCCESS, can, () -> op("enlist"));
            if (!can) ui.text(ui.clip(d.getString("enlistWhy"), cw - 16), cx + 8, by + 26, ORANGE);
            else ui.text(ui.clip("Joins the garrison: unlocks the skill tree, weapon practice and legendary perks.", cw - 16), cx + 8, by + 26, FAINT);
        } else {
            int earned = so.contains("rank") ? so.getInt("rank") : SoldierSkills.earnedPoints(so.getInt("skillXp"));
            int points = Math.max(0, earned - so.getList("skills", Tag.TAG_STRING).size());
            ui.button("rcGoSkills", cx + bw + 12, by, bw, 18, points > 0 ? "Skills (" + points + " pts)" : "Skill tree", points > 0 ? Style.PRIMARY : Style.SECONDARY, true, () -> showPage(Page.SKILLS));
            if (so.contains("drillCost")) {
                boolean ready = so.getBoolean("drillReady");
                int cd = so.getInt("drillCooldown");
                ui.button("rcDrill", cx + 2 * bw + 16, by, bw, 18, ready ? "Drill" : cd > 0 ? "Drill · " + (cd >= 60 ? cd / 60 + "m" : cd + "s") : "Drill", Style.SUCCESS, ready, () -> settlementAct("drillSoldier", panels.soldierData(so)));
                if (ui.over(cx + 2 * bw + 16, by, bw, 18)) ui.tip("Drill: train this recruit up a rank", "Costs " + so.getString("drillCost"));
                ui.text(ui.clip("Drill costs " + so.getString("drillCost") + ".", cw - 16), cx + 8, by + 26, FAINT);
            } else ui.text(ui.clip("Kills earn skill XP; Recruits levels come from combat too.", cw - 16), cx + 8, by + 26, FAINT);
        }
        ui.endScroll();
    }

    void stat(String label, String value, int x, int y, int w, float frac, int color) {
        ui.text(ui.clip(label, w - ui.width(value) - 6), x, y, TEXT);
        ui.right(value, x + w, y, MUTED);
        ui.bar(x, y + 11, w, 5, frac, color, Imm.lerp(color, 0xFF000000, 0.3f));
    }

    static String unitLabel(String unit) {
        try { return UnitType.valueOf(unit).label; } catch (Exception e) { return unit; }
    }

    void rename() {
        String n = nameBox.getValue().trim();
        if (n.isEmpty()) return;
        CompoundTag t = new CompoundTag();
        t.putString("name", n);
        op("rename", t);
        nameBox.setFocused(false);
    }

    // ------------------------------------------------------------------ promotions

    void promotions() {
        ListTag list = d.getList("promotions", Tag.TAG_COMPOUND);
        header("Promotions", "Royal Guard from Living Realms, and Villager Recruits' own professions");
        int top = cy + 30, w2 = (cw - 6) / 2, rows = (list.size() + 1) / 2;
        String foot = "A Recruits profession replaces the recruit with a new one of that kind; its town record, skills and origin follow it.";
        int off = ui.beginScroll("rcPromotions", cx, top, cw, cy + ch - top, rows * 82 + 4 + ui.wrapHeight(foot, cw - 8));
        int y = top - off;
        for (int i = 0; i < list.size(); i++) {
            CompoundTag p = list.getCompound(i);
            int x = cx + (i % 2) * (w2 + 6), yy = y + (i / 2) * 82;
            promotionCard(p, x, yy, w2, 76);
        }
        ui.wrap(foot, cx + 2, y + rows * 82 + 2, cw - 8, FAINT);
        ui.endScroll();
    }

    void promotionCard(CompoundTag p, int x, int y, int w, int h) {
        int id = p.getInt("id");
        String state = p.getString("state");
        boolean ok = state.equals("ok"), active = state.equals("active"), disabled = state.equals("disabled");
        boolean royal = id == -1;
        ui.card(x, y, w, h, active ? 0xFF303725 : ok ? CARD_HI : CARD, active ? GOLD : ok ? GREEN : BORDER);
        String pill = active ? "Active" : ok ? "Unlocked" : disabled ? "Unavailable" : "Locked";
        int pillFill = active ? GOLD_DK : ok ? 0xFF2E6A38 : disabled ? 0xFF2C323B : 0xFF6A2A2A;
        int pw2 = ui.pillWidth(pill);
        ui.pill(pill, x + w - pw2 - 6, y + 5, pillFill, active ? 0xFF1A1408 : TEXT);
        ui.text(ui.clip((royal ? "♛ " : "") + p.getString("label"), w - pw2 - 18), x + 7, y + 7, active ? GOLD : disabled ? MUTED : TEXT);
        ui.pushClip(x, y + 19, x + w, y + 39);
        ui.wrap(p.getString("note"), x + 7, y + 20, w - 14, MUTED);
        ui.popClip();
        String why = p.getString("why");
        boolean showWhy = !ok && !active && !why.isEmpty();
        int btnW = 66;
        ui.text(ui.clip(showWhy ? why : p.getString("req"), w - 14), x + 7, y + 43, showWhy ? ORANGE : FAINT);
        if (showWhy && !disabled) ui.text(ui.clip(p.getString("req"), w - btnW - 20), x + 7, y + h - 15, FAINT);
        if (ui.over(x, y, w, h)) ui.tip(p.getString("label"), p.getString("note"), "Requires: " + p.getString("req"), ok ? "Ready to promote" : active ? "Currently appointed" : why);
        if (disabled) return;
        int bx = x + w - btnW - 5, by = y + h - 20;
        if (royal) {
            CompoundTag so = soldier();
            ui.button("rcRoyal", bx, by, btnW, 16, active ? "Stand down" : "Appoint", active ? Style.SECONDARY : Style.PRIMARY, so != null && (ok || active), () -> {
                CompoundTag data = panels.soldierData(so); data.putBoolean("on", !active); settlementAct("royalGuard", data);
            });
        } else {
            boolean confirming = confirm == id;
            ui.button("rcPromote" + id, bx, by, btnW, 16, confirming ? "Confirm?" : "Promote", confirming ? Style.DANGER : Style.PRIMARY, ok, () -> {
                if (confirm != id) { confirm = id; return; }
                confirm = -99;
                CompoundTag t = new CompoundTag(); t.putInt("profession", id); op("promote", t);
            });
        }
    }

    // ------------------------------------------------------------------ skills, weapons, legendary, special

    void details() {
        CompoundTag so = soldier();
        String sub = switch (page) {
            case SKILLS -> "Spend skill points earned in battle";
            case WEAPONS -> "Weapon practice and the recruit's favourite";
            case LEGENDARY -> "Rare perks rolled on Recruits level-ups";
            default -> "Signature abilities";
        };
        header(page.label, sub);
        int top = cy + 30, h = cy + ch - top;
        if (so == null) { notEnlisted(top); return; }
        switch (page) {
            case WEAPONS -> panels.weapons(so, cx, cw, top, h);
            case LEGENDARY -> panels.legends(so, cx, cw, top, h);
            case SPECIAL -> { if (SoldierPanels.hasSpecials(so)) panels.specials(so, cx, cw, top, h); else panels.skills(so, cx, cw, top, h); }
            default -> panels.skills(so, cx, cw, top, h);
        }
    }

    void notEnlisted(int top) {
        ui.card(cx, top, cw, 92);
        ui.text("Not available yet", cx + 10, top + 8, GOLD);
        int h = ui.wrap(d.getBoolean("worker")
                ? "Workers keep to their trade: skills, weapon practice and legendary perks are for town soldiers."
                : "This recruit was hired the Villager Recruits way and isn't in a town garrison, so it has no Living Realms skills, weapon practice or legendary perks. Enlist it to start earning them; Overview, Promotions and Inventory work either way.",
                cx + 10, top + 22, cw - 20, MUTED);
        if (d.getBoolean("worker")) return;
        boolean can = d.hasUUID("enlistId") && !d.contains("enlistWhy");
        String label = d.contains("enlistTown") ? "Enlist in " + d.getString("enlistTown") : "Enlist in a town";
        ui.button("rcEnlist2", cx + 10, top + 26 + h, 160, 18, ui.clip(label, 152), Style.SUCCESS, can, () -> op("enlist"));
        if (!can) ui.text(ui.clip(d.getString("enlistWhy"), cw - 190), cx + 178, top + 31 + h, ORANGE);
    }

    // ------------------------------------------------------------------ input

    @Override
    public boolean mouseClicked(double x, double y, int button) {
        x /= fit; y /= fit;
        if (nameBox.visible && nameBox.mouseClicked(x, y, button)) { setFocused(nameBox); return true; }
        nameBox.setFocused(false);
        if (button == 0 && ui.click(x, y)) return true;
        if (button == 0) confirm = -99;
        return super.mouseClicked(x, y, button);
    }

    @Override
    public boolean mouseScrolled(double x, double y, double delta) { return ui.scroll(x / fit, y / fit, delta) || super.mouseScrolled(x / fit, y / fit, delta); }

    @Override
    public boolean keyPressed(int key, int scan, int mods) {
        if (nameBox.isFocused()) {
            if (key == GLFW.GLFW_KEY_ENTER || key == GLFW.GLFW_KEY_KP_ENTER) { rename(); return true; }
            if (key != GLFW.GLFW_KEY_ESCAPE) return nameBox.keyPressed(key, scan, mods) || true;
        }
        if (!livingrealms.client.ClientSetup.RECRUIT.isUnbound() && livingrealms.client.ClientSetup.RECRUIT.matches(key, scan)) { onClose(); return true; }
        // pages follow the hotbar keybinds (1–6 by default); Tab and Shift+Tab step through them
        CompoundTag so = soldier();
        java.util.List<Page> shown = new java.util.ArrayList<>(java.util.List.of(Page.values()));
        if (so == null || !SoldierPanels.hasSpecials(so)) shown.remove(Page.SPECIAL);
        var hotbar = Minecraft.getInstance().options.keyHotbarSlots;
        for (Page p : shown) if (p.ordinal() < hotbar.length && hotbar[p.ordinal()].matches(key, scan)) { showPage(p); return true; }
        if (key == GLFW.GLFW_KEY_TAB) {
            int i = Math.max(0, shown.indexOf(page));
            showPage(shown.get((i + (hasShiftDown() ? shown.size() - 1 : 1)) % shown.size()));
            return true;
        }
        return super.keyPressed(key, scan, mods);
    }
}
