package livingrealms.settlement;

import com.talhanation.recruits.ClaimEvent;
import com.talhanation.recruits.ClaimEvents;
import com.talhanation.recruits.FactionEvents;
import com.talhanation.recruits.world.RecruitsClaim;
import com.talhanation.recruits.world.RecruitsClaimManager;
import com.talhanation.recruits.world.RecruitsFaction;
import com.talhanation.recruits.world.RecruitsPlayerInfo;
import livingrealms.Config;
import livingrealms.LivingRealms;
import livingrealms.settlement.vx.Vx;
import livingrealms.settlement.vx.VxBridge;
import net.minecraft.ChatFormatting;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.scores.PlayerTeam;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import javax.annotation.Nullable;
import java.util.*;

/**
 * Each player town holds a Recruits claim owned by its realm's faction, so it shows on the Recruits world map like a
 * Village Expansion village. It starts as VE's 7x7-chunk founding claim and grows with the town's land on every rank.
 * Living Realms stays authoritative: the claim follows the town through conquest, and a Village Expansion claim raid
 * cannot delete it (the claim is laid out again, the town stays). See docs/village-expansion.md.
 */
@Mod.EventBusSubscriber(modid = LivingRealms.MODID)
public final class TownClaims {
    /** Village Expansion's founding claim reaches this many chunks from the centre chunk (7x7). */
    public static final int BASE = 3;
    /** The claim being created right now: exempt from Village Expansion's 600-block rule for new player claims. */
    @Nullable private static UUID creating;
    /** Towns under a Village Expansion claim raid: settlement id to raiding faction. Rebuilt from VE's data after a restart. */
    static final Map<UUID, String> RAIDED = new HashMap<>();
    private static long tick;

    public static boolean enabled() { return Config.TOWN_CLAIMS.get(); }

    static boolean ready() { return ClaimEvents.recruitsClaimManager != null && FactionEvents.recruitsFactionManager != null; }

    /** Recruits claims are chunk lists without a dimension: only overworld towns hold one. Mirrors are Village Expansion's. */
    static boolean eligible(Settlement s) {
        return s.dim.equals(Level.OVERWORLD) && !s.mirror() && (s.owner != null || s.claim != null);
    }

    @Nullable
    public static RecruitsClaim claim(Settlement s) {
        return s.claim == null || !ready() ? null : ClaimEvents.recruitsClaimManager.getClaim(s.claim);
    }

    // ------------------------------------------------------------------ ticking

    @SubscribeEvent
    public static void onServerTick(TickEvent.ServerTickEvent e) {
        if (e.phase != TickEvent.Phase.END || ++tick % 20 != 0) return;
        try {
            tick(e.getServer(), SettlementManager.get(e.getServer()));
        } catch (RuntimeException ex) {
            LivingRealms.LOG.error("Town claims update failed", ex);
        }
    }

    static void tick(MinecraftServer server, SettlementManager m) {
        if (!ready()) return;
        if (!enabled()) {
            for (Settlement s : m.all()) if (s.claim != null) release(server, s);
            return;
        }
        if (Realms.on()) watchRaids(server, m);
        for (Settlement s : List.copyOf(m.all())) update(server, m, s);
    }

    /** The charter founded a town: claim its land at once. */
    public static void found(MinecraftServer server, SettlementManager m, Settlement s) {
        if (!enabled() || !ready()) return;
        try {
            update(server, m, s);
        } catch (RuntimeException ex) {
            LivingRealms.LOG.error("Claiming {} failed", s.name, ex);
        }
    }

    /** Cities changed hands (Kingdoms.transfer): their claims change owner with them. */
    static void follow(MinecraftServer server, SettlementManager m, Collection<Settlement> captured) {
        if (!enabled() || !ready()) return;
        try {
            for (Settlement s : captured) {
                RecruitsClaim c = claim(s);
                if (s.mirror() && c != null && Realms.on() && c.getOwnerFaction() != null) {
                    // Now one of the village faction's centres (VxBridge.annex): held like its other claims.
                    c.setPlayer(new RecruitsPlayerInfo(VxBridge.fakeOwner(), VxBridge.name(s.vxFaction), c.getOwnerFaction()));
                    store(server.overworld(), c);
                } else update(server, m, s);
            }
        } catch (RuntimeException ex) {
            LivingRealms.LOG.error("Moving town claims after a conquest failed", ex);
        }
    }

