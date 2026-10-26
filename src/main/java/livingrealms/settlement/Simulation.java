package livingrealms.settlement;

import livingrealms.Config;
import livingrealms.building.*;
import livingrealms.entity.SettlerEntity;
import livingrealms.military.*;
import livingrealms.research.ResearchTree;
import livingrealms.smithing.ForgeOrder;
import livingrealms.smithing.Piece;
import livingrealms.smithing.Smithing;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.food.FoodProperties;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.AABB;

import java.util.*;

/** The settlement's life: economy, food, happiness, people, research, the forge, training and its builders. */
public final class Simulation {

    // ------------------------------------------------------------------ derived figures

    public static void recompute(Settlement s) {
        Settlement.Derived d = new Settlement.Derived();
        d.housing = s.baseHousing;
        d.capacity = 2000;
        d.garrisonCap = 4;
        for (Plot p : s.plots) {
            if (!p.standing()) continue;
            BuildingType t = BuildingCatalog.get(p.typeId);
            if (t == null) continue;
            Blueprint bp = p.blueprint();
            int beds = bp == null ? 0 : bp.beds;
            boolean levelBonus = !t.procedural();
            if (t.category != Category.ROAD) d.buildings++;
            switch (t.category) {
                // a bed sleeps two, plus a child or lodger per household
                case HOUSING -> d.housing += Math.max(3, beds * 2 + 1) + (levelBonus ? (p.level - 1) * 2 : 0);
                case CIVIC -> {
                    d.housing += beds * 2;
                    d.hasTownHall = true;
                    d.townHallLevel = Math.max(d.townHallLevel, p.level);
                    d.slots.merge(Job.BUILDER, 2, Integer::sum);
                }
                case TAVERN -> d.housing += beds;
                case STORAGE -> d.capacity += 3000 * p.level;
                case BARRACKS -> { d.garrisonCap += 6 * p.level; d.barracks += p.level; }
                case TOWER -> d.garrisonCap += 2 * p.level;
                case WALL -> d.garrisonCap += 1;
                case SMITHY -> d.smithyLevels += p.level;
                case LIBRARY -> d.libraries += p.level;
                case STABLE -> d.hasStables = true;
                default -> {}
            }
            if (t.category.job != null) d.slots.merge(t.category.job, t.category.slotsPerLevel * p.level, Integer::sum);
        }
        // until the Town Hall stands, the founding crew works from the charter
        if (!d.hasTownHall) d.slots.merge(Job.BUILDER, 2, Integer::sum);
        if (s.isAi()) d.hasTownHall = true;
        if (s.has("granaries")) d.capacity = d.capacity * 3 / 2;
        if (s.has("command")) d.garrisonCap = d.garrisonCap * 3 / 2;
        s.stock.capacity = d.capacity;

        // jobs: builders first while there's building to do, then food, then the rest
        boolean building = s.plots.stream().anyMatch(Plot::active);
        boolean hungry = s.starving || s.stock.count(Items.BREAD) + s.stock.count(Items.CARROT) + s.stock.count(Items.POTATO) < s.population * 4;
        List<Job> order = new ArrayList<>();
        if (hungry) order.add(Job.FARMER);
        if (building) order.add(Job.BUILDER);
        order.addAll(List.of(Job.FARMER, Job.SMITH, Job.SCHOLAR, Job.LUMBERJACK, Job.MINER, Job.MERCHANT, Job.CRAFTSMAN,
                Job.HAULER, Job.INNKEEPER, Job.PRIEST, Job.STABLEHAND, Job.BUILDER));
        int free = s.population;
        for (Job j : order) {
            int want = d.slots.getOrDefault(j, 0) - d.workers.getOrDefault(j, 0);
            int take = Math.max(0, Math.min(want, free));
            if (take > 0) { d.workers.merge(j, take, Integer::sum); free -= take; }
        }
        d.unemployed = free;
        // spread workers over their buildings
        EnumMap<Job, Integer> left = new EnumMap<>(d.workers);
        for (Plot p : s.plots) {
            if (!p.standing()) continue;
            BuildingType t = BuildingCatalog.get(p.typeId);
            if (t == null || t.category.job == null) continue;
            int slots = t.category.slotsPerLevel * p.level, have = left.getOrDefault(t.category.job, 0);
            int n = Math.min(slots, have);
            if (n > 0) { d.plotWorkers.put(p.id, n); left.put(t.category.job, have - n); }
        }
        d.foodNeedPerDay = s.population * 4 + s.soldiers.size() * 5 * (s.has("logistics") ? 0.7 : 1.0);
        d.researchPerDay = researchRate(s, d);
        d.emeraldsPerDay = s.population * TAX[s.taxLevel] + d.workers.getOrDefault(Job.MERCHANT, 0) * 3.0 * (s.has("banking") ? 1.5 : 1);
        double food = 0;
        for (Plot p : s.plots) {
            BuildingType t = BuildingCatalog.get(p.typeId);
            if (t == null || !p.standing() || t.category != Category.FARM) continue;
            food += d.plotWorkers.getOrDefault(p.id, 0) * levelMult(p.level) * (4 * 5 + 6 * 3 + 6 * 1) * (s.has("agriculture") ? 1.25 : 1);
        }
        d.foodPerDay = food;
        happinessTarget(s, d);
        s.d = d;
    }

