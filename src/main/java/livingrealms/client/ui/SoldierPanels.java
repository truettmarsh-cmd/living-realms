package livingrealms.client.ui;

import livingrealms.military.LegendaryPerk;
import livingrealms.military.PehkuiBridge;
import livingrealms.military.RecruitOrigin;
import livingrealms.military.SoldierSkills;
import livingrealms.military.WeaponProficiency;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;

import java.util.*;

import static livingrealms.client.ui.Imm.*;

/** One recruit's detail pages (skills, weapons, legendary, special, origin), shared by the City Manager and Recruit Command. */
final class SoldierPanels {
    /** What the page needs from the screen hosting it. */
    interface Host {
        boolean canCommand();
        /** A settlement action for this soldier's town, as CityScreen.act sends it. */
        void act(String action, CompoundTag data);
        boolean hasResearch(String id);
        int emeralds();
        /** Royal Guard and dismissal live under Skills in the City Manager; Recruit Command has a Promotions page. */
        default boolean royalFooter() { return true; }
        default void dismissed() {}
    }

    static final String[] TABS = {"Skills", "Weapons", "Legendary", "Origin"};
    static final String[] TABS_SPECIAL = {"Skills", "Weapons", "Legendary", "Special", "Origin"};

    private final Imm ui;
    private final Host host;
    private int cx, cw;

    SoldierPanels(Imm ui, Host host) { this.ui = ui; this.host = host; }

    static boolean hasSpecials(CompoundTag so) { return so.contains("specials", Tag.TAG_LIST); }

    /** Tab labels for this soldier; Special appears only when the perks data is present. */
    static String[] tabs(CompoundTag so) { return hasSpecials(so) ? TABS_SPECIAL : TABS; }

    void resetScroll() { for (String s : new String[]{"soldierSkills", "soldierWeapons", "soldierLegends", "soldierSpecials", "soldierOrigin"}) ui.resetScroll(s); }

    /** Draws tab {@code index} of {@link #tabs}. */
    void draw(CompoundTag so, int index, int x, int w, int sy, int sh) {
        String[] tabs = tabs(so);
        switch (tabs[Math.max(0, Math.min(tabs.length - 1, index))]) {
            case "Weapons" -> weapons(so, x, w, sy, sh);
            case "Legendary" -> legends(so, x, w, sy, sh);
            case "Special" -> specials(so, x, w, sy, sh);
            case "Origin" -> origin(so, x, w, sy, sh);
            default -> skills(so, x, w, sy, sh);
        }
    }

    CompoundTag soldierData(CompoundTag so) {
        CompoundTag data = new CompoundTag();
        data.putUUID("soldier", so.getUUID("e"));
        return data;
    }

    void origin(CompoundTag so, int x, int w, int sy, int sh) {
        cx = x; cw = w;
        RecruitOrigin origin = RecruitOrigin.byId(so.getString("origin"));
        int off = ui.beginScroll("soldierOrigin", cx, sy, cw, sh, 250);
        int y = sy - off;
        ui.text("Born " + origin.label, cx + 4, y + 4, GOLD);
        int h = ui.wrap(origin.description + ". Origin traits stay with this recruit, including after leaving town service. These bonuses stack with learned skills.", cx + 4, y + 23, cw - 14, MUTED);
        h += ui.wrap(PehkuiBridge.installed() ? "Pehkui size: " + Math.round(origin.height * 100) + "% height, " + Math.round(origin.width * 100) + "% width. Innate size does not add damage or reach." : "Install optional Pehkui for visible height and width differences. Origin and attribute traits already work without it.", cx + 4, y + 33 + h, cw - 14, TEXT);
        ui.wrap("Origins are assigned once to new recruits using configurable weights (default: human 80, dwarf 8, elf 9, giant 3). Existing unmarked recruits remain human. Equipment and training never reroll origin.", cx + 4, y + 43 + h, cw - 14, FAINT);
        ui.endScroll();
    }