    /** Creates, reshapes, renames or re-owns the town's claim so it matches the town. */
    static void update(MinecraftServer server, SettlementManager m, Settlement s) {
        if (!eligible(s) || !enabled() || !ready()) return;
        RecruitsClaimManager mgr = ClaimEvents.recruitsClaimManager;
        ServerLevel level = server.overworld();
        String faction = faction(server, m, s);
        RecruitsFaction f = faction == null ? null : FactionEvents.recruitsFactionManager.getFactionByStringID(faction);
        if (f == null) {
            // A town that went to a kingdom without a Recruits faction (a Living Realms NPC kingdom without Village Expansion).
            if (s.claim != null && faction == null) release(server, s);
            return;
        }
        RecruitsClaim c = claim(s);
        if (c == null) {
            if (s.claim != null) {
                String by = RAIDED.remove(s.id);
                if (by != null) Raids.announce(server, s, "Raiders of " + (Realms.on() ? VxBridge.name(by) : by)
                        + " sacked the land of " + s.name + ", but a claim raid cannot take a town. Its claim is restored.");
                else s.log("The town's claim was lost; it is laid out again");
                s.claim = null;
            }
            c = create(level, s, f);
            if (c == null) return;
        }
        if (c.isUnderSiege) return; // a Recruits siege is deciding this claim; onSiegeWon applies its result
        boolean changed = false;
        if (!faction.equals(c.getOwnerFactionStringID())) {
            c.setOwnerFaction(f);
            changed = true;
        }
        RecruitsPlayerInfo who = c.getPlayerInfo();
        UUID holder = holder(s);
        if (who == null || !holder.equals(who.getUUID()) || !Objects.equals(who.getName(), holderName(s, f))) {
            c.setPlayer(new RecruitsPlayerInfo(holder, holderName(s, f), f));
            changed = true;
        }
        if (!s.name.equals(c.getName())) {
            c.setName(s.name);
            changed = true;
        }
        ChunkPos center = new ChunkPos(s.center);
        if (!center.equals(c.getCenter())) {
            c.setCenter(center);
            changed = true;
        }
        Set<ChunkPos> want = shape(mgr, s, c.getUUID()), have = new HashSet<>(c.getClaimedChunks());
        if (!want.equals(have)) {
            for (ChunkPos p : have) if (!want.contains(p)) c.removeChunk(p);
            for (ChunkPos p : want) if (!have.contains(p)) c.addChunk(p);
            changed = true;
        }
        if (changed) store(level, c);
    }

    /** A new claim over the town: open to building and use as before (the ruler may protect it in Recruits' claim screen). */
    @Nullable
    static RecruitsClaim create(ServerLevel level, Settlement s, RecruitsFaction f) {
        RecruitsClaimManager mgr = ClaimEvents.recruitsClaimManager;
        ChunkPos center = new ChunkPos(s.center);
        RecruitsClaim other = mgr.getClaim(center);
        if (other != null) return null; // the centre is already someone's land: a village's, or a claim the player made by hand
        RecruitsClaim c = new RecruitsClaim(s.name, f);
        c.setCenter(center);
        for (ChunkPos p : shape(mgr, s, c.getUUID())) c.addChunk(p);
        c.setBlockInteractionAllowed(true);
        c.setBlockPlacementAllowed(true);
        c.setBlockBreakingAllowed(true);
        creating = c.getUUID();
        try {
            mgr.addOrUpdateClaim(level, c);
        } finally {
            creating = null;
        }
        if (mgr.getClaim(c.getUUID()) == null) return null;
        // The holder is set after creation: Village Expansion would otherwise "refund" a claiming fee nobody paid.
        c.setPlayer(new RecruitsPlayerInfo(holder(s), holderName(s, f), f));
        store(level, c);
        s.claim = c.getUUID();
        s.dirty = true;
        s.log(s.name + " claims " + c.getClaimedChunks().size() + " chunks of land for " + f.getTeamDisplayName());
        return c;
    }

    /**
     * Village Expansion's 7x7-chunk founding claim, plus every chunk whose centre lies within the town's land
     * (Settlement.radius) and half a chunk. Chunks held by any other claim are left out.
     */
    static Set<ChunkPos> shape(RecruitsClaimManager mgr, Settlement s, UUID self) {
        ChunkPos c = new ChunkPos(s.center);
        double r = s.radius() + 8;
        int reach = Math.max(BASE, (int) Math.ceil(r / 16) + 1);
        Set<ChunkPos> out = new LinkedHashSet<>();
        for (int dx = -reach; dx <= reach; dx++) for (int dz = -reach; dz <= reach; dz++) {
            ChunkPos p = new ChunkPos(c.x + dx, c.z + dz);
            double x = p.getMiddleBlockX() - s.center.getX(), z = p.getMiddleBlockZ() - s.center.getZ();
            boolean base = Math.abs(dx) <= BASE && Math.abs(dz) <= BASE;
            if (!base && x * x + z * z > r * r) continue;
            RecruitsClaim held = mgr.getClaim(p);
            if (held != null && !held.getUUID().equals(self)) continue;
            out.add(p);
        }
        return out;
    }

    /** Index, broadcast and write through to Recruits' save data (Village Expansion reloads claims from it). */
    static void store(ServerLevel level, RecruitsClaim c) {
        ClaimEvents.recruitsClaimManager.addOrUpdateClaim(level, c);
        ClaimEvents.recruitsClaimManager.save(level);
    }