    static final double[] TAX = {0, 0.25, 0.5, 1.0};
    static final int[] TAX_MOOD = {6, 0, -8, -20};
    public static final String[] TAX_NAMES = {"None", "Low", "Normal", "High"};

    static double levelMult(int level) { return 1 + (level - 1) * 0.6; }

    static double researchRate(Settlement s, Settlement.Derived d) {
        double base = d.hasTownHall ? 20 * (1 + (s.tier.number() - 1) * 0.25) : 0;
        double scholars = 0;
        for (Plot p : s.plots) {
            BuildingType t = BuildingCatalog.get(p.typeId);
            if (t != null && p.standing() && t.category == Category.LIBRARY) scholars += d.plotWorkers.getOrDefault(p.id, 0) * 25 * levelMult(p.level);
        }
        return (base + scholars) * (s.has("education") ? 1.5 : 1);
    }

    static void happinessTarget(Settlement s, Settlement.Derived d) {
        double h = 50;
        List<String> why = d.happinessReasons;
        int chapel = s.levels(Category.CHAPEL) * 6, tavern = s.levels(Category.TAVERN) * 5, decor = Math.min(10, s.standing(Category.DECOR) * 2);
        if (chapel > 0) { h += chapel; why.add("+" + chapel + " faith"); }
        if (tavern > 0) { h += tavern; why.add("+" + tavern + " taverns"); }
        if (decor > 0) { h += decor; why.add("+" + decor + " gardens and wells"); }
        int variety = 0;
        for (var e : s.stock.view().object2IntEntrySet()) if (e.getIntValue() > 0 && e.getKey().getFoodProperties() != null) variety++;
        if (variety >= 4) { h += 6; why.add("+6 varied food"); }
        int tax = TAX_MOOD[s.taxLevel];
        if (tax != 0) { h += tax; why.add((tax > 0 ? "+" : "") + tax + " taxes"); }
        if (s.starving) { h -= 30; why.add("-30 starving"); }
        if (s.population > d.housing) { h -= 12; why.add("-12 overcrowded"); }
        if (s.alarmUntil > 0) { h -= 5; why.add("-5 under attack"); }
        d.happinessTarget = Math.max(0, Math.min(100, h));
    }

    // ------------------------------------------------------------------ the slow step

    public static void step(MinecraftServer server, Settlement s) {
        ServerLevel level = server.getLevel(s.dim);
        if (level == null) return;
        recompute(s);
        Settlement.Derived d = s.d;
        double f = Config.SIM_INTERVAL.get() / (double) Config.DAY_LENGTH.get();
        boolean loaded = level.hasChunkAt(s.center);

        produce(s, d, f);
        eat(s, d, f);
        // taxes and trade
        if (s.isAi()) s.wealth = Math.min(5000, s.wealth + (s.population * 0.8 + d.workers.getOrDefault(Job.MERCHANT, 0) * 4 + 6) * f * 20);
        else gain(s, Items.EMERALD, d.emeraldsPerDay * f);
        // mood and people
        s.happiness += Math.signum(d.happinessTarget - s.happiness) * Math.min(Math.abs(d.happinessTarget - s.happiness), 40 * f);
        int housing = d.housing;
        if (!s.starving && s.happiness >= 45 && s.population < housing) {
            s.growth += (1.5 + s.levels(Category.TAVERN) * 0.5) * f;
        } else if (s.starving || s.happiness < 25) {
            s.growth -= 0.8 * f;
        }
        if (s.growth >= 1) {
            s.growth = 0;
            s.population++;
            if (!s.isAi()) s.log(pick(level, "A new family has moved in", "Settlers arrived from the road", "A child was born"));
        } else if (s.growth <= -1) {
            s.growth = 0;
            if (s.population > 1) {
                s.population--;
                if (!s.isAi()) s.log(s.starving ? "A family left to find food elsewhere" : "Unhappy settlers packed up and left");
            }
        }
        research(s, d, f);
        forge(s, d, f);
        training(level, s, d, f);
        offscreenBuild(level, s, d);
        if (s.isAi()) {
            AiPlanner.think(level, s);
        }
        if (loaded) {
            settlers(level, s, d);
            garrison(level, s, true);
            if (s.isAi() && Config.AI_GARRISONS.get()) aiGarrison(level, s);
        }
        long day = level.getDayTime() / 24000;
        if (day != s.lastDay) s.lastDay = day;
        s.dirty = true;
    }

