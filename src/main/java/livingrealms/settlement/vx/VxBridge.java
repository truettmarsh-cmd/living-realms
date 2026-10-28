package livingrealms.settlement.vx;

import com.example.villagerecruits.assessment.FactionAssessmentManager;
import com.example.villagerecruits.claim.VillageClaimConquestHandler;
import com.example.villagerecruits.claim.VillageClaimIntegration;
import com.example.villagerecruits.claim.VillageFastConquest;
import com.example.villagerecruits.config.VRConfig;
import com.example.villagerecruits.data.VillageFactionSaveData;
import com.example.villagerecruits.diplomacy.AssaultManager;
import com.example.villagerecruits.diplomacy.VRDiplomacy;
import com.example.villagerecruits.diplomacy.VillageAutoDiplomacy;
import com.example.villagerecruits.faction.VillageFaction;
import com.example.villagerecruits.faction.VillageFactionManager;
import com.example.villagerecruits.faction.VillageNameGenerator;
import com.example.villagerecruits.structures.cityplan.CityPlan;
import com.example.villagerecruits.structures.cityplan.CityPlanGenerator;
import com.talhanation.recruits.ClaimEvents;
import com.talhanation.recruits.FactionEvents;
import com.talhanation.recruits.world.RecruitsClaim;
import com.talhanation.recruits.world.RecruitsDiplomacyManager.DiplomacyStatus;
import com.talhanation.recruits.world.RecruitsFaction;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.scores.PlayerTeam;

import javax.annotation.Nullable;
import java.util.*;

/** Every call into Village Expansion and Recruits' faction, claim and diplomacy managers goes through here. Only reached when Vx.active(). */
public final class VxBridge {
    /** Living Realms kingdoms that are not already in a Recruits faction get one with this prefix. */
    public static final String PREFIX = "lr_";

    // ------------------------------------------------------------------ factions

    public static boolean ready() { return FactionEvents.recruitsFactionManager != null && FactionEvents.recruitsDiplomacyManager != null; }

    public static boolean exists(String id) { return ready() && FactionEvents.recruitsFactionManager.getFactionByStringID(id) != null; }

    /** A Village Expansion NPC faction (village_*), as opposed to a player's realm. */
    public static boolean isNpc(String id) { return VillageFactionManager.isAiVillageFaction(id); }

    public static String name(String id) {
        RecruitsFaction f = ready() ? FactionEvents.recruitsFactionManager.getFactionByStringID(id) : null;
        if (f != null && f.getTeamDisplayName() != null && !f.getTeamDisplayName().isEmpty()) return f.getTeamDisplayName();
        String n = VillageFactionManager.generatedNameFor(id);
        return n == null || n.isEmpty() ? id : n;
    }

    public static String villageName(BlockPos center) {
        String n = VillageNameGenerator.nameFor(center);
        return n == null || n.isEmpty() ? "Village" : n;
    }

    /** The faction's capital centre, or null. */
    @Nullable
    public static BlockPos capital(String id) {
        VillageFaction f = VillageFactionManager.getFaction(id);
        return f == null ? null : f.center;
    }

    public static int recruitCount(String id) {
        VillageFaction f = VillageFactionManager.getFaction(id);
        return f == null ? 0 : f.recruits.size();
    }

    public static Set<UUID> recruits(String id) {
        VillageFaction f = VillageFactionManager.getFaction(id);
        return f == null ? Set.of() : Set.copyOf(f.recruits);
    }

    /** Every village centre Village Expansion knows, with its owning faction (the Recruits claim is authoritative). */
    public static Map<BlockPos, String> villages(ServerLevel level) {
        return VillageClaimIntegration.getAllCentersWithFactions(level);
    }

    @Nullable
    public static String ownerAt(ServerLevel level, BlockPos center) {
        return VillageClaimIntegration.getFactionIdAt(level, center);
    }

