package livingrealms.military;

import livingrealms.LivingRealms;
import livingrealms.net.Net;
import livingrealms.net.View;
import livingrealms.settlement.Actions;
import livingrealms.settlement.Settlement;
import livingrealms.settlement.SettlementManager;
import livingrealms.settlement.Simulation;
import net.minecraft.ChatFormatting;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Items;
import net.minecraftforge.event.entity.player.PlayerInteractEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import javax.annotation.Nullable;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Shift + right-click on a recruit you own opens the Recruit Command screen instead of Recruits' own GUI.
 * Client requests arrive as the "recruit" packet {e, op}; the screen's data goes back as "recruit".
 */
@Mod.EventBusSubscriber(modid = LivingRealms.MODID)
public final class RecruitCommand {
    private static final Map<UUID, Long> OPENED = new HashMap<>();
    static final String ROYAL_REQ = "Command · rank 2 · 32 emeralds", ROYAL_NOTE = "Personal bodyguard: follows and defends you, even during alarms";

    @SubscribeEvent(priority = EventPriority.HIGH)
    public static void interactAt(PlayerInteractEvent.EntityInteractSpecific e) { intercept(e, e.getTarget()); }

    @SubscribeEvent(priority = EventPriority.HIGH)
    public static void interact(PlayerInteractEvent.EntityInteract e) { intercept(e, e.getTarget()); }

    /** Runs on both sides so neither Recruits' client nor server handler sees the click. */
    static void intercept(PlayerInteractEvent e, Entity target) {
        Player p = e.getEntity();
        if (!p.isShiftKeyDown() || !RecruitsBridge.ownedBy(target, p.getUUID())) return;
        e.setCanceled(true);
        e.setCancellationResult(InteractionResult.SUCCESS);
        if (e.getHand() != InteractionHand.MAIN_HAND || !(p instanceof ServerPlayer sp)) return;
        long now = sp.level().getGameTime();
        if (OPENED.getOrDefault(sp.getUUID(), -1L) == now) return;
        OPENED.put(sp.getUUID(), now);
        send(sp, target, true);
    }

    public static void handle(ServerPlayer p, CompoundTag d) {
        if (!d.hasUUID("e")) return;
        Entity e = SoldierProgression.findEntity(p.server, d.getUUID("e"));
        if (e == null || !e.isAlive() || !RecruitsBridge.ownedBy(e, p.getUUID())) {
            if (!d.getString("op").equals("refresh")) tell(p, "That recruit isn't loaded or isn't yours");
            return;
        }
        String op = d.getString("op");
        if (op.equals("inventory")) {
            if (e.level() != p.level() || e.distanceTo(p) > 8) tell(p, "Walk closer to " + e.getName().getString() + " to open its inventory");
            else RecruitsBridge.openInventory(e, p);
            return;
        }
        String err = null;
        switch (op) {
            case "follow" -> RecruitsBridge.follow(e, p.getUUID());
            case "hold" -> RecruitsBridge.holdAt(e, e.blockPosition());
            case "wander" -> RecruitsBridge.wander(e);
            case "rename" -> err = rename(p, e, d.getString("name"));
            case "enlist" -> err = enlist(p, e);
            case "promote" -> {
                int prof = d.getInt("profession");
                err = RecruitsBridge.promotionBlocker(e, prof);
                if (err == null) {
                    Entity n = promote(p, e, prof);
                    if (n == null) err = e.isRemoved() ? "Promoted, but the new recruit could not be found" : "Villager Recruits refused the promotion";
                    else { e = n; tell(p, e.getName().getString() + " is now a " + RecruitsBridge.PROFESSIONS[prof].label()); }
                }
            }
            default -> {}
        }
        if (err != null) tell(p, err);
        send(p, e, op.equals("open"));
    }

    public static void send(ServerPlayer p, Entity e, boolean open) {
        CompoundTag t = describe(p, e);
        t.putBoolean("open", open);
        Net.send(p, "recruit", t);
    }