    static String pick(ServerLevel level, String... options) { return options[level.random.nextInt(options.length)]; }

    /** Adds fractional amounts, carrying the remainder to the next step. */
    static void gain(Settlement s, Item item, double amount) {
        if (amount <= 0) return;
        String key = BuiltInRegistries.ITEM.getKey(item).toString();
        double v = s.accum.getOrDefault(key, 0.0) + amount;
        int whole = (int) v;
        if (whole > 0) s.stock.add(item, whole);
        s.accum.put(key, v - whole);
    }

    static void produce(Settlement s, Settlement.Derived d, double f) {
        if (s.isAi()) return;
        for (Plot p : s.plots) {
            if (!p.standing()) continue;
            BuildingType t = BuildingCatalog.get(p.typeId);
            int w = d.plotWorkers.getOrDefault(p.id, 0);
            if (t == null || w == 0) continue;
            double k = w * levelMult(p.level) * f;
            switch (t.category) {
                case FARM -> {
                    double a = s.has("agriculture") ? 1.25 : 1;
                    gain(s, Items.WHEAT, 8 * k * a);
                    gain(s, Items.BREAD, 4 * k * a);
                    gain(s, Items.CARROT, 6 * k * a);
                    gain(s, Items.POTATO, 6 * k * a);
                    gain(s, Items.WHEAT_SEEDS, 2 * k);
                }
                case LUMBER -> {
                    Item log = switch (Procedural.styleFamily(s.style)) {
                        case "taiga", "snowy" -> Items.SPRUCE_LOG;
                        case "savanna" -> Items.ACACIA_LOG;
                        case "desert" -> Items.JUNGLE_LOG;
                        default -> Items.OAK_LOG;
                    };
                    Item planks = switch (Procedural.styleFamily(s.style)) {
                        case "taiga", "snowy" -> Items.SPRUCE_PLANKS;
                        case "savanna" -> Items.ACACIA_PLANKS;
                        case "desert" -> Items.JUNGLE_PLANKS;
                        default -> Items.OAK_PLANKS;
                    };
                    double a = s.has("forestry") ? 1.5 : 1;
                    gain(s, log, 12 * k * a);
                    gain(s, planks, 32 * k * a);
                    gain(s, Items.STICK, 8 * k);
                    gain(s, Items.OAK_PLANKS, planks == Items.OAK_PLANKS ? 0 : 8 * k);
                }
                case MINE -> {
                    gain(s, Items.COBBLESTONE, 40 * k);
                    gain(s, Items.COAL, 8 * k);
                    gain(s, Items.RAW_IRON, 5 * k);
                    gain(s, Items.RAW_COPPER, 3 * k);
                    gain(s, Items.FLINT, 2 * k);
                    if (s.has("deep_mining")) {
                        gain(s, Items.RAW_GOLD, 2 * k);
                        gain(s, Items.REDSTONE, 3 * k);
                        gain(s, Items.LAPIS_LAZULI, 2 * k);
                        gain(s, Items.DIAMOND, 0.25 * k);
                        gain(s, Items.COBBLED_DEEPSLATE, 16 * k);
                    }
                    if (s.has("netherite_forging")) gain(s, Items.NETHERITE_SCRAP, 0.05 * k);
                }
                case SMITHY -> {
                    smelt(s, Items.RAW_IRON, Items.IRON_INGOT, 16 * k);
                    smelt(s, Items.RAW_GOLD, Items.GOLD_INGOT, 8 * k);
                    smelt(s, Items.RAW_COPPER, Items.COPPER_INGOT, 16 * k);
                    smelt(s, Items.COBBLESTONE, Items.STONE, 8 * k);
                    if (s.has("steelmaking")) {
                        Item steel = BuiltInRegistries.ITEM.get(new ResourceLocation("magistuarmory", "steel_ingot"));
                        if (steel != Items.AIR && s.stock.has(Items.COAL, 2)) {
                            double before = s.accum.getOrDefault("steel", 0.0) + 4 * k;
                            int n = Math.min((int) before, Math.min(s.stock.count(Items.IRON_INGOT), s.stock.count(Items.COAL) / 2));
                            if (n > 0) { s.stock.take(Items.IRON_INGOT, n); s.stock.take(Items.COAL, n * 2); s.stock.add(steel, n); }
                            s.accum.put("steel", before - (int) before);
                        }
                    }
                    if (s.has("netherite_forging") && s.stock.count(Items.NETHERITE_SCRAP) >= 4 && s.stock.count(Items.GOLD_INGOT) >= 4) {
                        s.stock.take(Items.NETHERITE_SCRAP, 4);
                        s.stock.take(Items.GOLD_INGOT, 4);
                        s.stock.add(Items.NETHERITE_INGOT, 1);
                    }
                }
                case MARKET -> {} // emeralds counted in emeraldsPerDay
                case WORKSHOP -> workshop(s, t.trade == null ? "general" : t.trade, k);
                case TAVERN -> smelt(s, Items.WHEAT, Items.BREAD, 12 * k, 3);
                case STABLE -> gain(s, Items.LEATHER, 3 * k);
                case CHAPEL -> gain(s, Items.CANDLE, 1 * k);
                default -> {}
            }
        }
    }