    /**
     * The Recruits faction a player kingdom fights under: the ruler's own Recruits faction if they have one, otherwise
     * a Living Realms realm led by them. Its Village Expansion record makes it a full member of VE diplomacy.
     */
    public static String realm(ServerLevel level, UUID ruler, String rulerName, @Nullable String known) {
        PlayerTeam team = rulerName.isEmpty() ? null : level.getScoreboard().getPlayersTeam(rulerName);
        if (team != null && exists(team.getName()) && !isNpc(team.getName())) return register(level, team.getName());
        String id = known != null && !known.isEmpty() ? known : PREFIX + ruler.toString().replace("-", "").substring(0, 10);
        String display = (rulerName.isEmpty() ? "Unknown" : rulerName) + "'s Realm";
        team = level.getScoreboard().getPlayerTeam(id);
        if (team == null) {
            team = level.getScoreboard().addPlayerTeam(id);
            team.setDisplayName(net.minecraft.network.chat.Component.literal(display));
            team.setColor(ChatFormatting.GOLD);
            team.setAllowFriendlyFire(false);
        }
        if (!rulerName.isEmpty() && level.getScoreboard().getPlayersTeam(rulerName) == null) level.getScoreboard().addPlayerToTeam(rulerName, team);
        RecruitsFaction f = FactionEvents.recruitsFactionManager.getFactionByStringID(id);
        if (f == null) {
            CompoundTag banner = new ItemStack(Items.YELLOW_BANNER).save(new CompoundTag());
            FactionEvents.recruitsFactionManager.addTeam(id, display, ruler, rulerName, banner, (byte) 4, ChatFormatting.GOLD);
            f = FactionEvents.recruitsFactionManager.getFactionByStringID(id);
            if (f != null && f.getMembers().stream().noneMatch(m -> ruler.equals(m.getUUID()))) f.addMember(ruler, rulerName);
            FactionEvents.recruitsFactionManager.save(level.getServer().overworld());
        }
        return register(level, id);
    }

    /** A Recruits faction for an independent Living Realms kingdom (legacy NPC kingdoms; no Village Expansion record). */
    public static String npcRealm(ServerLevel level, UUID kingdom, String display) {
        String id = "lrk_" + kingdom.toString().replace("-", "").substring(0, 10);
        if (level.getScoreboard().getPlayerTeam(id) == null) {
            PlayerTeam team = level.getScoreboard().addPlayerTeam(id);
            team.setDisplayName(net.minecraft.network.chat.Component.literal(display));
        }
        if (!exists(id)) {
            FactionEvents.recruitsFactionManager.addTeam(id, display, kingdom, display, new ItemStack(Items.WHITE_BANNER).save(new CompoundTag()), (byte) 0, ChatFormatting.GRAY);
            FactionEvents.recruitsFactionManager.save(level.getServer().overworld());
        }
        return id;
    }

    private static String register(ServerLevel level, String id) {
        if (VillageFactionManager.getFaction(id) == null) {
            VillageFactionManager.getOrCreate(id);
            VillageFactionSaveData.get(level.getServer().overworld()).setDirty();
        }
        return id;
    }

    /** Puts a soldier in the realm's scoreboard team, which is how Recruits and Village Expansion tell friend from foe. */
    public static void enlist(ServerLevel level, Entity e, String id) {
        PlayerTeam team = level.getScoreboard().getPlayerTeam(id);
        if (team == null || e.getTeam() == team) return;
        level.getScoreboard().addPlayerToTeam(e.getStringUUID(), team);
    }

    /** A captured town's soldiers go over to the winning village faction. */
    public static void surrender(ServerLevel level, Entity e, String id, BlockPos center) {
        e.stopRiding();
        if (e instanceof com.talhanation.recruits.entities.AbstractRecruitEntity r) {
            r.setOwnerUUID(Optional.of(VillageFactionManager.FAKE_OWNER));
            r.setIsOwned(true);
            r.setTarget(null);
        }
        e.getPersistentData().putString("VillageFactionID", id);
        enlist(level, e, id);
        VillageFaction f = VillageFactionManager.getFaction(id);
        if (f != null) {
            f.addRecruit(e.getUUID());
            VillageFactionSaveData.get(level.getServer().overworld()).setDirty();
        }
    }

    public static int population(String id, BlockPos center) {
        VillageFaction f = VillageFactionManager.getFaction(id);
        return f == null ? 0 : f.getLedgerPop(center);
    }

    // ------------------------------------------------------------------ diplomacy

