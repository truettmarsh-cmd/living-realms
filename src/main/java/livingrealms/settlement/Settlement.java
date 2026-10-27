package livingrealms.settlement;

import livingrealms.building.BuildingCatalog;
import livingrealms.building.BuildingType;
import livingrealms.building.Category;
import livingrealms.military.Kit;
import livingrealms.military.RecruitOrigin;
import livingrealms.military.SoldierSkills;
import livingrealms.military.WeaponProficiency;
import livingrealms.military.LegendaryPerk;
import livingrealms.military.SpecialSkill;
import livingrealms.military.Stance;
import livingrealms.military.UnitType;
import livingrealms.research.ResearchTree;
import livingrealms.smithing.ForgeOrder;
import livingrealms.smithing.Piece;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.*;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.Level;

import javax.annotation.Nullable;
import java.util.*;

public final class Settlement {
    public UUID id = UUID.randomUUID();
    public String name = "";
    public String castleStatus = "";
    public ResourceKey<Level> dim = Level.OVERWORLD;
    public BlockPos center = BlockPos.ZERO;
    @Nullable public UUID owner;
    public String ownerName = "";
    /** Independent NPC kingdoms have distinct identities; player kingdoms use their owner's UUID. */
    public UUID kingdom = id;
    public boolean capital;
    public long raidCooldown;
    public long capitalMovedAt;
    public String raidStatus = "";
    public boolean autoBuild = true;
    public String style = "minecraft:plains";
    public Tier tier = Tier.CAMP;
    public final Stockpile stock = new Stockpile();
    public final List<Plot> plots = new ArrayList<>();
    public int nextId = 1;

    public int population = 3;
    public double growth;
    public double happiness = 60;
    public boolean starving;
    /** Housing in buildings that came with a generated village. */
    public int baseHousing;
    public int taxLevel = 1;
    public double wealth;
    public final Map<String, Double> accum = new HashMap<>();

    public final Set<String> research = new LinkedHashSet<>();
    public String activeResearch = "";
    public double researchProgress;
    public double researchBank;

    public final List<Soldier> soldiers = new ArrayList<>();
    public final List<Training> training = new ArrayList<>();
    public final EnumMap<UnitType, Kit> kits = new EnumMap<>(UnitType.class);
    public Stance stance = Stance.PATROL;
    public boolean autoEquip = true;
    public final List<ForgeOrder> orders = new ArrayList<>();
    public long alarmUntil;
    public boolean manualAlarm;

    public final Deque<String> log = new ArrayDeque<>();
    public String structureKey = "";
    public long founded;
    public long lastDay;

    /** Recomputed every simulation step; not saved. */
    public transient Derived d = new Derived();
    public transient boolean dirty = true;

    public static final class Soldier {
        public UUID entity;
        public UnitType type;
        public String name;
        // last seen
        public float health, maxHealth, morale;
        public int level, kills;
        public String gear = "";
        public boolean seen;
        public boolean royalGuard;
        public int skillXp;
        public RecruitOrigin origin = RecruitOrigin.HUMAN;
        public final Set<SoldierSkills> skills = EnumSet.noneOf(SoldierSkills.class);
        public WeaponProficiency preferredWeapon;
        public final Map<WeaponProficiency, Integer> weaponXp = new EnumMap<>(WeaponProficiency.class);
        public final Set<LegendaryPerk> legendary = EnumSet.noneOf(LegendaryPerk.class);
        public int legendaryLevelChecked;
        public final Map<LegendaryPerk, Long> legendaryCooldowns = new EnumMap<>(LegendaryPerk.class);
        public long battleRhythmUntil;
        public long drillReadyAt, trickleDay;
        public int trickleXp;
        public float damageBank;
        public int legendaryPity;
        public final List<SpecialSkill> specials = new ArrayList<>();
        public final Map<SpecialSkill, Long> specialCooldowns = new EnumMap<>(SpecialSkill.class);
        public long braceUntil, rallyUntil, chargeUntil;