    static void smelt(Settlement s, Item from, Item to, double amount) { smelt(s, from, to, amount, 1); }

    /** Converts up to amount of "to" from "from" (ratio inputs per output). */
    static void smelt(Settlement s, Item from, Item to, double amount, int ratio) {
        String key = "conv:" + BuiltInRegistries.ITEM.getKey(to);
        double v = s.accum.getOrDefault(key, 0.0) + amount;
        int n = Math.min((int) v, s.stock.count(from) / ratio);
        if (n > 0) {
            s.stock.take(from, n * ratio);
            s.stock.add(to, n);
        }
        s.accum.put(key, Math.min(v - (int) v + Math.max(0, (int) v - n) * 0, 4));
    }

    static void workshop(Settlement s, String trade, double k) {
        switch (trade) {
            case "butcher" -> gain(s, Items.COOKED_BEEF, 5 * k);
            case "fisher" -> gain(s, Items.COOKED_COD, 8 * k);
            case "shepherd", "weaver" -> gain(s, Items.WHITE_WOOL, 6 * k);
            case "fletcher" -> { gain(s, Items.ARROW, 24 * k); gain(s, Items.FEATHER, 4 * k); }
            case "mason" -> smelt(s, Items.COBBLESTONE, Items.STONE_BRICKS, 32 * k);
            case "tanner", "leather" -> gain(s, Items.LEATHER, 6 * k);
            case "bake", "mill" -> smelt(s, Items.WHEAT, Items.BREAD, 16 * k, 3);
            case "potter" -> gain(s, Items.BRICK, 16 * k);
            case "brew" -> gain(s, Items.SUGAR, 6 * k);
            default -> { gain(s, Items.PAPER, 6 * k); gain(s, Items.STRING, 6 * k); gain(s, Items.TORCH, 8 * k); }
        }
    }

    static void eat(Settlement s, Settlement.Derived d, double f) {
        if (s.isAi()) { s.starving = false; return; }
        double need = d.foodNeedPerDay * f + s.accum.getOrDefault("hunger", 0.0);
        List<Item> foods = new ArrayList<>();
        for (var e : s.stock.view().object2IntEntrySet()) {
            FoodProperties fp = e.getKey().getFoodProperties();
            if (fp != null && fp.getNutrition() > 0 && !fp.getEffects().stream().anyMatch(p -> !p.getFirst().getEffect().isBeneficial())) foods.add(e.getKey());
        }
        foods.sort(Comparator.comparingInt(i -> -s.stock.count(i)));
        for (Item it : foods) {
            if (need <= 0) break;
            int nut = it.getFoodProperties().getNutrition();
            int n = Math.min(s.stock.count(it), (int) Math.ceil(need / nut));
            s.stock.take(it, n);
            need -= n * nut;
        }
        s.starving = need > 0.5;
        s.accum.put("hunger", Math.max(0, Math.min(need, d.foodNeedPerDay)));
        if (need <= 0) s.accum.put("hunger", 0.0);
    }

    static void research(Settlement s, Settlement.Derived d, double f) {
        double pts = d.researchPerDay * f;
        if (s.isAi()) {
            s.researchBank += pts * 0.5;
            if (s.activeResearch.isEmpty()) {
                List<String> open = new ArrayList<>();
                for (var n : ResearchTree.nodes().values()) if (s.cannotResearch(n.id()) == null) open.add(n.id());
                if (!open.isEmpty()) s.activeResearch = open.get(new Random().nextInt(open.size()));
            }
        }
        if (s.activeResearch.isEmpty()) {
            s.researchBank = Math.min(s.researchBank + pts, 2000);
            return;
        }
        var node = ResearchTree.get(s.activeResearch);
        if (node == null) { s.activeResearch = ""; return; }
        s.researchProgress += pts + Math.min(s.researchBank, pts * 2);
        s.researchBank = Math.max(0, s.researchBank - pts * 2);
        if (s.researchProgress >= node.cost()) {
            s.research.add(node.id());
            s.researchBank += s.researchProgress - node.cost();
            s.researchProgress = 0;
            s.activeResearch = "";
            if (!s.isAi()) s.log("Research complete: " + node.name());
        }
    }