    void skills(CompoundTag so, int x, int w, int sy, int sh) {
        cx = x; cw = w;
        Set<SoldierSkills> learned = EnumSet.noneOf(SoldierSkills.class);
        for (Tag value : so.getList("skills", Tag.TAG_STRING)) {
            SoldierSkills skill = SoldierSkills.byId(value.getAsString());
            if (skill != null) learned.add(skill);
        }
        int xp = so.getInt("skillXp"), rank = so.contains("rank") ? so.getInt("rank") : SoldierSkills.earnedPoints(xp);
        int points = SoldierSkills.availablePoints(xp, learned);
        int maxPoints = so.contains("maxPoints") ? so.getInt("maxPoints") : SoldierSkills.MAX_POINTS;
        boolean seen = so.getBoolean("seen"), progression = so.contains("xpNeeded");
        int head = progression ? 24 : 0;
        int footer = host.royalFooter() ? 90 : 0;
        String intro = "Kills give 25 XP; damage, alarms, raid defence and escort duty add up to 75 XP a day. Drill: 40 XP. 50 XP per point (" + maxPoints + " max); respec refunds all.";
        int extra = Math.max(0, ui.wrapHeight(intro, cw - 12) - 20);
        int off = ui.beginScroll("soldierSkills", cx, sy, cw, sh, 566 + head + footer + extra);
        int y = sy - off;
        String title = so.contains("rankTitle") ? so.getString("rankTitle") + " · rank " + rank : "Skill rank " + rank;
        ui.text(ui.clip(title + " · " + xp + " combat XP · " + points + " points available", cw - 12), cx + 4, y + 2, GOLD);
        if (progression) {
            int into = so.getInt("xpInto"), need = Math.max(1, so.getInt("xpNeeded"));
            int bw = Math.max(40, cw - 200);
            ui.bar(cx + 4, y + 15, bw, 5, into / (float) need, GOLD, GOLD_DK);
            String toNext = into + " / " + need + " XP to next rank";
            ui.text(ui.clip(toNext, bw), cx + 4, y + 24, FAINT);
            if (ui.over(cx + 4, y + 13, bw, 20)) ui.tip(toNext);
            int bx = cx + bw + 12;
            if (so.contains("drillCost")) {
                boolean ready = so.getBoolean("drillReady");
                int cd = so.getInt("drillCooldown");
                String label = ready ? "Drill" : cd > 0 ? "Drill · " + (cd >= 60 ? cd / 60 + "m" : cd + "s") : "Drill";
                ui.button("drillSoldier", bx, y + 12, 84, 17, label, Style.PRIMARY, host.canCommand() && ready && seen, () -> host.act("drillSoldier", soldierData(so)));
                if (ui.over(bx, y + 12, 84, 17)) ui.tip("Drill: train this recruit up a rank", "Costs " + so.getString("drillCost"), ready ? "Ready now" : cd > 0 ? "Ready in " + cd + "s" : "Not ready");
            }
            if (so.contains("respecCost")) {
                ui.button("respecSoldier", bx + 88, y + 12, 84, 17, "Respec", Style.SECONDARY, host.canCommand() && seen && !learned.isEmpty(), () -> host.act("respecSoldier", soldierData(so)));
                if (ui.over(bx + 88, y + 12, 84, 17)) ui.tip("Respec: forget learned skills and refund their points", "Costs " + so.getString("respecCost"));
            }
        }
        y += head;
        ui.wrap(intro, cx + 4, y + 17, cw - 12, MUTED);
        y += extra;
        int cardW = (cw - 16) / 3;
        SoldierSkills[] all = SoldierSkills.values();
        for (int branch = 0; branch < 3; branch++) {
            int bx = cx + branch * (cardW + 6);
            ui.text(all[branch * 6].branch, bx + 4, y + 47, FAINT);
            for (int depth = 0; depth < 6; depth++) {
                final SoldierSkills skill = all[branch * 6 + depth];
                int yy = y + 61 + depth * 82;
                boolean known = learned.contains(skill), prerequisite = skill.unlocked(learned), ranked = rank >= skill.requiredRank;
                ui.card(bx, yy, cardW, 76, known ? 0xFF303725 : CARD, known ? GOLD : BORDER);
                ui.text(ui.clip(skill.label, cardW - 10), bx + 5, yy + 5, known ? GOLD : TEXT);
                ui.pushClip(bx, yy, bx + cardW, yy + 55);
                ui.wrap(skill.description, bx + 5, yy + 17, cardW - 10, MUTED);
                ui.popClip();
                String label = known ? "Learned" : !ranked ? "Needs rank " + skill.requiredRank : !prerequisite ? "Needs prior skill" : "Learn · 1 point";
                ui.button("learn" + skill.name(), bx + 4, yy + 57, cardW - 8, 15, ui.clip(label, cardW - 16),
                        known ? Style.GHOST : Style.PRIMARY, host.canCommand() && !known && prerequisite && ranked && points > 0 && seen, () -> {
                            CompoundTag data = soldierData(so); data.putString("skill", skill.name()); host.act("learnSoldierSkill", data);
                        });
                if (ui.over(bx, yy, cardW, 76)) ui.tip(skill.label, skill.description, "Skill rank " + skill.requiredRank + " · 1 earned point",
                        skill.prerequisite == null ? "Root skill" : "Requires " + skill.prerequisite.label);
            }
        }
        if (host.royalFooter()) royalGuard(so, rank, y + 566);
        ui.endScroll();
    }