    public static String relation(String a, String b) {
        if (!ready() || a.equals(b)) return "NEUTRAL";
        DiplomacyStatus s = FactionEvents.recruitsDiplomacyManager.getRelation(a, b), t = FactionEvents.recruitsDiplomacyManager.getRelation(b, a);
        if (s == DiplomacyStatus.ENEMY || t == DiplomacyStatus.ENEMY) return "ENEMY";
        return s == DiplomacyStatus.ALLY && t == DiplomacyStatus.ALLY ? "ALLY" : "NEUTRAL";
    }

    public static boolean atWar(String a, String b) { return "ENEMY".equals(relation(a, b)); }

    public static void declareWar(ServerLevel level, String a, String b) {
        VRDiplomacy.setSymmetric(FactionEvents.recruitsDiplomacyManager, a, b, DiplomacyStatus.ENEMY, level);
        VillageAutoDiplomacy.onWarDeclared(level, a, b);
        AssaultManager.recordAttack(a, b, level.getGameTime());
    }

    public static void assign(Entity soldier, net.minecraft.world.entity.LivingEntity enemy) {
        if (soldier instanceof com.talhanation.recruits.entities.AbstractRecruitEntity r)
            com.example.villagerecruits.events.RecruitTargetStickiness.noteGroupAssignment(r, enemy);
    }

    /** Enemies without a declaration of war, for raiders and other armed bands. */
    public static void hostile(ServerLevel level, String a, String b) {
        VRDiplomacy.setSymmetric(FactionEvents.recruitsDiplomacyManager, a, b, DiplomacyStatus.ENEMY, level);
    }

    public static void makePeace(ServerLevel level, String a, String b, long freezeTicks) {
        VRDiplomacy.setSymmetric(FactionEvents.recruitsDiplomacyManager, a, b, DiplomacyStatus.NEUTRAL, level);
        VillageAutoDiplomacy.freezePair(a, b, level.getGameTime() + freezeTicks);
    }

    /** Village Expansion's own view on whether that faction would agree to peace with the other one. */
    public static boolean wouldAcceptPeace(ServerLevel level, String npc, String other) {
        try { return VillageAutoDiplomacy.aiWouldAccept(level, npc, other, DiplomacyStatus.NEUTRAL); } catch (RuntimeException e) { return false; }
    }

    public static boolean peaceLocked(ServerLevel level, String a, String b) {
        return AssaultManager.isPeaceLocked(level, a, b, level.getGameTime());
    }

    public static boolean diplomacyEnabled() { return VRConfig.COMMON.DIPLOMACY_ENABLED.get(); }

    public static boolean attacksEnabled() { return VRConfig.COMMON.ATTACK_ENABLED.get(); }

    /** VE's strength ratio of a against b (0.5 = even); 0.5 when either has no assessment yet. */
    public static double strength(String a, String b) {
        try { return FactionAssessmentManager.relativeStrength(a, b); } catch (RuntimeException e) { return 0.5; }
    }

    // ------------------------------------------------------------------ villages changing hands

    /** Full Village Expansion conquest of one village centre: claim, centres, garrison, plan, civilians. */
    public static boolean conquer(ServerLevel level, BlockPos center, String newOwner, String oldOwner) {
        if (!exists(newOwner)) return false;
        return VillageFastConquest.conquerAbstract(level.getServer().overworld(), center, newOwner, oldOwner);
    }

    /** Makes a centre without a Village Expansion claim (a Living Realms city) a village of the given faction. */
    public static boolean annex(ServerLevel level, BlockPos center, String owner) {
        VillageFaction f = VillageFactionManager.getFaction(owner);
        if (f == null || !exists(owner)) return false;
        ServerLevel ow = level.getServer().overworld();
        if (VillageClaimIntegration.getClaimAt(ow, center) == null && !VillageClaimIntegration.createVillageClaimAt(ow, f, center)) return false;
        RecruitsClaim claim = VillageClaimIntegration.getClaimAt(ow, center);
        if (claim == null) return false;
        if (!owner.equals(claim.getOwnerFactionStringID())) {
            VillageClaimConquestHandler.recordOldOwner(claim.getCenter(), claim.getOwnerFactionStringID());
            VillageClaimIntegration.transferCenterClaimOwner(ow, center, owner);
        }
        BlockPos exact = new BlockPos(center.getX(), center.getY(), center.getZ());
        if (f.center == null) f.center = exact; else f.addCenterIfAbsent(exact);
        VillageFactionSaveData.get(ow).setDirty();
        VillageClaimIntegration.invalidateClaimVillageCache();
        if (ClaimEvents.recruitsClaimManager != null) ClaimEvents.recruitsClaimManager.broadcastClaimsToAll(ow);
        return true;
    }