        public CompoundTag save() {
            CompoundTag t = new CompoundTag();
            t.putUUID("e", entity);
            t.putString("type", type.name());
            t.putString("name", name);
            t.putFloat("hp", health);
            t.putFloat("max", maxHealth);
            t.putFloat("morale", morale);
            t.putInt("lvl", level);
            t.putInt("kills", kills);
            t.putString("gear", gear);
            t.putBoolean("seen", seen);
            t.putBoolean("royalGuard", royalGuard);
            t.putInt("skillXp", skillXp);
            t.putString("origin", origin.name());
            ListTag learned = new ListTag();
            skills.forEach(skill -> learned.add(StringTag.valueOf(skill.name())));
            t.put("skills", learned);
            t.putString("preferredWeapon", preferredWeapon == null ? "" : preferredWeapon.name());
            CompoundTag practice = new CompoundTag();
            weaponXp.forEach((weapon, xp) -> practice.putInt(weapon.name(), xp));
            t.put("weaponXp", practice);
            ListTag legends = new ListTag();
            legendary.forEach(perk -> legends.add(StringTag.valueOf(perk.name())));
            t.put("legendary", legends);
            t.putInt("legendaryLevelChecked", legendaryLevelChecked);
            CompoundTag cooldowns = new CompoundTag();
            legendaryCooldowns.forEach((perk, ready) -> cooldowns.putLong(perk.name(), ready));
            t.put("legendaryCooldowns", cooldowns);
            t.putLong("battleRhythmUntil", battleRhythmUntil);
            t.putLong("drillReadyAt", drillReadyAt);
            t.putLong("trickleDay", trickleDay);
            t.putInt("trickleXp", trickleXp);
            t.putInt("legendaryPity", legendaryPity);
            ListTag equipped = new ListTag();
            specials.forEach(sp -> equipped.add(StringTag.valueOf(sp.name())));
            t.put("specials", equipped);
            CompoundTag specialReady = new CompoundTag();
            specialCooldowns.forEach((sp, ready) -> specialReady.putLong(sp.name(), ready));
            t.put("specialCooldowns", specialReady);
            t.putLong("braceUntil", braceUntil); t.putLong("rallyUntil", rallyUntil); t.putLong("chargeUntil", chargeUntil);
            return t;
        }

        public static Soldier load(CompoundTag t) {
            Soldier s = new Soldier();
            s.entity = t.getUUID("e");
            s.type = UnitType.valueOf(t.getString("type"));
            s.name = t.getString("name");
            s.health = t.getFloat("hp");
            s.maxHealth = t.getFloat("max");
            s.morale = t.getFloat("morale");
            s.level = t.getInt("lvl");
            s.kills = t.getInt("kills");
            s.gear = t.getString("gear");
            s.seen = t.getBoolean("seen");
            s.royalGuard = t.getBoolean("royalGuard");
            s.origin = RecruitOrigin.byId(t.getString("origin"));
            s.skillXp = Math.max(0, Math.min(SoldierSkills.MAX_POINTS * SoldierSkills.XP_PER_POINT, t.getInt("skillXp")));
            for (Tag value : t.getList("skills", Tag.TAG_STRING)) {
                SoldierSkills skill = SoldierSkills.byId(value.getAsString());
                if (skill != null) s.skills.add(skill);
            }
            s.preferredWeapon = WeaponProficiency.byId(t.getString("preferredWeapon"));
            CompoundTag practice = t.getCompound("weaponXp");
            for (WeaponProficiency weapon : WeaponProficiency.values())
                if (practice.contains(weapon.name())) s.weaponXp.put(weapon, Math.max(0, Math.min(WeaponProficiency.MAX_XP, practice.getInt(weapon.name()))));
            for (Tag entry : t.getList("legendary", Tag.TAG_STRING)) {
                LegendaryPerk perk = LegendaryPerk.byId(entry.getAsString());
                if (perk != null && s.legendary.size() < LegendaryPerk.MAX_PERKS) s.legendary.add(perk);
            }
            s.legendaryLevelChecked = Math.max(0, Math.min(100, t.getInt("legendaryLevelChecked")));
            CompoundTag cooldowns = t.getCompound("legendaryCooldowns");
            for (LegendaryPerk perk : LegendaryPerk.values()) s.legendaryCooldowns.put(perk, Math.max(0, cooldowns.getLong(perk.name())));
            s.battleRhythmUntil = Math.max(0, t.getLong("battleRhythmUntil"));
            s.drillReadyAt = Math.max(0, t.getLong("drillReadyAt"));
            s.trickleDay = Math.max(0, t.getLong("trickleDay"));
            s.trickleXp = Math.max(0, Math.min(livingrealms.military.SoldierRanks.DAILY_TRICKLE_CAP, t.getInt("trickleXp")));
            s.legendaryPity = t.contains("legendaryPity") ? Math.max(0, Math.min(LegendaryPerk.PITY_LIMIT, t.getInt("legendaryPity")))
                    : LegendaryPerk.estimatePity(s);
            for (Tag entry : t.getList("specials", Tag.TAG_STRING)) {
                SpecialSkill sp = SpecialSkill.byId(entry.getAsString());
                if (sp != null && !s.specials.contains(sp) && s.specials.size() < SpecialSkill.SLOT_RANKS.length) s.specials.add(sp);
            }
            CompoundTag specialReady = t.getCompound("specialCooldowns");
            for (SpecialSkill sp : SpecialSkill.values()) if (specialReady.contains(sp.name())) s.specialCooldowns.put(sp, Math.max(0, specialReady.getLong(sp.name())));
            s.braceUntil = Math.max(0, t.getLong("braceUntil")); s.rallyUntil = Math.max(0, t.getLong("rallyUntil")); s.chargeUntil = Math.max(0, t.getLong("chargeUntil"));
            return s;
        }
    }