    static void forge(Settlement s, Settlement.Derived d, double f) {
        int smiths = d.workers.getOrDefault(Job.SMITH, 0);
        if (smiths == 0 || s.orders.isEmpty()) return;
        double work = smiths * f * (1 + Math.max(0, d.smithyLevels - 1) * 0.3);
        Iterator<ForgeOrder> it = s.orders.iterator();
        while (it.hasNext() && work > 0) {
            ForgeOrder o = it.next();
            var pattern = Smithing.get(o.pattern);
            var recipe = pattern == null ? null : pattern.pieces().get(o.piece);
            if (recipe == null) { it.remove(); continue; }
            while (work > 0 && o.done < o.quantity) {
                float need = o.piece.work - o.progress;
                if (work >= need) {
                    work -= need;
                    o.progress = 0;
                    o.done++;
                    s.stock.force(recipe.item(), recipe.count());
                } else {
                    o.progress += (float) work;
                    work = 0;
                }
            }
            if (o.done >= o.quantity) {
                it.remove();
                s.log("The smiths finished " + o.quantity + "× " + recipe.item().getDescription().getString());
            }
        }
    }

    static void training(ServerLevel level, Settlement s, Settlement.Derived d, double f) {
        if (s.training.isEmpty()) return;
        if (d.barracks == 0) return;
        double speed = (1 + (d.barracks - 1) * 0.5) * (s.has("logistics") ? 1.5 : 1);
        Settlement.Training t = s.training.get(0);
        t.progress += (float) (f * speed / t.type.trainDays);
        if (t.progress >= 1 && level.hasChunkAt(s.center)) {
            BlockPos at = musterPoint(level, s, 0);
            String name = soldierName(level, t.type);
            Entity e = RecruitsBridge.spawn(level, t.type, at, s.owner, name);
            if (e == null) return;
            s.training.remove(0);
            for (ItemStack g : t.gear) equipStack(s, e, g);
            if (s.autoEquip) equip(s, e, t.type);
            if (s.has("veterans")) RecruitsBridge.setLevel(e, 3);
            Settlement.Soldier so = new Settlement.Soldier();
            so.entity = e.getUUID();
            so.type = t.type;
            so.name = name;
            s.soldiers.add(so);
            s.log(name + " has finished training");
            applyStance(level, s, e, so, 0);
        }
    }

    static final String[] FIRST = {"Aldric", "Bran", "Cedric", "Doran", "Edric", "Falk", "Gareth", "Hale", "Ivo", "Joren", "Kell", "Leof",
            "Merek", "Nyle", "Osric", "Percival", "Quinn", "Roland", "Sten", "Tobin", "Ulric", "Varek", "Wystan", "Yorick", "Ada", "Brienne",
            "Cerys", "Dagna", "Elspeth", "Freya", "Gwen", "Hild", "Isolde", "Maud"};

    static String soldierName(ServerLevel level, UnitType t) {
        return t.label + " " + FIRST[level.random.nextInt(FIRST.length)];
    }

    /** Where soldiers form up: in front of the barracks, else the town hall. */
    static BlockPos musterPoint(ServerLevel level, Settlement s, int index) {
        BlockPos base = s.center;
        for (Plot p : s.plots) {
            BuildingType t = BuildingCatalog.get(p.typeId);
            if (t != null && p.standing() && t.category == Category.BARRACKS) { base = p.center(); break; }
        }
        int row = index / 5, col = index % 5;
        int x = base.getX() - 4 + col * 2, z = base.getZ() + 6 + row * 2;
        return new BlockPos(x, Construction.ground(level, x, z) + 1, z);
    }

    static final int[] AI_GUARDS = {2, 3, 5, 8, 12, 16};

    /** AI towns keep a guard that grows with their rank; one recruit joins per step. */
    static void aiGarrison(ServerLevel level, Settlement s) {
        if (s.soldiers.size() >= AI_GUARDS[s.tier.ordinal()] || s.wealth < 20) return;
        UnitType u = s.soldiers.size() % 3 == 2 ? UnitType.BOWMAN : s.tier.number() >= 3 && s.soldiers.size() % 4 == 1 ? UnitType.SHIELDBEARER : UnitType.FOOTMAN;
        BlockPos at = musterPoint(level, s, s.soldiers.size());
        Entity e = RecruitsBridge.spawn(level, u, at, null, s.name + " " + u.label);
        if (e == null) return;
        s.wealth -= 20;
        // AI guards get plain gear matching the town's rank
        Item[][] sets = {{Items.LEATHER_HELMET, Items.LEATHER_CHESTPLATE, Items.LEATHER_LEGGINGS, Items.LEATHER_BOOTS},
                {Items.CHAINMAIL_HELMET, Items.CHAINMAIL_CHESTPLATE, Items.CHAINMAIL_LEGGINGS, Items.CHAINMAIL_BOOTS},
                {Items.IRON_HELMET, Items.IRON_CHESTPLATE, Items.IRON_LEGGINGS, Items.IRON_BOOTS}};
        Item[] set = sets[Math.min(2, s.tier.ordinal() / 2)];
        for (int i = 0; i < 4; i++) RecruitsBridge.setSlot(e, Piece.ARMOR[i].slot, new ItemStack(set[i]));
        RecruitsBridge.setSlot(e, EquipmentSlot.MAINHAND, new ItemStack(u == UnitType.BOWMAN ? Items.BOW : s.tier.number() >= 3 ? Items.IRON_SWORD : Items.STONE_SWORD));
        if (u == UnitType.SHIELDBEARER) RecruitsBridge.setSlot(e, EquipmentSlot.OFFHAND, new ItemStack(Items.SHIELD));
        if (u == UnitType.BOWMAN) RecruitsBridge.addToPack(e, new ItemStack(Items.ARROW, 64));
        Settlement.Soldier so = new Settlement.Soldier();
        so.entity = e.getUUID();
        so.type = u;
        so.name = e.getName().getString();
        s.soldiers.add(so);
        applyStance(level, s, e, so, s.soldiers.size() - 1);
    }

