package livingrealms.compat;

import de.maxhenkel.corpse.entities.CorpseEntity;
import livingrealms.settlement.Raids;
import livingrealms.settlement.Settlement;
import livingrealms.settlement.SettlementManager;
import livingrealms.settlement.Stockpile;
import net.minecraft.core.BlockPos;
import net.minecraft.core.NonNullList;
import net.minecraft.nbt.ListTag;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.AABB;
import java.util.*;

/**
 * Corpse: Recruits leaves a corpse holding a fallen soldier's kit. Once the fight in their town is over, the town's
 * armoury takes back the fresh gear and the body is carried to the town centre. Worn gear stays on the body, as the
 * stockpile only takes unused items. A soldier who falls abroad is noted in the chronicle with where the body lies.
 */
final class CorpseCompat {
    record Fallen(UUID settlement, String name, ResourceKey<Level> dim, BlockPos pos, long time) {}
    static final List<Fallen> PENDING = new ArrayList<>();
    static final long SETTLE = 40, GIVE_UP = 12000;

    static void fallen(Settlement s, String name, ResourceKey<Level> dim, BlockPos pos, long now) {
        PENDING.add(new Fallen(s.id, name, dim, pos, now));
    }

    /** Returns the number of items recovered this pass. */
    static int tick(MinecraftServer server) {
        int recovered = 0;
        long now = server.overworld().getGameTime();
        SettlementManager m = SettlementManager.get(server);
        for (Fallen f : List.copyOf(PENDING)) {
            Settlement s = m.get(f.settlement);
            ServerLevel level = server.getLevel(f.dim);
            if (s == null || level == null || now - f.time > GIVE_UP) { PENDING.remove(f); continue; }
            if (now - f.time < SETTLE || !level.hasChunkAt(f.pos)) continue;
            boolean home = s.dim.equals(f.dim) && s.contains(f.pos);
            if (home && (s.alarmUntil > now || Raids.engaged(m, s))) continue;
            CorpseEntity body = level.getEntitiesOfClass(CorpseEntity.class, new AABB(f.pos).inflate(4),
                    c -> c.isAlive() && f.name.equals(c.getCorpseName())).stream().findFirst().orElse(null);
            PENDING.remove(f);
            if (body == null) continue;
            if (!home) { s.log(f.name + "'s body lies at " + f.pos.getX() + ", " + f.pos.getZ() + " with their kit"); continue; }
            recovered += salvage(server, s, body);
        }
        return recovered;
    }

    static int salvage(MinecraftServer server, Settlement s, CorpseEntity body) {
        var death = body.getDeath();
        int n = 0;
        for (NonNullList<ItemStack> list : List.of(death.getMainInventory(), death.getArmorInventory(), death.getOffHandInventory(), death.getAdditionalItems()))
            for (int i = 0; i < list.size(); i++) {
                ItemStack st = list.get(i);
                if (!Stockpile.canStore(st) || s.stock.free() < st.getCount()) continue;
                n += s.stock.add(st.getItem(), st.getCount());
                list.set(i, ItemStack.EMPTY);
            }
        if (n > 0) CompatData.get(server).salvaged.merge(s.id, n, Integer::sum);
        if (body.isEmpty()) {
            body.discard();
            s.log("The armoury recovered " + n + " items from " + body.getCorpseName() + "'s body");
        } else {
            BlockPos c = s.center;
            body.teleportTo(c.getX() + .5, body.level().getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, c.getX(), c.getZ()), c.getZ() + .5);
            s.log(body.getCorpseName() + " was carried to the town centre" + (n > 0 ? "; " + n + " fresh items went to the armoury" : ""));
        }
        s.dirty = true;
        return n;
    }

    static void view(MinecraftServer server, Settlement s, ListTag lines) {
        if (s.isAi()) return;
        lines.add(Compat.line("Gear", CompatData.get(server).salvaged.getOrDefault(s.id, 0) + " recovered",
                "Corpse: after a fight, fresh gear on your fallen soldiers returns to the armoury.",
                "Their bodies, with any worn gear, are laid at the town centre."));
    }

    private CorpseCompat() {}
}