    static CompoundTag describe(ServerPlayer p, Entity e) {
        CompoundTag t = new CompoundTag();
        t.putUUID("e", e.getUUID());
        t.putString("name", e.getName().getString());
        t.putString("type", e.getType().getDescription().getString());
        t.putString("unit", RecruitsBridge.unitOf(e).name());
        if (e instanceof LivingEntity le) {
            t.putFloat("hp", le.getHealth());
            t.putFloat("max", le.getMaxHealth());
            t.putString("gear", Simulation.gearSummary(le));
        }
        t.putFloat("morale", RecruitsBridge.morale(e));
        t.putInt("kills", RecruitsBridge.kills(e));
        t.putInt("lvl", RecruitsBridge.level(e));
        t.putInt("xp", RecruitsBridge.xp(e));
        t.putInt("xpNeeded", RecruitsBridge.xpForLevel());
        t.putInt("follow", RecruitsBridge.followState(e));
        t.putString("origin", RecruitOrigins.origin(e).name());
        t.putBoolean("worker", RecruitsBridge.isWorker(e));
        // Recruits' own inventory screen only finds recruits within 10 blocks of the player
        t.putBoolean("near", e.level() == p.level() && e.distanceTo(p) <= 8);
        SoldierProgression.Record rec = SoldierProgression.record(p.server, e);
        ListTag promos = new ListTag();
        if (rec != null) {
            Settlement s = rec.settlement();
            t.put("soldier", View.soldier(s, rec.soldier()));
            t.putUUID("settlement", s.id);
            t.putString("town", s.name);
            t.putBoolean("hasCommand", s.has("command"));
            t.putInt("emeralds", s.stock.count(Items.EMERALD));
            String why = !p.getUUID().equals(s.owner) ? "Only the town's owner can appoint Royal Guards" : Actions.royalGuardBlocker(s, rec.soldier());
            promos.add(promotion(-1, "Royal Guard", ROYAL_REQ, ROYAL_NOTE,
                    rec.soldier().royalGuard ? "active" : why == null ? "ok" : "locked", why));
        } else {
            Settlement home = enlistTarget(p, e);
            if (home != null) { t.putUUID("enlistId", home.id); t.putString("enlistTown", home.name); }
            String why = enlistBlocker(p, e, home);
            if (why != null) t.putString("enlistWhy", why);
            promos.add(promotion(-1, "Royal Guard", ROYAL_REQ, ROYAL_NOTE, "locked", "Enlist in a town first"));
        }
        for (int i = 0; i < RecruitsBridge.PROFESSIONS.length; i++) {
            var pr = RecruitsBridge.PROFESSIONS[i];
            String why = RecruitsBridge.promotionBlocker(e, i);
            promos.add(promotion(i, pr.label(), i >= 5 ? "Not available" : "Recruits level " + pr.level(), pr.note(),
                    i >= 5 ? "disabled" : why == null ? "ok" : "locked", why));
        }
        t.put("promotions", promos);
        return t;
    }

    static CompoundTag promotion(int id, String label, String req, String note, String state, @Nullable String why) {
        CompoundTag t = new CompoundTag();
        t.putInt("id", id);
        t.putString("label", label);
        t.putString("req", req);
        t.putString("note", note);
        t.putString("state", state);
        if (why != null) t.putString("why", why);
        return t;
    }

    /** The player's own settlement nearest the recruit, in its dimension. */
    @Nullable
    static Settlement enlistTarget(ServerPlayer p, Entity e) {
        Settlement best = null;
        double bd = Double.MAX_VALUE;
        for (Settlement s : SettlementManager.get(p.server).ownedBy(p.getUUID())) {
            if (!s.dim.equals(e.level().dimension())) continue;
            double d = s.center.distSqr(e.blockPosition());
            if (d < bd) { bd = d; best = s; }
        }
        return best;
    }

    @Nullable
    static String enlistBlocker(ServerPlayer p, Entity e, @Nullable Settlement s) {
        if (RecruitsBridge.isWorker(e)) return "Workers belong to their fields, not a garrison";
        if (s == null) return "You don't own a town in this dimension. Place a Town Charter first.";
        if (s.soldiers.size() + s.training.size() >= s.d.garrisonCap) return s.name + "'s garrison is full (" + s.d.garrisonCap + "). Build barracks or towers.";
        return null;
    }

    @Nullable
    static String enlist(ServerPlayer p, Entity e) {
        if (SoldierProgression.record(p.server, e) != null) return "Already serves in a town garrison";
        Settlement s = enlistTarget(p, e);
        String why = enlistBlocker(p, e, s);
        if (why != null) return why;
        Settlement.Soldier so = new Settlement.Soldier();
        so.entity = e.getUUID();
        so.type = RecruitsBridge.unitOf(e);
        so.name = e.getName().getString();
        so.seen = true;
        if (e instanceof LivingEntity le) { so.health = le.getHealth(); so.maxHealth = le.getMaxHealth(); so.gear = Simulation.gearSummary(le); }
        so.level = RecruitsBridge.level(e);
        so.kills = RecruitsBridge.kills(e);
        so.morale = RecruitsBridge.morale(e);
        RecruitOrigins.sync(so, e);
        WeaponProficiency.initialise(so);
        s.soldiers.add(so);
        s.log(so.name + " enlisted in the garrison");
        s.dirty = true;
        tell(p, so.name + " enlisted in " + s.name);
        return null;
    }

    @Nullable
    static String rename(ServerPlayer p, Entity e, String name) {
        name = name.trim();
        if (name.isEmpty() || name.length() > 32) return "Names are 1 to 32 characters";
        e.setCustomName(Component.literal(name));
        SoldierProgression.Record rec = SoldierProgression.record(p.server, e);
        if (rec != null) { rec.soldier().name = name; rec.settlement().dirty = true; }
        return null;
    }

    /** Recruits swaps in a new entity, so the garrison record and origin move to it. */
    @Nullable
    static Entity promote(ServerPlayer p, Entity e, int profession) {
        SoldierProgression.Record rec = SoldierProgression.record(p.server, e);
        boolean hasOrigin = e.getPersistentData().contains(RecruitOrigins.KEY);
        RecruitOrigin origin = RecruitOrigins.origin(e);
        Entity n = RecruitsBridge.promote(e, profession, p);
        if (n == null) return null;
        if (hasOrigin) RecruitOrigins.assign(n, origin);
        if (rec != null) {
            rec.soldier().entity = n.getUUID();
            rec.settlement().log(rec.soldier().name + " was promoted to " + RecruitsBridge.PROFESSIONS[profession].label());
            rec.settlement().dirty = true;
        }
        return n;
    }

    static void tell(ServerPlayer p, String msg) { p.displayClientMessage(Component.literal(msg).withStyle(ChatFormatting.GOLD), true); }

    private RecruitCommand() {}
}