    public static final class Training {
        public UnitType type;
        public float progress;
        /** Weapons reserved from the armoury, handed over at muster. */
        public final List<net.minecraft.world.item.ItemStack> gear = new ArrayList<>();

        public CompoundTag save() {
            CompoundTag t = new CompoundTag();
            t.putString("type", type.name());
            t.putFloat("p", progress);
            ListTag g = new ListTag();
            for (var s : gear) g.add(s.save(new CompoundTag()));
            t.put("gear", g);
            return t;
        }

        public static Training load(CompoundTag t) {
            Training tr = new Training();
            tr.type = UnitType.valueOf(t.getString("type"));
            tr.progress = t.getFloat("p");
            for (Tag g : t.getList("gear", Tag.TAG_COMPOUND)) tr.gear.add(net.minecraft.world.item.ItemStack.of((CompoundTag) g));
            return tr;
        }
    }

    /** Figures derived from buildings and people. */
    public static final class Derived {
        public int housing, buildings, garrisonCap, capacity, barracks, smithyLevels, libraries;
        public final EnumMap<Job, Integer> slots = new EnumMap<>(Job.class);
        public final EnumMap<Job, Integer> workers = new EnumMap<>(Job.class);
        public final Map<Integer, Integer> plotWorkers = new HashMap<>();
        public int unemployed;
        public double foodPerDay, foodNeedPerDay, researchPerDay, emeraldsPerDay, happinessTarget;
        public final List<String> happinessReasons = new ArrayList<>();
        public boolean hasTownHall, hasStables;
        public int townHallLevel;
    }

    // ------------------------------------------------------------------ helpers

    public boolean isAi() { return owner == null; }

    public UUID kingdomId() { return owner == null ? kingdom : owner; }

    public boolean has(String researchId) { return researchId == null || researchId.isEmpty() || research.contains(researchId); }

    public void log(String msg) {
        log.addFirst(msg);
        while (log.size() > 40) log.removeLast();
        dirty = true;
    }

    public int radius() { return tier.radius; }

    public boolean contains(BlockPos pos) {
        long dx = pos.getX() - center.getX(), dz = pos.getZ() - center.getZ();
        return dx * dx + dz * dz <= (long) radius() * radius();
    }

    @Nullable
    public Plot plot(int id) {
        for (Plot p : plots) if (p.id == id) return p;
        return null;
    }

    public int count(String typeId) {
        int n = 0;
        for (Plot p : plots) if (p.typeId.equals(typeId)) n++;
        return n;
    }

    public int standing(Category c) {
        int n = 0;
        for (Plot p : plots) {
            BuildingType t = BuildingCatalog.get(p.typeId);
            if (t != null && t.category == c && p.standing()) n++;
        }
        return n;
    }

    public int levels(Category c) {
        int n = 0;
        for (Plot p : plots) {
            BuildingType t = BuildingCatalog.get(p.typeId);
            if (t != null && t.category == c && p.standing()) n += p.level;
        }
        return n;
    }