    // ------------------------------------------------------------------ armoury

    /** Hands a soldier its kit from the armoury, returning what it wore before. */
    public static void equip(Settlement s, Entity e, UnitType u) {
        Kit k = s.kit(u);
        var armor = Smithing.get(k.armor);
        if (armor != null) {
            for (Piece p : Piece.ARMOR) {
                var r = armor.pieces().get(p);
                if (r != null) swapFromStock(s, e, p.slot, r.item());
            }
        }
        var weapon = Smithing.get(k.weapon);
        if (weapon != null) {
            var r = weapon.pieces().get(k.weaponPiece);
            if (r != null) swapFromStock(s, e, EquipmentSlot.MAINHAND, r.item());
        }
        if (k.shield) {
            for (var pat : Smithing.patterns().values()) {
                var r = pat.pieces().get(Piece.SHIELD);
                if (r != null && s.stock.has(r.item(), 1) && !(RecruitsBridge.getSlot(e, EquipmentSlot.OFFHAND).getItem() instanceof net.minecraft.world.item.ShieldItem)) {
                    swapFromStock(s, e, EquipmentSlot.OFFHAND, r.item());
                    break;
                }
            }
        }
        if (u == UnitType.BOWMAN || u == UnitType.CROSSBOWMAN) {
            int have = RecruitsBridge.countInPack(e, Items.ARROW);
            int want = 64 - have;
            int take = s.stock.takeUpTo(Items.ARROW, Math.max(0, want));
            if (take > 0) {
                ItemStack left = RecruitsBridge.addToPack(e, new ItemStack(Items.ARROW, take));
                s.stock.force(Items.ARROW, left.getCount());
            }
        }
    }

    static void swapFromStock(Settlement s, Entity e, EquipmentSlot slot, Item want) {
        ItemStack cur = RecruitsBridge.getSlot(e, slot);
        if (cur.is(want) || !s.stock.take(want, 1)) return;
        if (!cur.isEmpty() && !cur.hasTag()) s.stock.force(cur.getItem(), cur.getCount());
        RecruitsBridge.setSlot(e, slot, new ItemStack(want));
    }

    static void equipStack(Settlement s, Entity e, ItemStack g) {
        EquipmentSlot slot = g.getItem() instanceof net.minecraft.world.item.ShieldItem ? EquipmentSlot.OFFHAND
                : net.minecraft.world.entity.Mob.getEquipmentSlotForItem(g);
        if (RecruitsBridge.getSlot(e, slot).isEmpty()) RecruitsBridge.setSlot(e, slot, g);
        else s.stock.force(g.getItem(), g.getCount());
    }

    // ------------------------------------------------------------------ garrison

    /** Updates soldier records from the world, feeds them, and keeps their standing orders. */
    public static void garrison(ServerLevel level, Settlement s, boolean slow) {
        int i = 0;
        boolean fed = !s.starving;
        for (Settlement.Soldier so : s.soldiers) {
            Entity e = level.getEntity(so.entity);
            if (!(e instanceof LivingEntity le) || !e.isAlive()) { so.seen = false; i++; continue; }
            so.seen = true;
            so.health = le.getHealth();
            so.maxHealth = le.getMaxHealth();
            so.level = RecruitsBridge.level(e);
            so.kills = RecruitsBridge.kills(e);
            so.morale = RecruitsBridge.morale(e);
            so.gear = gearSummary(le);
            if (slow) {
                if (fed && s.contains(e.blockPosition())) {
                    RecruitsBridge.supply(e);
                    if (le.getHealth() < le.getMaxHealth()) le.addEffect(new MobEffectInstance(MobEffects.REGENERATION, 100, 0, true, false));
                } else if (!fed) {
                    le.addEffect(new MobEffectInstance(MobEffects.WEAKNESS, Config.SIM_INTERVAL.get() + 20, 0, true, true));
                }
                if (s.stance == Stance.PATROL || s.alarmUntil > level.getGameTime()) applyStance(level, s, e, so, i);
            }
            i++;
        }
    }