    /** Whether Village Expansion holds a claim with this centre chunk. */
    public static boolean claimed(ServerLevel level, BlockPos center) {
        RecruitsClaim c = VillageClaimIntegration.getClaimAt(level.getServer().overworld(), center);
        return c != null && new ChunkPos(center).equals(c.getCenter());
    }

    // ------------------------------------------------------------------ city planning

    /** Village Expansion's street-and-plot layout for any centre; generate() reads terrain but stores nothing in VE. */
    public static void layout(ServerLevel level, String id, BlockPos center, int target, VxLayout out) {
        if (out.seed == 0) out.seed = CityPlanGenerator.legacySeed(center) ^ 0x4C52L;
        CityPlan plan = CityPlanGenerator.generate(level, id, center, Math.max(1, target), out.seed);
        Map<Long, VxLayout.Slot> old = new HashMap<>();
        for (VxLayout.Slot s : out.slots) old.put(BlockPos.asLong(s.x, 0, s.z), s);
        out.slots.clear();
        for (var p : plan.plots) {
            if (p.anchor == null) continue;
            VxLayout.Slot s = new VxLayout.Slot();
            s.x = p.anchor.getX(); s.z = p.anchor.getZ(); s.w = p.width; s.d = p.depth;
            s.kind = kind(p.structureKey, p.productionSubtype);
            var road = plan.roadOf(p);
            if (road != null && road.a != null && road.b != null) { s.roadA = road.a.asLong(); s.roadB = road.b.asLong(); s.primary = road.primary; }
            VxLayout.Slot was = old.get(BlockPos.asLong(s.x, 0, s.z));
            s.used = was != null && was.used;
            out.slots.add(s);
        }
        out.target = target;
    }

    /** VE zones a lot by its template: production subtype, barracks, a villager's workplace, else a house. */
    static String kind(@Nullable String key, @Nullable String production) {
        if (production != null && !production.isEmpty()) return production.toLowerCase(Locale.ROOT);
        String k = key == null ? "" : key.toLowerCase(Locale.ROOT);
        k = k.substring(Math.max(k.lastIndexOf(':'), k.lastIndexOf('/')) + 1);
        if (k.contains("barracks")) return "barracks";
        for (String job : new String[]{"armorer", "butcher", "cartographer", "fisher", "fletcher", "librar", "liberary", "mason", "shepherd",
                "tannery", "temple", "smith", "leatherworker", "animal_pen", "stable", "shop"})
            if (k.contains(job)) return "work";
        return "house";
    }

    // ------------------------------------------------------------------ self-test support

    /** Founds a Village Expansion NPC village the way its towers do: an AI faction, its centre and its claim. */
    public static String testVillage(ServerLevel level, String id, BlockPos center) {
        VillageFaction f = VillageFactionManager.getFaction(id);
        if (f == null) f = VillageFactionManager.mintAiFaction(level, id, "Village_" + id);
        if (f == null) f = VillageFactionManager.getOrCreate(id);
        if (f.center == null) { f.center = center; f.centerLocked = true; } else f.addCenterIfAbsent(center);
        VillageClaimIntegration.createVillageClaimAt(level, f, center);
        VillageClaimIntegration.invalidateClaimVillageCache();
        return id;
    }

    public static UUID fakeOwner() { return VillageFactionManager.FAKE_OWNER; }

    public static boolean registered(String id) { return VillageFactionManager.getFaction(id) != null; }

    @Nullable
    public static UUID leader(String id) {
        RecruitsFaction f = ready() ? FactionEvents.recruitsFactionManager.getFactionByStringID(id) : null;
        return f == null ? null : f.getTeamLeaderUUID();
    }

    public static List<BlockPos> centers(String id) {
        VillageFaction f = VillageFactionManager.getFaction(id);
        List<BlockPos> out = new ArrayList<>();
        if (f == null) return out;
        if (f.center != null) out.add(f.center);
        out.addAll(f.additionalCenters);
        return out;
    }

    @Nullable
    public static String team(Entity e) { return e.getTeam() == null ? null : e.getTeam().getName(); }

    private VxBridge() {}
}