    void royalGuard(CompoundTag so, int rank, int gy) {
        boolean royal = so.getBoolean("royalGuard"), seen = so.getBoolean("seen");
        ui.text(royal ? "Royal Guard · your personal bodyguard" : "Royal Guard promotion", cx + 4, gy, GOLD);
        ui.wrap("Royal Guards follow you and defend against nearby hostiles, even during town alarms. Promotion: Command research, skill rank 2, and 32 emeralds.", cx + 4, gy + 13, cw - 12, MUTED);
        boolean canAppoint = host.hasResearch("command") && rank >= 2 && host.emeralds() >= 32;
        if (!host.canCommand()) return;
        ui.button("royalGuard", cx + 4, gy + 55, 170, 17, royal ? "Return to town duty" : "Promote to Royal Guard", Style.PRIMARY,
                seen && (royal || canAppoint), () -> { CompoundTag data = soldierData(so); data.putBoolean("on", !royal); host.act("royalGuard", data); });
        ui.button("dismissSoldier", cx + cw - 105, gy + 55, 96, 17, "Dismiss recruit", Style.GHOST, seen, () -> {
            host.act("dismiss", soldierData(so)); host.dismissed();
        });
        if (ui.over(cx + cw - 105, gy + 55, 96, 17)) ui.tip("Releases town service and its skill bonuses. The recruit remains yours in Villager Recruits.");
    }

    void weapons(CompoundTag so, int x, int w, int sy, int sh) {
        cx = x; cw = w;
        WeaponProficiency favorite = WeaponProficiency.byId(so.getString("preferredWeapon"));
        CompoundTag practice = so.getCompound("weaponXp");
        int off = ui.beginScroll("soldierWeapons", cx, sy, cw, sh, 420);
        int y = sy - off;
        ui.text(ui.clip("Favourite: " + (favorite == null ? "Being assigned" : favorite.label), cw - 12), cx + 4, y + 3, GOLD);
        y += 19 + ui.wrap("The favourite gives +5% damage and faster practice. Proficiency adds +2% per rank (10% max). Weapon bonuses cap at 15%; normal combat bonuses at 15%; legendary bonuses at 10%.", cx + 4, y + 19, cw - 12, MUTED) + 8;
        for (WeaponProficiency family : WeaponProficiency.values()) {
            int xp = Math.max(0, practice.getInt(family.name()));
            int rank = WeaponProficiency.rank(xp), bonus = rank * 2 + (family == favorite ? 5 : 0);
            ui.card(cx + 2, y, cw - 10, 35, family == favorite ? 0xFF303725 : CARD, family == favorite ? GOLD : BORDER);
            ui.text(ui.clip(family.label + (family == favorite ? " · favourite" : ""), cw - 136), cx + 9, y + 5, family == favorite ? GOLD : TEXT);
            ui.right("Rank " + rank + "/5 · +" + bonus + "%", cx + cw - 16, y + 5, family == favorite ? GOLD : MUTED);
            ui.bar(cx + 9, y + 20, Math.max(25, cw - 153), 4, Math.min(1, xp / (float) WeaponProficiency.MAX_XP), GOLD, GOLD_DK);
            ui.right(xp + " / " + WeaponProficiency.MAX_XP + " XP", cx + cw - 16, y + 19, FAINT);
            if (ui.over(cx + 2, y, cw - 10, 35)) ui.tip(family.label,
                    "Practice comes from actual damage and kills against hostile creatures.",
                    "Weapon material changes preserve practice in the same weapon family.",
                    family == favorite ? "Favourite: +5% damage, +25% practice XP." : "Proficiency adds +2% damage per rank.");
            y += 40;
        }
        ui.wrap("Weapon affinity belongs to this recruit; opening the screen or changing equipment does not reroll it.", cx + 4, y + 4, cw - 12, FAINT);
        ui.endScroll();
    }