    static String gearSummary(LivingEntity e) {
        int armor = e.getArmorValue();
        ItemStack main = e.getMainHandItem();
        return (main.isEmpty() ? "Unarmed" : main.getHoverName().getString()) + " · " + armor + " armour";
    }

    /** Sends one soldier to its post for the current stance (or to the threat during an alarm). */
    public static void applyStance(ServerLevel level, Settlement s, Entity e, Settlement.Soldier so, int index) {
        if (s.alarmUntil > level.getGameTime() && s.stance != Stance.FOLLOW && s.stance != Stance.FREE) {
            Mob threat = nearestThreat(level, s, e.blockPosition());
            RecruitsBridge.aggressive(e);
            if (threat != null) { RecruitsBridge.holdAt(e, threat.blockPosition()); return; }
        }
        switch (s.stance) {
            case FREE -> {}
            case FOLLOW -> { if (s.owner != null) RecruitsBridge.follow(e, s.owner); }
            case MUSTER -> RecruitsBridge.holdAt(e, musterPoint(level, s, index));
            case GARRISON -> RecruitsBridge.holdAt(e, post(level, s, index));
            case PATROL -> {
                List<BlockPos> posts = posts(s);
                BlockPos target;
                if (!posts.isEmpty() && level.random.nextBoolean()) target = posts.get(level.random.nextInt(posts.size()));
                else {
                    double a = level.random.nextDouble() * Math.PI * 2, r = s.radius() * (0.25 + level.random.nextDouble() * 0.5);
                    target = s.center.offset((int) (Math.cos(a) * r), 0, (int) (Math.sin(a) * r));
                }
                RecruitsBridge.holdAt(e, new BlockPos(target.getX(), Construction.ground(level, target.getX(), target.getZ()) + 1, target.getZ()));
                RecruitsBridge.aggressive(e);
            }
        }
    }

    static List<BlockPos> posts(Settlement s) {
        List<BlockPos> out = new ArrayList<>();
        for (Plot p : s.plots) {
            BuildingType t = BuildingCatalog.get(p.typeId);
            if (t != null && p.standing() && (t.category == Category.TOWER || t.category == Category.WALL || t.category == Category.BARRACKS)) out.add(p.center());
        }
        return out;
    }

    static BlockPos post(ServerLevel level, Settlement s, int index) {
        List<BlockPos> posts = posts(s);
        if (posts.isEmpty()) return musterPoint(level, s, index);
        BlockPos p = posts.get(index % posts.size());
        int k = index / posts.size();
        int x = p.getX() + (k % 3) - 1, z = p.getZ() + 4 + k / 3;
        return new BlockPos(x, Construction.ground(level, x, z) + 1, z);
    }

    public static void applyStanceAll(ServerLevel level, Settlement s) {
        int i = 0;
        for (Settlement.Soldier so : s.soldiers) {
            Entity e = level.getEntity(so.entity);
            if (e != null && e.isAlive()) applyStance(level, s, e, so, i);
            i++;
        }
    }

    static Mob nearestThreat(ServerLevel level, Settlement s, BlockPos from) {
        int r = s.radius();
        List<Mob> mobs = level.getEntitiesOfClass(Mob.class, new AABB(s.center).inflate(r, 32, r),
                m -> m instanceof Enemy && m.isAlive() && s.contains(m.blockPosition()));
        Mob best = null;
        double bd = Double.MAX_VALUE;
        for (Mob m : mobs) {
            double dd = m.distanceToSqr(from.getX(), from.getY(), from.getZ());
            if (dd < bd) { bd = dd; best = m; }
        }
        return best;
    }

    /** Raises the alarm when hostiles walk into town. */
    public static void watch(ServerLevel level, Settlement s) {
        int r = s.radius();
        int hostiles = level.getEntitiesOfClass(Mob.class, new AABB(s.center).inflate(r, 32, r),
                m -> m instanceof Enemy && m.isAlive() && s.contains(m.blockPosition())
                        && m.distanceToSqr(s.center.getX(), s.center.getY(), s.center.getZ()) < (double) r * r * 0.5).size();
        long now = level.getGameTime();
        boolean was = s.alarmUntil > now;
        if (hostiles >= 3 || s.manualAlarm) {
            s.alarmUntil = now + 400;
            if (!was) {
                s.log(s.manualAlarm ? "The alarm bell was rung" : "Raiders sighted! The alarm bell rings");
                level.playSound(null, s.center, SoundEvents.BELL_BLOCK, SoundSource.BLOCKS, 3f, 1f);
                applyStanceAll(level, s);
                s.dirty = true;
            }
        } else if (was && s.alarmUntil - now < 40) {
            s.alarmUntil = 0;
            s.log("The threat has passed");
            applyStanceAll(level, s);
            s.dirty = true;
        }
    }

    // ------------------------------------------------------------------ builders and townsfolk

