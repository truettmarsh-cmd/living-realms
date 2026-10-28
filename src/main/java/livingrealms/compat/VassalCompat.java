package livingrealms.compat;

import com.knightmare.vassalsuzerain.data.FeudalSavedData;
import com.knightmare.vassalsuzerain.data.FeudalTitle;
import com.knightmare.vassalsuzerain.logic.FeudalService;
import com.talhanation.recruits.FactionEvents;
import com.talhanation.recruits.world.RecruitsFaction;
import livingrealms.settlement.Settlement;
import livingrealms.settlement.SettlementManager;
import net.minecraft.nbt.ListTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import java.util.*;

/** Vassal & Suzerain: feudal titles follow kingdom size, and oaths bind Living Realms raids. */
final class VassalCompat {
    static FeudalSavedData data(MinecraftServer server) { return FeudalSavedData.get(server.overworld()); }

    /** Sets the title of each player kingdom's faction; a title someone changed by hand is no longer managed. */
    static int tick(MinecraftServer server, SettlementManager m, CompatData mine) {
        FeudalSavedData f = data(server);
        int changed = 0;
        for (UUID owner : owners(m)) {
            RecruitsFaction faction = Compat.factionLedBy(owner);
            Compat.Rank rank = Compat.rank(m, owner);
            if (faction == null || rank == null) continue;
            String id = faction.getStringID(), last = mine.feudal.get(id);
            boolean set = f.getTitlesView().containsKey(id);
            FeudalTitle cur = f.getTitle(id), want = allowed(f, id, FeudalTitle.valueOf(rank.name()));
            if (want == null || set && !cur.name().equals(last)) continue;
            if (!set || cur != want) { f.setTitle(id, want); f.setDirty(); changed++; }
            mine.feudal.put(id, want.name());
        }
        return changed;
    }

    /** A vassal's title sits just below its liege's; a liege must still outrank every vassal. */
    static FeudalTitle allowed(FeudalSavedData f, String id, FeudalTitle want) {
        String liege = f.getSuzerain(id);
        if (liege != null) {
            FeudalTitle over = f.getTitle(liege);
            want = Arrays.stream(FeudalTitle.values()).filter(over::canRuleOver).findFirst().orElse(null);
            if (want == null) return null;
        }
        for (String v : f.getDirectVassals(id)) if (f.getTitlesView().containsKey(v) && !want.canRuleOver(f.getTitle(v))) return null;
        return want;
    }

    static Set<UUID> owners(SettlementManager m) {
        Set<UUID> out = new LinkedHashSet<>();
        for (Settlement s : m.all()) if (!s.isAi()) out.add(s.owner);
        return out;
    }

    static String blocked(MinecraftServer server, UUID attacker, UUID defender) {
        RecruitsFaction a = Compat.factionLedBy(attacker), d = Compat.factionLedBy(defender);
        if (a == null || d == null) return null;
        FeudalSavedData f = data(server);
        if (FeudalService.areDirectLiegeAndVassal(f, a.getStringID(), d.getStringID())
                || FeudalService.areDirectLiegeAndVassal(f, d.getStringID(), a.getStringID()))
            return "A feudal oath binds your kingdoms (Vassal & Suzerain); release it before raiding";
        return null;
    }

    /** A protecting liege hears when a vassal's city is raided. */
    static void warnLiege(MinecraftServer server, Settlement source, Settlement target) {
        RecruitsFaction vassal = Compat.factionLedBy(target.owner);
        if (vassal == null) return;
        String liegeId = data(server).getSuzerain(vassal.getStringID());
        RecruitsFaction liege = liegeId == null || FactionEvents.recruitsFactionManager == null ? null
                : FactionEvents.recruitsFactionManager.getFactionByStringID(liegeId);
        if (liege == null || liege.getTeamLeaderUUID() == null) return;
        String msg = "Your vassal " + vassal.getTeamDisplayName() + " is raided: " + source.name + " marches on " + target.name + ".";
        ServerPlayer p = server.getPlayerList().getPlayer(liege.getTeamLeaderUUID());
        if (p != null) p.sendSystemMessage(Component.literal(msg));
        SettlementManager.get(server).ownedBy(liege.getTeamLeaderUUID()).stream().filter(s -> s.capital).forEach(s -> s.log(msg));
    }

    static void view(MinecraftServer server, Settlement s, Compat.Rank rank, ListTag lines) {
        RecruitsFaction faction = Compat.factionLedBy(s.owner);
        if (faction == null) {
            lines.add(Compat.line("Title", "none", "Vassal & Suzerain works on Recruits factions.", "Found a faction to hold the title of " + rank.label + "."));
            return;
        }
        FeudalSavedData f = data(server);
        String id = faction.getStringID(), liege = f.getSuzerain(id);
        int vassals = f.getDirectVassals(id).size();
        String title = f.getTitle(id).name().charAt(0) + f.getTitle(id).name().substring(1).toLowerCase(Locale.ROOT);
        lines.add(Compat.line("Title", title + (vassals > 0 ? " · " + vassals + " vassal" + (vassals == 1 ? "" : "s") : ""),
                "Vassal & Suzerain title of " + faction.getTeamDisplayName(),
                "Kingdom size suggests " + rank.label + " (3 cities or a Capital: King; 2 cities or a City: Duke).",
                liege == null ? "No liege." : "Liege: " + liege + ". A vassal sits one rank below its liege.",
                "Liege and vassal kingdoms cannot raid each other."));
    }

    private VassalCompat() {}
}