    /** Drops the town's claim (the switch was turned off, or the town went to a kingdom without a faction). */
    static void release(MinecraftServer server, Settlement s) {
        UUID id = s.claim;
        s.claim = null;
        RAIDED.remove(s.id);
        if (id == null || !ready()) return;
        ServerLevel level = server.overworld();
        if (ClaimEvents.recruitsClaimManager.removeClaim(level, id)) ClaimEvents.recruitsClaimManager.save(level);
    }

    static UUID holder(Settlement s) { return s.owner != null ? s.owner : s.kingdomId(); }

    static String holderName(Settlement s, RecruitsFaction f) {
        if (s.owner != null && !s.ownerName.isEmpty()) return s.ownerName;
        String n = f.getTeamDisplayName();
        return n == null || n.isEmpty() ? f.getStringID() : n;
    }

    // ------------------------------------------------------------------ owners

    /** The Recruits faction that holds the town's claim: its realm. */
    @Nullable
    static String faction(MinecraftServer server, SettlementManager m, Settlement s) {
        if (Realms.on()) return Realms.realm(server, m, s);
        if (s.isAi()) return null;
        String id = realm(server.overworld(), s.owner, s.ownerName, m.realms.get(s.owner));
        if (id != null) m.realms.put(s.owner, id);
        return id;
    }

    /**
     * Without Village Expansion: the ruler's own Recruits faction, or a realm led by them under the same id Village
     * Expansion mode uses (VxBridge.realm), so switching the integration on later keeps the claim's owner.
     */
    @Nullable
    static String realm(ServerLevel level, UUID ruler, String rulerName, @Nullable String known) {
        var factions = FactionEvents.recruitsFactionManager;
        PlayerTeam team = rulerName.isEmpty() ? null : level.getScoreboard().getPlayersTeam(rulerName);
        if (team != null && factions.getFactionByStringID(team.getName()) != null) return team.getName();
        String id = known != null && !known.isEmpty() ? known : "lr_" + ruler.toString().replace("-", "").substring(0, 10);
        String display = (rulerName.isEmpty() ? "Unknown" : rulerName) + "'s Realm";
        team = level.getScoreboard().getPlayerTeam(id);
        if (team == null) {
            team = level.getScoreboard().addPlayerTeam(id);
            team.setDisplayName(Component.literal(display));
            team.setColor(ChatFormatting.GOLD);
            team.setAllowFriendlyFire(false);
        }
        if (!rulerName.isEmpty() && level.getScoreboard().getPlayersTeam(rulerName) == null) level.getScoreboard().addPlayerToTeam(rulerName, team);
        if (factions.getFactionByStringID(id) == null) {
            CompoundTag banner = new ItemStack(Items.YELLOW_BANNER).save(new CompoundTag());
            factions.addTeam(id, display, ruler, rulerName, banner, (byte) 4, ChatFormatting.GOLD);
            RecruitsFaction f = factions.getFactionByStringID(id);
            if (f != null && f.getMembers().stream().noneMatch(p -> ruler.equals(p.getUUID()))) f.addMember(ruler, rulerName);
            factions.save(level.getServer().overworld());
        }
        return factions.getFactionByStringID(id) == null ? null : id;
    }

    // ------------------------------------------------------------------ Village Expansion

    /**
     * Village Expansion refuses new player claims within 600 blocks of its villages and pending towers. A town exists
     * whether or not it is claimed, so Living Realms keeps its own claim (and VE's rule still keeps new villages away).
     */
    @SubscribeEvent(priority = EventPriority.LOWEST, receiveCanceled = true)
    public static void keep(ClaimEvent.Updated e) {
        if (creating != null && e.isCanceled() && e.getClaim() != null && creating.equals(e.getClaim().getUUID())) e.setCanceled(false);
    }

    /** Village Expansion's field armies raid plain player claims. The town hears of it, sounds the alarm and keeps its claim. */
    static void watchRaids(MinecraftServer server, SettlementManager m) {
        ServerLevel level = server.overworld();
        Map<UUID, String> raids = VxBridge.claimRaids(level);
        long now = level.getGameTime();
        for (Settlement s : m.all()) {
            if (s.claim == null || s.mirror()) continue;
            String by = raids.get(s.claim);
            if (by != null) {
                if (!by.equals(RAIDED.put(s.id, by)))
                    Raids.announce(server, s, VxBridge.name(by) + " is raiding the land of " + s.name + ". The town sounds the alarm.");
                s.alarmUntil = Math.max(s.alarmUntil, now + 100);
            } else if (RAIDED.containsKey(s.id) && claim(s) != null) {
                RAIDED.remove(s.id);
                s.log("The raiders left the land of " + s.name);
            }
        }
    }

    private TownClaims() {}
}