    /** Keeps the visible builders and townsfolk in step with the town's jobs. */
    static void settlers(ServerLevel level, Settlement s, Settlement.Derived d) {
        int r = s.radius() + 16;
        List<SettlerEntity> present = level.getEntitiesOfClass(SettlerEntity.class, new AABB(s.center).inflate(r, 48, r),
                e -> s.id.equals(e.settlementId()));
        int builders = Math.min(6, d.workers.getOrDefault(Job.BUILDER, 0));
        int folk = Math.min(Config.MAX_VISIBLE_TOWNSFOLK.get(), Math.max(0, s.population - builders) / 2);
        List<Job> folkJobs = new ArrayList<>();
        d.workers.forEach((j, n) -> { if (j != Job.BUILDER) for (int i = 0; i < n; i++) folkJobs.add(j); });
        for (int i = 0; i < d.unemployed; i++) folkJobs.add(Job.IDLE);
        int haveB = 0, haveF = 0;
        for (SettlerEntity e : present) {
            if (e.job() == Job.BUILDER) {
                if (haveB >= builders) e.discard(); else haveB++;
            } else {
                if (haveF >= folk) e.discard(); else haveF++;
            }
        }
        BlockPos spawn = new BlockPos(s.center.getX(), Construction.ground(level, s.center.getX(), s.center.getZ()) + 1, s.center.getZ());
        if (haveB < builders) SettlerEntity.spawn(level, s, Job.BUILDER, spawn);
        if (haveF < folk && !folkJobs.isEmpty()) SettlerEntity.spawn(level, s, folkJobs.get(level.random.nextInt(folkJobs.size())), spawn);
    }

    /** Builders not in loaded range still work, slower, paying ahead; the blocks appear when someone comes near. */
    static void offscreenBuild(ServerLevel level, Settlement s, Settlement.Derived d) {
        double rate = Config.OFFSCREEN_BUILD_RATE.get();
        if (rate <= 0) return;
        int builders = d.workers.getOrDefault(Job.BUILDER, 0);
        if (builders == 0) return;
        double speed = speed(s);
        List<Plot> active = activePlots(s);
        int per = Math.max(1, builders / Math.max(1, active.size()));
        for (Plot p : active) {
            if (builders <= 0) break;
            if (Construction.loaded(level, p)) continue;
            if (p.state == Plot.State.REPAIRING || p.state == Plot.State.UPGRADING) continue;
            int steps = (int) (Config.SIM_INTERVAL.get() / (double) Config.BUILD_INTERVAL.get() * rate * per * speed);
            Construction.payAhead(s, p, steps);
            builders -= per;
        }
    }

    public static double speed(Settlement s) {
        return (s.has("carpentry") ? 1.25 : 1) * (s.has("engineering") ? 1.4 : 1);
    }

    public static List<Plot> activePlots(Settlement s) {
        List<Plot> l = new ArrayList<>();
        for (Plot p : s.plots) if (p.active()) l.add(p);
        l.sort(Comparator.comparingInt((Plot p) -> -p.priority).thenComparingInt(p -> p.id));
        return l;
    }

    /** Called often (every second) for loaded settlements: catch up paid work and finish plots. */
    public static void fast(ServerLevel level, Settlement s) {
        boolean changed = false;
        for (Plot p : new ArrayList<>(s.plots)) {
            if (!p.active()) continue;
            if (p.builder != null) {
                Entity b = level.getEntity(p.builder);
                if (b == null || !b.isAlive()) p.builder = null;
            }
            if (!Construction.loaded(level, p)) {
                if (p.paid >= p.totalSteps() && p.totalSteps() > 0) p.status = "Finishing when someone is near";
                continue;
            }
            if (p.paid > p.placed) Construction.catchUp(level, s, p, 3000);
            if (p.placed >= p.totalSteps() || (p.paid >= p.totalSteps() && Construction.nextWork(level, s, p, 400) == null)) {
                finish(level, s, p);
                changed = true;
            }
        }
        if (changed) recompute(s);
    }

    public static void finish(ServerLevel level, Settlement s, Plot p) {
        BuildingType t = BuildingCatalog.get(p.typeId);
        String name = p.typeId.equals("road") ? "road" : t == null ? "building" : t.name;
        switch (p.state) {
            case BUILDING -> { p.state = Plot.State.BUILT; if (!s.isAi() || level.random.nextInt(3) == 0) s.log("Builders finished the " + name); }
            case UPGRADING -> { p.state = Plot.State.BUILT; p.level++; s.log("The " + name + " is now level " + p.level); }
            case REPAIRING -> { p.state = Plot.State.BUILT; s.log("Repairs to the " + name + " are done"); }
            case DEMOLISHING -> { s.plots.remove(p); s.log("The " + name + " was torn down"); }
            default -> {}
        }
        p.placed = 0;
        p.paid = 0;
        p.builder = null;
        p.status = "";
        p.foundation = new long[0];
        s.dirty = true;
    }

    private Simulation() {}
}