    /** Why a building can't be planned here, or null. */
    @Nullable
    public String cannotBuild(BuildingType t) {
        if (t.id.equals(CapitalCastles.ID) && !capital) return "Only the kingdom capital can build the Capital Castle";
        if (t.minTier > tier.number()) return "Needs " + Tier.of(t.minTier).label;
        if (!has(t.research)) {
            var n = ResearchTree.get(t.research);
            return "Research " + (n == null ? t.research : n.name());
        }
        if (t.maxCount > 0 && count(t.id) >= t.maxCount) return "Only " + t.maxCount + " allowed";
        if (t.category != Category.CIVIC && !d.hasTownHall && t.category != Category.ROAD && !isAi() && count("livingrealms:town_hall") == 0)
            return "Plan the Town Hall first";
        return null;
    }

    public int maxLevel(BuildingType t) {
        if (t.id.equals("livingrealms:town_hall")) return Math.min(t.maxLevel, tier.number());
        return Math.min(t.maxLevel, tier.maxBuildingLevel);
    }

    @Nullable
    public String cannotResearch(String id) {
        var n = ResearchTree.get(id);
        if (n == null) return "Unknown";
        if (research.contains(id)) return "Done";
        for (String r : n.requires()) if (!research.contains(r)) {
            var rn = ResearchTree.get(r);
            return "Needs " + (rn == null ? r : rn.name());
        }
        return null;
    }

    /** Requirements still missing for the next rank, empty when it can be claimed. */
    public List<String> tierBlockers() {
        List<String> out = new ArrayList<>();
        Tier n = tier.next();
        if (n == null) return out;
        if (n == Tier.CAPITAL && !capital) out.add("Only the kingdom capital can claim Capital rank");
        if (population < n.population) out.add(population + "/" + n.population + " people");
        if (d.buildings < n.buildings) out.add(d.buildings + "/" + n.buildings + " buildings");
        if (n.research != null && !research.contains(n.research)) {
            var rn = ResearchTree.get(n.research);
            out.add("Research " + (rn == null ? n.research : rn.name()));
        }
        if (!isAi() && !d.hasTownHall) out.add("A finished Town Hall");
        if (!isAi() && stock.count(net.minecraft.world.item.Items.EMERALD) < n.emeralds) out.add(n.emeralds + " emeralds");
        return out;
    }

    public Kit kit(UnitType u) { return kits.computeIfAbsent(u, k -> defaultKit(u)); }

    static Kit defaultKit(UnitType u) {
        Kit k = new Kit();
        k.armor = "leather";
        k.weapon = switch (u) {
            case BOWMAN -> "fletching";
            case CROSSBOWMAN -> "fletching";
            default -> "iron";
        };
        k.weaponPiece = switch (u) {
            case BOWMAN -> Piece.BOW;
            case CROSSBOWMAN -> Piece.CROSSBOW;
            default -> Piece.SWORD;
        };
        k.shield = u == UnitType.SHIELDBEARER;
        return k;
    }

    // ------------------------------------------------------------------ save

    public CompoundTag save() {
        CompoundTag t = new CompoundTag();
        t.putUUID("id", id);
        t.putString("name", name);
        t.putString("castleStatus", castleStatus);
        t.putString("dim", dim.location().toString());
        t.put("center", NbtUtils.writeBlockPos(center));
        if (owner != null) t.putUUID("owner", owner);
        t.putString("ownerName", ownerName);
        t.putUUID("kingdom", kingdomId());
        t.putBoolean("capital", capital);
        t.putLong("raidCooldown", raidCooldown);
        t.putLong("capitalMovedAt", capitalMovedAt);
        t.putString("raidStatus", raidStatus);
        t.putBoolean("autoBuild", autoBuild);
        t.putString("style", style);
        t.putInt("tier", tier.number());
        t.put("stock", stock.save());
        ListTag pl = new ListTag();
        plots.forEach(p -> pl.add(p.save()));
        t.put("plots", pl);
        t.putInt("nextId", nextId);
        t.putInt("pop", population);
        t.putDouble("growth", growth);
        t.putDouble("happy", happiness);
        t.putBoolean("starving", starving);
        t.putInt("baseHousing", baseHousing);
        t.putInt("tax", taxLevel);
        t.putDouble("wealth", wealth);
        CompoundTag acc = new CompoundTag();
        accum.forEach(acc::putDouble);
        t.put("accum", acc);
        ListTag rl = new ListTag();
        research.forEach(r -> rl.add(StringTag.valueOf(r)));
        t.put("research", rl);
        t.putString("activeResearch", activeResearch);
        t.putDouble("researchProgress", researchProgress);
        t.putDouble("researchBank", researchBank);
        ListTag sl = new ListTag();
        soldiers.forEach(s -> sl.add(s.save()));
        t.put("soldiers", sl);
        ListTag tl = new ListTag();
        training.forEach(s -> tl.add(s.save()));
        t.put("training", tl);
        CompoundTag kt = new CompoundTag();
        kits.forEach((u, k) -> kt.put(u.name(), k.save()));
        t.put("kits", kt);
        t.putString("stance", stance.name());
        t.putBoolean("autoEquip", autoEquip);
        ListTag ol = new ListTag();
        orders.forEach(o -> ol.add(o.save()));
        t.put("orders", ol);
        t.putLong("alarmUntil", alarmUntil);
        t.putBoolean("manualAlarm", manualAlarm);
        ListTag lg = new ListTag();
        log.forEach(s -> lg.add(StringTag.valueOf(s)));
        t.put("log", lg);
        t.putString("structure", structureKey);
        t.putLong("founded", founded);
        t.putLong("lastDay", lastDay);
        return t;
    }

