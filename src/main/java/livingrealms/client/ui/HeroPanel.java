package livingrealms.client.ui;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;

import static livingrealms.client.ui.Imm.*;

/** The Hero section at the foot of the Path page: ascension's requirements and price, or a Hero's Valor and ultimate. */
final class HeroPanel {
    static final int STAR_MAX = 10;
    private static long confirmUntil;

    static int height(CompoundTag so) {
        CompoundTag h = so.getCompound("hero");
        if (h.isEmpty()) return 0;
        return h.getBoolean("hero") ? 104 : 38 + h.getList("needs", Tag.TAG_COMPOUND).size() * 13 + 46;
    }

    static String stars(int valor) { return "★".repeat(Math.max(0, valor)) + "☆".repeat(Math.max(0, STAR_MAX - valor)); }

    static String time(int s) { return s >= 60 ? s / 60 + "m " + s % 60 + "s" : s + "s"; }

    /** Draws the section at y; ascend runs when the player confirms. */
    static void draw(Imm ui, CompoundTag so, int x, int y, int w, boolean canCommand, Runnable ascend) {
        CompoundTag h = so.getCompound("hero");
        if (h.isEmpty()) return;
        int ht = height(so);
        String capLine = h.getInt("count") + " / " + Math.max(1, h.getInt("cap")) + " Heroes";
        String capTip = "One Hero per city you hold (" + h.getInt("cities") + " now), at least 1 and at most 5";
        if (h.getBoolean("hero")) {
            ui.card(x, y, w, ht, 0xFF33301F, GOLD);
            ui.text(ui.clip("♛ Hero · " + h.getString("name"), w - 90), x + 8, y + 6, GOLD);
            ui.right(capLine, x + w - 8, y + 6, FAINT);
            if (ui.over(x + w - 80, y + 4, 76, 12)) ui.tip(capLine, capTip);
            ui.text(ui.clip("Gold name · +20% max health · no knockback · no fear · steadies recruits within 12 blocks", w - 16), x + 8, y + 19, MUTED);
            if (ui.over(x, y + 17, w, 12)) ui.tip("Hero perks", "+20% max health; cannot be knocked back", "Its morale never stays below 60 and it never panics",
                    "Your recruits within 12 blocks: +5 morale every 5s while under 50, and they fall back at 20% health instead of 30%");
            int valor = h.getInt("valor"), from = h.getInt("valorFrom"), to = h.getInt("valorTo"), xp = h.getInt("valorXp");
            ui.text("Valor " + valor, x + 8, y + 34, TEXT);
            ui.text(stars(valor), x + 54, y + 34, GOLD);
            ui.right(to > 0 ? (xp - from) + " / " + (to - from) + " XP" : "Highest Valor", x + w - 8, y + 34, MUTED);
            ui.bar(x + 8, y + 46, w - 16, 5, to > 0 ? (xp - from) / (float) Math.max(1, to - from) : 1, GOLD, GOLD_DK);
            if (ui.over(x, y + 32, w, 22)) ui.tip("Valor " + valor + " of " + STAR_MAX, "Combat XP past the top skill rank goes into Valor",
                    "Each level: +1% damage and +1% max health (+" + valor + "% each now)", "Hero bonuses have their own +30% damage budget");
            String ult = h.getString("ultimate");
            int cd = h.getInt("cooldown");
            boolean spent = h.getBoolean("spent");
            ui.text(ui.clip("✦ Ultimate: " + (ult.isEmpty() ? "none" : ult), w - 100), x + 8, y + 60, PURPLE);
            ui.right(spent ? "Used this battle" : cd > 0 ? "Ready in " + time(cd) : "Ready", x + w - 8, y + 60, spent || cd > 0 ? ORANGE : GREEN);
            ui.pushClip(x, y + 72, x + w, y + ht - 2);
            ui.wrap(h.getString("ultimateDesc") + ". Used on its own in battle; 2 minutes between uses.", x + 8, y + 73, w - 16, MUTED);
            ui.popClip();
            return;
        }
        boolean can = h.getBoolean("can");
        ui.card(x, y, w, ht, CARD, can ? GOLD : BORDER);
        ui.text("♛ Ascension · raise a Hero", x + 8, y + 6, can ? GOLD : TEXT);
        ui.right(capLine, x + w - 8, y + 6, FAINT);
        if (ui.over(x + w - 80, y + 4, 76, 12)) ui.tip(capLine, capTip);
        String ult = h.getString("ultimate");
        ui.text(ui.clip("A Hero gains +20% health, no knockback or fear, an aura, Valor" + (ult.isEmpty() ? "" : " and the ultimate " + ult), w - 16), x + 8, y + 19, MUTED);
        if (!ult.isEmpty() && ui.over(x, y + 17, w, 12)) ui.tip(ult, h.getString("ultimateDesc"), "Used on its own in battle; 2 minutes between uses");
        int ry = y + 34;
        ListTag needs = h.getList("needs", Tag.TAG_COMPOUND);
        for (int i = 0; i < needs.size(); i++) {
            CompoundTag n = needs.getCompound(i);
            boolean met = n.getBoolean("met");
            ui.text(met ? "✔" : "✘", x + 10, ry, met ? GREEN : RED);
            ui.text(ui.clip(n.getString("label") + " · " + n.getString("detail"), w - 30), x + 22, ry, met ? TEXT : MUTED);
            ry += 13;
        }
        ui.text(ui.clip("Price: " + h.getString("cost"), w - 130), x + 8, ry + 6, FAINT);
        if (ui.over(x, ry + 4, w - 120, 12)) ui.tip("Price, paid once", h.getString("cost"), "The town has " + plural(h.getInt("emeralds"), "emerald") + " in stock",
                "Emeralds from town stock; the rare item from your inventory, or else town stock");
        boolean confirming = System.currentTimeMillis() < confirmUntil;
        ui.button("heroAscend", x + w - 116, ry + 2, 110, 16, confirming ? "Confirm ascension?" : "Ascend", confirming ? Style.DANGER : Style.PRIMARY,
                canCommand && can && so.getBoolean("seen"), () -> {
                    if (System.currentTimeMillis() >= confirmUntil) { confirmUntil = System.currentTimeMillis() + 4000; return; }
                    confirmUntil = 0;
                    ascend.run();
                });
        String why = h.getString("why");
        if (!can && !why.isEmpty()) ui.text(ui.clip(why, w - 16), x + 8, ry + 22, ORANGE);
    }

    private HeroPanel() {}
}
