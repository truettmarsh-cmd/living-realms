package livingrealms.compat;

import livingrealms.military.RecruitsBridge;
import livingrealms.settlement.Job;
import livingrealms.settlement.Settlement;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.ListTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.phys.AABB;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/** Workers: your Workers-mod workers standing in your town take its matching jobs before townsfolk do. */
final class WorkersCompat {
    static final Map<String, Job> JOBS = Map.of("farmer", Job.FARMER, "animal_farmer", Job.FARMER, "fisherman", Job.FARMER,
            "lumberjack", Job.LUMBERJACK, "miner", Job.MINER, "builder", Job.BUILDER, "merchant", Job.MERCHANT,
            "courier", Job.HAULER, "cook", Job.INNKEEPER);
    static final Map<UUID, EnumMap<Job, Integer>> STAFF = new ConcurrentHashMap<>();

    static Job job(Entity e) {
        var id = BuiltInRegistries.ENTITY_TYPE.getKey(e.getType());
        return id.getNamespace().equals(Compat.WORKERS) ? JOBS.get(id.getPath()) : null;
    }

    /** Counts the owner's workers inside the town. Returns the total. */
    static int scan(ServerLevel level, Settlement s) {
        if (s.isAi()) { STAFF.remove(s.id); return 0; }
        int r = s.radius();
        EnumMap<Job, Integer> found = new EnumMap<>(Job.class);
        for (Mob m : level.getEntitiesOfClass(Mob.class, new AABB(s.center).inflate(r, 48, r),
                m -> m.isAlive() && job(m) != null && RecruitsBridge.ownedBy(m, s.owner) && s.contains(m.blockPosition())))
            found.merge(job(m), 1, Integer::sum);
        var old = STAFF.put(s.id, found);
        if (!found.equals(old == null ? Map.of() : old)) s.dirty = true;
        return found.values().stream().mapToInt(Integer::intValue).sum();
    }

    static void staff(Settlement s, Settlement.Derived d) {
        var found = STAFF.get(s.id);
        if (found == null) return;
        found.forEach((j, n) -> {
            int room = d.slots.getOrDefault(j, 0) - d.workers.getOrDefault(j, 0);
            if (room > 0) d.workers.merge(j, Math.min(room, n), Integer::sum);
        });
    }

    static void view(Settlement s, ListTag lines) {
        var found = STAFF.get(s.id);
        if (s.isAi()) return;
        int n = found == null ? 0 : found.values().stream().mapToInt(Integer::intValue).sum();
        List<String> tip = new ArrayList<>(List.of("Your Workers-mod workers inside the town fill its matching jobs",
                "(farms, lumber camps, mines, builders, markets, inns), freeing townsfolk."));
        if (found != null) found.forEach((j, c) -> tip.add(c + " × " + j.label));
        lines.add(Compat.line("Workers", n + " on staff", tip.toArray(new String[0])));
    }

    private WorkersCompat() {}
}