    public static Settlement load(CompoundTag t) {
        Settlement s = new Settlement();
        s.id = t.getUUID("id");
        s.name = t.getString("name");
        s.castleStatus = t.getString("castleStatus");
        s.dim = ResourceKey.create(Registries.DIMENSION, new ResourceLocation(t.getString("dim")));
        s.center = NbtUtils.readBlockPos(t.getCompound("center"));
        if (t.hasUUID("owner")) s.owner = t.getUUID("owner");
        s.ownerName = t.getString("ownerName");
        s.kingdom = t.hasUUID("kingdom") ? t.getUUID("kingdom") : s.owner == null ? s.id : s.owner;
        s.capital = t.getBoolean("capital");
        s.raidCooldown = t.getLong("raidCooldown");
        s.capitalMovedAt = t.getLong("capitalMovedAt");
        s.raidStatus = t.getString("raidStatus");
        s.autoBuild = !t.contains("autoBuild") || t.getBoolean("autoBuild");
        s.style = t.getString("style");
        s.tier = Tier.of(t.getInt("tier"));
        s.stock.load(t.getList("stock", Tag.TAG_COMPOUND));
        for (Tag p : t.getList("plots", Tag.TAG_COMPOUND)) s.plots.add(Plot.load((CompoundTag) p));
        s.nextId = t.getInt("nextId");
        s.population = t.getInt("pop");
        s.growth = t.getDouble("growth");
        s.happiness = t.getDouble("happy");
        s.starving = t.getBoolean("starving");
        s.baseHousing = t.getInt("baseHousing");
        s.taxLevel = t.getInt("tax");
        s.wealth = t.getDouble("wealth");
        CompoundTag acc = t.getCompound("accum");
        for (String k : acc.getAllKeys()) s.accum.put(k, acc.getDouble(k));
        t.getList("research", Tag.TAG_STRING).forEach(r -> s.research.add(r.getAsString()));
        s.activeResearch = t.getString("activeResearch");
        s.researchProgress = t.getDouble("researchProgress");
        s.researchBank = t.getDouble("researchBank");
        for (Tag e : t.getList("soldiers", Tag.TAG_COMPOUND)) s.soldiers.add(Soldier.load((CompoundTag) e));
        for (Tag e : t.getList("training", Tag.TAG_COMPOUND)) s.training.add(Training.load((CompoundTag) e));
        CompoundTag kt = t.getCompound("kits");
        for (String k : kt.getAllKeys()) {
            try { s.kits.put(UnitType.valueOf(k), Kit.load(kt.getCompound(k))); } catch (Exception ignored) {}
        }
        try { s.stance = Stance.valueOf(t.getString("stance")); } catch (Exception ignored) {}
        s.autoEquip = t.getBoolean("autoEquip");
        for (Tag e : t.getList("orders", Tag.TAG_COMPOUND)) {
            try { s.orders.add(ForgeOrder.load((CompoundTag) e)); } catch (Exception ignored) {}
        }
        s.alarmUntil = t.getLong("alarmUntil");
        s.manualAlarm = t.getBoolean("manualAlarm");
        t.getList("log", Tag.TAG_STRING).forEach(e -> s.log.addLast(e.getAsString()));
        s.structureKey = t.getString("structure");
        s.founded = t.getLong("founded");
        s.lastDay = t.getLong("lastDay");
        return s;
    }
}
