package livingrealms.compat;

import com.knightmare.ranksandtitles.data.FactionRoleSavedData;
import com.knightmare.ranksandtitles.faction.FactionRole;
import com.talhanation.recruits.world.RecruitsFaction;
import livingrealms.settlement.Settlement;
import livingrealms.settlement.SettlementManager;
import net.minecraft.nbt.ListTag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import java.util.UUID;

/** Ranks and Titles: a kingdom's ruler wears its title in the tab list. Renamed titles are left alone. */
final class RanksCompat {
    static int tick(MinecraftServer server, SettlementManager m, CompatData mine) {
        FactionRoleSavedData roles = FactionRoleSavedData.get(server.overworld());
        int changed = 0;
        for (UUID owner : VassalCompat.owners(m)) {
            RecruitsFaction faction = Compat.factionLedBy(owner);
            Compat.Rank rank = Compat.rank(m, owner);
            if (faction == null || rank == null) continue;
            String id = faction.getStringID(), cur = roles.getRoleTitle(id, FactionRole.LEADER), last = mine.ranks.get(id);
            if (!cur.equals(FactionRoleSavedData.defaultRoleTitle(FactionRole.LEADER)) && !cur.equals(last)) continue;
            mine.ranks.put(id, rank.label);
            if (cur.equals(rank.label)) continue;
            roles.setRoleTitle(id, FactionRole.LEADER, rank.label);
            changed++;
            ServerPlayer p = server.getPlayerList().getPlayer(owner);
            if (p != null) p.refreshTabListName();
        }
        return changed;
    }

    static String title(MinecraftServer server, String faction) {
        return FactionRoleSavedData.get(server.overworld()).getRoleTitle(faction, FactionRole.LEADER);
    }

    static void view(MinecraftServer server, Settlement s, ListTag lines) {
        RecruitsFaction faction = Compat.factionLedBy(s.owner);
        if (faction == null) return;
        lines.add(Compat.line("Ruler", title(server, faction.getStringID()) + " " + s.ownerName,
                "Ranks and Titles: the faction Leader title follows kingdom size.", "Rename it in the Faction Ranks screen to keep your own."));
    }

    private RanksCompat() {}
}