    void legends(CompoundTag so, int x, int w, int sy, int sh) {
        cx = x; cw = w;
        Set<LegendaryPerk> earned = EnumSet.noneOf(LegendaryPerk.class);
        for (Tag value : so.getList("legendary", Tag.TAG_STRING)) {
            LegendaryPerk perk = LegendaryPerk.byId(value.getAsString());
            if (perk != null) earned.add(perk);
        }
        int nativeLevel = so.getInt("lvl");
        int slots = LegendaryPerk.slots(nativeLevel), pity = so.getInt("legendaryPity");
        LegendaryPerk[] all = LegendaryPerk.values();
        int rows = (all.length + 1) / 2;
        int off = ui.beginScroll("soldierLegends", cx, sy, cw, sh, 108 + rows * 88);
        int y = sy - off;
        ui.text(ui.clip("Recruits level " + nativeLevel + " · " + earned.size() + " / " + slots + " legendary slots · next roll " + Math.round(LegendaryPerk.chance(pity) * 100) + "%", cw - 12), cx + 4, y + 3, GOLD);
        int h = ui.wrap("From Recruits level 8, each new level rolls 12%, +3% for every miss; after 6 misses the next roll is certain (a first perk by level 14 at the latest). A second slot opens at level 14. Reopening this screen never rerolls.", cx + 4, y + 19, cw - 12, MUTED);
        h += ui.wrap("Normal combat bonuses cap at 15%; weapon bonuses at 15%; legendary bonuses at 10% (40% combined). Damage reduction caps at 25%.", cx + 4, y + 25 + h, cw - 12, FAINT);
        int w2 = (cw - 16) / 2, top = y + 33 + h;
        for (int i = 0; i < all.length; i++) {
            LegendaryPerk perk = all[i];
            int px = cx + (i % 2) * (w2 + 6), yy = top + (i / 2) * 88;
            boolean known = earned.contains(perk);
            ui.card(px, yy, w2, 82, known ? 0xFF343044 : CARD, known ? 0xFFC59BF5 : BORDER);
            ui.text(ui.clip(perk.label, w2 - 12), px + 6, yy + 6, known ? 0xFFC59BF5 : TEXT);
            ui.wrap(perk.description, px + 6, yy + 20, w2 - 12, MUTED);
            ui.text(known ? "Earned" : "Rare level-up reward", px + 6, yy + 67, known ? GOLD : FAINT);
            if (ui.over(px, yy, w2, 82)) ui.tip(perk.label, perk.description,
                    known ? "Earned and saved on this recruit" : "A rare roll picks a perk suited to the recruit; this is not a purchase.");
        }
        ui.endScroll();
    }

    /** Special skills, drawn generically from the {@code specials} list: {id, label, desc, state, req}. */
    void specials(CompoundTag so, int x, int w, int sy, int sh) {
        cx = x; cw = w;
        ListTag list = so.getList("specials", Tag.TAG_COMPOUND);
        int slots = so.getInt("specialSlots"), chosen = 0;
        for (Tag t : list) if (((CompoundTag) t).getString("state").equals("chosen")) chosen++;
        int w2 = (cw - 16) / 2, rows = (list.size() + 1) / 2;
        int off = ui.beginScroll("soldierSpecials", cx, sy, cw, sh, 44 + rows * 82);
        int y = sy - off;
        ui.text(chosen + " / " + slots + " special skill slots used", cx + 4, y + 3, GOLD);
        ui.wrap("Special skills are signature abilities. Each slot holds one; more slots open as the recruit rises.", cx + 4, y + 17, cw - 12, MUTED);
        if (list.isEmpty()) ui.text("No special skills for this recruit yet.", cx + 4, y + 44, FAINT);
        for (int i = 0; i < list.size(); i++) {
            CompoundTag sp = list.getCompound(i);
            String state = sp.getString("state");
            boolean has = state.equals("chosen"), open = state.equals("available");
            int px = cx + (i % 2) * (w2 + 6), yy = y + 44 + (i / 2) * 82;
            ui.card(px, yy, w2, 76, has ? 0xFF303725 : CARD, has ? GOLD : open ? BLUE : BORDER);
            ui.text(ui.clip(sp.getString("label"), w2 - 12), px + 6, yy + 5, has ? GOLD : open ? TEXT : MUTED);
            ui.pushClip(px, yy, px + w2, yy + 56);
            ui.wrap(sp.getString("desc"), px + 6, yy + 17, w2 - 12, MUTED);
            ui.popClip();
            String req = sp.getString("req");
            if (has) ui.text("Chosen", px + 6, yy + 61, GOLD);
            else if (open) ui.button("special" + i, px + 4, yy + 57, w2 - 8, 15, chosen < slots ? "Choose" : "No free slot", Style.PRIMARY,
                    host.canCommand() && chosen < slots && so.getBoolean("seen"), () -> { CompoundTag d = soldierData(so); d.putString("special", sp.getString("id")); host.act("chooseSpecial", d); });
            else ui.text(ui.clip("🔒 " + (req.isEmpty() ? "Locked" : req), w2 - 12), px + 6, yy + 61, RED);
            if (ui.over(px, yy, w2, 76)) ui.tip(sp.getString("label"), sp.getString("desc"), has ? "Chosen" : req.isEmpty() ? "Available" : "Requires " + req);
        }
        ui.endScroll();
    }
}
