package livingrealms.net;

import livingrealms.building.BuildingCatalog;
import livingrealms.building.BuildingType;
import livingrealms.settlement.*;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.server.level.ServerPlayer;

/** What the City Manager shows, built on the server for one player. */
public final class View {
    public static CompoundTag of(Settlement s, ServerPlayer p) {
        Settlement.Derived d = s.d;
        CompoundTag t = new CompoundTag();
        t.putUUID("id", s.id);
        t.putString("name", s.name);
        t.putString("castleStatus", s.castleStatus);
        SettlementManager manager = SettlementManager.get(p.server);
        t.putBoolean("capital", s.capital);
        t.putBoolean("ruler", p.getUUID().equals(s.owner));
        t.putInt("kingdomCities", Kingdoms.cities(manager, s).size());
        t.putString("raidStatus", s.raidStatus);
        t.putBoolean("autoBuild", s.autoBuild);
        t.putString("autoBuildStatus", AutoBuild.status(s));
        t.putBoolean("autoResearch", s.autoResearch);
        t.putBoolean("autoTier", s.autoTier);
        t.putBoolean("autoTrain", s.autoTrain);
        t.putInt("garrisonTarget", s.garrisonTarget);
        t.putInt("garrisonGoal", Autonomy.target(s));
        t.putInt("garrisonNow", s.soldiers.size() + s.training.size());
        t.putBoolean("researchAuto", s.researchAuto);
        t.putInt("researchSkips", s.researchSkip.size());
        String next = s.activeResearch.isEmpty() ? Autonomy.pickResearch(s) : null;
        t.putString("nextResearch", next == null ? "" : next);
        Tier up = s.tier.next();
        t.putInt("tierFunds", up == null ? 0 : Autonomy.tierFunds(up));
        t.putBoolean("canRaid", Raids.playerSource(p.server, manager, p.getUUID(), s) != null);
        t.putBoolean("canMoveCapital", p.getUUID().equals(s.owner) && !s.capital && !Raids.engaged(manager, s)
                && Kingdoms.cities(manager, s).stream().noneMatch(c -> c.capitalMovedAt > p.server.overworld().getGameTime()));
        t.putString("owner", s.isAi() ? "" : s.ownerName);
        t.putBoolean("mine", p.getUUID().equals(s.owner) || p.hasPermissions(2));
        t.putBoolean("ai", s.isAi());
        t.putString("style", s.style);
        t.putInt("tier", s.tier.number());
        t.putLong("center", s.center.asLong());
        t.putInt("radius", s.radius());
        t.putInt("pop", s.population);
        t.putInt("housing", d.housing);
        t.putDouble("happy", s.happiness);
        t.putDouble("happyTarget", d.happinessTarget);
        ListTag why = new ListTag();
        d.happinessReasons.forEach(r -> why.add(StringTag.valueOf(r)));
        t.put("happyWhy", why);
        t.putBoolean("starving", s.starving);
        t.putDouble("growth", s.growth);
        t.putInt("tax", s.taxLevel);
        t.putDouble("wealth", s.wealth);
        t.put("stock", s.stock.save());
        t.putInt("capacity", s.stock.capacity);
        ListTag plots = new ListTag();
        for (Plot pl : s.plots) plots.add(pl.toClient());
        t.put("plots", plots);
        ListTag res = new ListTag();
        s.research.forEach(r -> res.add(StringTag.valueOf(r)));
        t.put("research", res);
        t.putString("activeResearch", s.activeResearch);
        t.putDouble("researchProgress", s.researchProgress);
        t.putDouble("researchBank", s.researchBank);
        t.putDouble("researchRate", d.researchPerDay);
        CompoundTag jobs = new CompoundTag();
        for (Job j : Job.values()) {
            int sl = d.slots.getOrDefault(j, 0), w = d.workers.getOrDefault(j, 0);
            if (sl > 0 || w > 0) jobs.putIntArray(j.name(), new int[]{w, sl});
        }
        t.put("jobs", jobs);
        t.putInt("unemployed", d.unemployed);
        t.putDouble("food", d.foodPerDay);
        t.putDouble("foodNeed", d.foodNeedPerDay);
        t.putDouble("emeralds", d.emeraldsPerDay);
        t.putInt("garrisonCap", d.garrisonCap);
        t.putInt("buildings", d.buildings);
        t.putInt("barracks", d.barracks);
        t.putInt("smithy", d.smithyLevels);
        t.putBoolean("stables", d.hasStables);
        t.putBoolean("townHall", d.hasTownHall);
        ListTag soldiers = new ListTag();
        s.soldiers.forEach(so -> soldiers.add(so.save()));
        t.put("soldiers", soldiers);
        ListTag training = new ListTag();
        s.training.forEach(tr -> training.add(tr.save()));
        t.put("training", training);
        CompoundTag kits = new CompoundTag();
        for (var u : livingrealms.military.UnitType.values()) kits.put(u.name(), s.kit(u).save());
        t.put("kits", kits);
        t.putString("stance", s.stance.name());
        t.putBoolean("autoEquip", s.autoEquip);
        ListTag orders = new ListTag();
        s.orders.forEach(o -> orders.add(o.save()));
        t.put("orders", orders);
        t.putBoolean("alarm", s.alarmUntil > p.level().getGameTime());
        t.putBoolean("manualAlarm", s.manualAlarm);
        ListTag log = new ListTag();
        s.log.forEach(l -> log.add(StringTag.valueOf(l)));
        t.put("log", log);
        ListTag blockers = new ListTag();
        s.tierBlockers().forEach(b -> blockers.add(StringTag.valueOf(b)));
        t.put("blockers", blockers);
        // per-type lock reasons so the catalogue can grey things out
        CompoundTag locks = new CompoundTag();
        for (BuildingType bt : BuildingCatalog.types().values()) {
            String why2 = s.cannotBuild(bt);
            if (why2 != null) locks.putString(bt.id, why2);
        }
        t.put("locks", locks);
        CompoundTag counts = new CompoundTag();
        for (Plot pl : s.plots) counts.putInt(pl.typeId, counts.getInt(pl.typeId) + 1);
        t.put("counts", counts);
        CompoundTag plotWorkers = new CompoundTag();
        d.plotWorkers.forEach((k, v) -> plotWorkers.putInt(String.valueOf(k), v));
        t.put("plotWorkers", plotWorkers);
        CompoundTag maxLevels = new CompoundTag();
        for (Plot pl : s.plots) {
            BuildingType bt = BuildingCatalog.get(pl.typeId);
            if (bt != null) maxLevels.putInt(String.valueOf(pl.id), s.maxLevel(bt));
        }
        t.put("maxLevels", maxLevels);
        // what the next blueprint of each plot needs (for upgrade previews)
        CompoundTag bills = new CompoundTag();
        for (Plot pl : s.plots) {
            if (!pl.active() || pl.state == Plot.State.DEMOLISHING) continue;
            var bp = pl.blueprint();
            if (bp == null) continue;
            CompoundTag b = new CompoundTag();
            bp.bill().forEach((it, n) -> b.putInt(BuiltInRegistries.ITEM.getKey(it).toString(), n));
            bills.put(String.valueOf(pl.id), b);
        }
        t.put("bills", bills);
        return t;
    }

    private View() {}
}
