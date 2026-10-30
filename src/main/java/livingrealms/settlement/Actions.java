package livingrealms.settlement;

import livingrealms.building.BuildingCatalog;
import livingrealms.building.BuildingType;
import livingrealms.building.Category;
import livingrealms.military.Kit;
import livingrealms.military.RecruitsBridge;
import livingrealms.military.SoldierRanks;
import livingrealms.military.SoldierSkills;
import livingrealms.military.SoldierProgression;
import livingrealms.military.Stance;
import livingrealms.military.UnitType;
import livingrealms.research.ResearchTree;
import livingrealms.smithing.ForgeOrder;
import livingrealms.smithing.Piece;
import livingrealms.smithing.Smithing;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Rotation;

import javax.annotation.Nullable;
import java.util.*;

/** Everything a player can do from the City Manager and the planner, checked on the server. */
public final class Actions {

    public static void handle(ServerPlayer player, UUID settlementId, String action, CompoundTag d) {
        SettlementManager m = SettlementManager.get(player.server);
        Settlement s = m.get(settlementId);
        if (s == null) return;
        if (action.equals("raid")) {
            String error = Raids.playerStart(player, m, s);
            if (error != null) tell(player, error);
            return;
        }
        if (action.equals("vxRaid")) {
            Settlement village = d.hasUUID("village") ? m.get(d.getUUID("village")) : null;
            String error = village == null || !village.mirror() ? "No such village" : Raids.playerStart(player, m, village);
            if (error != null) tell(player, error);
            return;
        }
        if (action.equals("vxWar") || action.equals("vxPeace")) {
            String f = d.getString("faction");
            String error = action.equals("vxWar") ? Realms.declare(player, m, s, f) : Realms.peace(player, m, s, f);
            if (error != null) tell(player, error);
            s.dirty = true;
            return;
        }
        if (action.startsWith("sc")) {
            String error = Statecraft.handle(player, m, s, action, d);
            if (error != null) tell(player, error);
            s.dirty = true;
            return;
        }
        if (action.startsWith("quest")) {
            String error = livingrealms.quest.Quests.handle(player, m, s, action, d);
            if (error != null) tell(player, error);
            s.dirty = true;
            return;
        }
        if (s.mirror()) {
            tell(player, s.name + " is run by Village Expansion. You can look, raid it in war, or capture it.");
            return;
        }
        boolean owner = player.getUUID().equals(s.owner) || player.hasPermissions(2);
        if (!owner) {
            tell(player, "This is " + (s.isAi() ? "an independent town" : s.ownerName + "'s town") + ". You can look, not command.");
            return;
        }
        ServerLevel level = player.server.getLevel(s.dim);
        if (level == null) return;
        String err = switch (action) {
            case "rename" -> { String n = d.getString("name").trim(); if (!n.isEmpty() && n.length() <= 32) { s.name = n; } yield null; }
            case "plan" -> plan(level, s, d);
            case "planMap" -> Planning.planMap(level, s, d.getString("type"), d.getInt("x"), d.getInt("z"), d.getInt("rot"));
            case "autoBuild" -> { s.autoBuild = d.getBoolean("on"); yield null; }
            case "autoWalls" -> { s.autoWalls = d.getBoolean("on"); s.dirty = true; yield null; }
            case "style" -> style(level, s, d.getString("family"));
            case "road" -> Planning.road(level, s, BlockPos.of(d.getLong("a")), BlockPos.of(d.getLong("b")), d.getInt("width"), d.getString("material"));
            case "upgrade" -> withPlot(s, d, p -> p.deferred ? DEFERRED : Planning.upgrade(level, s, p));
            case "repair" -> withPlot(s, d, p -> { if (p.deferred) return DEFERRED; Planning.repair(s, p); return null; });
            case "demolish" -> withPlot(s, d, p -> { Planning.demolish(s, p); return null; });
            case "priority" -> withPlot(s, d, p -> { p.priority = Math.max(-5, Math.min(20, p.priority + d.getInt("delta"))); return null; });
            case "research" -> research(s, d.getString("id"));
            case "cancelResearch" -> cancelResearch(s);
            case "clearSkips" -> { s.researchSkip.clear(); yield null; }
            case "tradeRoute", "tradeBan" -> { String e = Trade.handle(player, m, s, action, d); s.dirty = true; yield e; }
            case "autoResearch" -> { s.autoResearch = d.getBoolean("on"); yield null; }
            case "autoTier" -> { s.autoTier = d.getBoolean("on"); yield null; }
            case "autoTrain" -> { s.autoTrain = d.getBoolean("on"); yield null; }
            case "garrisonTarget" -> { s.garrisonTarget = Math.max(-1, Math.min(s.d.garrisonCap, d.getInt("value"))); yield null; }
            case "tier" -> advance(s);
            case "capital" -> player.getUUID().equals(s.owner) ? Kingdoms.designate(player.server, m, s) : "Only the ruler may move the capital";
            case "tax" -> tax(s, d.getInt("level"));
            case "order" -> order(s, d);
            case "cancelOrder" -> cancelOrder(s, d.getInt("id"));
            case "train" -> train(s, d);
            case "cancelTraining" -> cancelTraining(s, d.getInt("index"));
            case "stance" -> { s.stance = Stance.valueOf(d.getString("stance")); Simulation.applyStanceAll(level, s); yield null; }
            case "kit" -> kit(s, d);
            case "equipAll" -> equipAll(level, s);
            case "autoEquip" -> { s.autoEquip = d.getBoolean("on"); yield null; }
            case "alarm" -> { s.manualAlarm = d.getBoolean("on"); if (!s.manualAlarm) s.alarmUntil = Math.min(s.alarmUntil, level.getGameTime() + 20); Simulation.watch(level, s); yield null; }
            case "withdraw" -> withdraw(player, s, d);
            case "deposit" -> deposit(player, s, d.getString("item"));
            case "royalGuard" -> royalGuard(player, level, s, d);
            case "learnSoldierSkill" -> learnSoldierSkill(player, s, d);
            case "drillSoldier" -> drillSoldier(player, s, d);
            case "respecSoldier" -> respecSoldier(player, s, d);
            case "chooseSpecial" -> chooseSpecial(player, s, d);
            case "grantRequest", "orderRequest", "declineRequest" -> Merit.handle(player, s, action, d);
            case "dismiss" -> dismissSoldier(player, level, s, d);
            case "chronicleBook" -> livingrealms.chronicle.ChronicleBook.give(player, s);
            case "quarantine" -> livingrealms.hardship.Hardships.quarantine(s, d.getBoolean("on"));
            default -> "Unknown action";
        };
        if (err != null) tell(player, err);
        Simulation.recompute(s);
        s.dirty = true;
    }

    static final String DEFERRED = "Builders finish placing it when the area is loaded";

    static void tell(ServerPlayer p, String msg) {
        p.displayClientMessage(Component.literal(msg).withStyle(ChatFormatting.GOLD), true);
    }

    interface PlotAction { String run(Plot p); }

    @Nullable
    static String withPlot(Settlement s, CompoundTag d, PlotAction a) {
        Plot p = s.plot(d.getInt("plot"));
        return p == null ? "No such building" : a.run(p);
    }

    @Nullable
    static String plan(ServerLevel level, Settlement s, CompoundTag d) {
        BuildingType t = BuildingCatalog.get(d.getString("type"));
        if (t == null) return "Unknown building";
        return Planning.plan(level, s, t, BlockPos.of(d.getLong("pos")), Rotation.values()[d.getInt("rot") & 3], false);
    }

    @Nullable
    static String research(Settlement s, String id) {
        String why = s.cannotResearch(id);
        if (why != null) return why;
        var n = ResearchTree.get(id);
        for (var e : n.items().entrySet()) if (s.stock.countIngredient(e.getKey()) < e.getValue()) return "Needs " + e.getValue() + " " + ingredientName(e.getKey());
        n.items().forEach(s.stock::takeIngredient);
        if (!s.activeResearch.isEmpty()) s.researchBank += s.researchProgress;
        s.activeResearch = id;
        s.researchAuto = false;
        s.researchProgress = 0;
        s.log("Scholars began studying " + n.name());
        return null;
    }

    /** Cancelling a choice auto research made also tells it to leave that project alone. */
    @Nullable
    static String cancelResearch(Settlement s) {
        if (s.researchAuto && !s.activeResearch.isEmpty()) {
            s.researchSkip.add(s.activeResearch);
            var n = ResearchTree.get(s.activeResearch);
            s.log("Auto research will skip " + (n == null ? s.activeResearch : n.name()));
        }
        s.researchBank += s.researchProgress;
        s.researchProgress = 0;
        s.activeResearch = "";
        s.researchAuto = false;
        return null;
    }

    public static String ingredientName(String ing) {
        if (ing.startsWith("#")) return BuildingCatalog.pretty(new ResourceLocation(ing.substring(1)).getPath());
        return BuiltInRegistries.ITEM.get(new ResourceLocation(ing)).getDescription().getString();
    }

    @Nullable
    static String advance(Settlement s) {
        Tier n = s.tier.next();
        if (n == null) return "Already a capital";
        List<String> b = s.tierBlockers();
        if (!b.isEmpty()) return "Still needed: " + String.join(", ", b);
        s.stock.take(Items.EMERALD, n.emeralds);
        s.tier = n;
        s.log(s.name + " is now a " + n.label + "! The Town Hall can be enlarged.");
        return null;
    }

    @Nullable
    static String tax(Settlement s, int level) {
        if (level == 3 && !s.has("taxation")) return "Research Taxation first";
        s.taxLevel = Math.max(0, Math.min(3, level));
        return null;
    }

    // ------------------------------------------------------------------ smithing

    /** Cost of one piece, or null if the pattern doesn't make it. */
    @Nullable
    public static Map<String, Integer> cost(String pattern, Piece piece) {
        var p = Smithing.get(pattern);
        if (p == null) return null;
        var r = p.pieces().get(piece);
        return r == null ? null : r.cost();
    }

    @Nullable
    static String order(Settlement s, CompoundTag d) {
        var pattern = Smithing.get(d.getString("pattern"));
        if (pattern == null) return "Unknown material";
        int qty = Math.max(1, Math.min(64, d.getInt("qty")));
        if (s.standing(Category.SMITHY) == 0) return "Build a Smithy first";
        List<Piece> pieces = new ArrayList<>();
        if (d.getString("piece").equals("SET")) {
            for (Piece p : Piece.ARMOR) if (pattern.pieces().containsKey(p)) pieces.add(p);
        } else pieces.add(Piece.valueOf(d.getString("piece")));
        if (pieces.isEmpty()) return "This pattern has no armour set";
        Map<String, Integer> total = new LinkedHashMap<>();
        for (Piece p : pieces) {
            var r = pattern.pieces().get(p);
            if (r == null) return pattern.name() + " has no " + p.label.toLowerCase(Locale.ROOT);
            if (!s.has(r.research())) {
                var n = ResearchTree.get(r.research());
                return "Research " + (n == null ? r.research() : n.name()) + " first";
            }
            r.cost().forEach((k, v) -> total.merge(k, v * qty, Integer::sum));
        }
        for (var e : total.entrySet())
            if (s.stock.countIngredient(e.getKey()) < e.getValue())
                return "Not enough " + ingredientName(e.getKey()) + " (" + s.stock.countIngredient(e.getKey()) + "/" + e.getValue() + ")";
        total.forEach(s.stock::takeIngredient);
        for (Piece p : pieces) {
            ForgeOrder o = new ForgeOrder();
            o.id = s.nextId++;
            o.pattern = pattern.id();
            o.piece = p;
            o.quantity = qty;
            s.orders.add(o);
        }
        s.log("Ordered " + qty + "× " + pattern.name() + " " + (pieces.size() > 1 ? "armour sets" : pieces.get(0).label.toLowerCase(Locale.ROOT)));
        return null;
    }

    @Nullable
    static String cancelOrder(Settlement s, int id) {
        for (Iterator<ForgeOrder> it = s.orders.iterator(); it.hasNext(); ) {
            ForgeOrder o = it.next();
            if (o.id != id) continue;
            it.remove();
            var c = cost(o.pattern, o.piece);
            int left = o.quantity - o.done;
            if (c != null) c.forEach((k, v) -> refund(s, k, v * left));
            return null;
        }
        return null;
    }

    static void refund(Settlement s, String ing, int n) {
        if (ing.startsWith("#")) {
            var tag = net.minecraft.tags.TagKey.create(net.minecraft.core.registries.Registries.ITEM, new ResourceLocation(ing.substring(1)));
            BuiltInRegistries.ITEM.getTag(tag).flatMap(h -> h.stream().findFirst()).ifPresent(h -> s.stock.force(h.value(), n));
        } else s.stock.force(BuiltInRegistries.ITEM.get(new ResourceLocation(ing)), n);
    }

    // ------------------------------------------------------------------ military

    @Nullable
    static String train(Settlement s, CompoundTag d) {
        UnitType u = UnitType.valueOf(d.getString("unit"));
        int qty = Math.max(1, Math.min(10, d.getInt("qty")));
        if (!s.has(u.research)) return "Research " + ResearchTree.get(u.research).name() + " first";
        if (s.standing(Category.BARRACKS) == 0) return "Build a Barracks first";
        if ((u == UnitType.HORSEMAN || u == UnitType.DRAGON_RIDER) && !s.d.hasStables) return u.label + " training needs Stables";
        if (u == UnitType.DRAGON_RIDER && !livingrealms.military.DragonRiders.installed()) return "Dragon Riders require Ice and Fire";
        int trained = 0;
        String err = null;
        for (int i = 0; i < qty; i++) {
            if (s.soldiers.size() + s.training.size() >= s.d.garrisonCap) { err = "The garrison is full (" + s.d.garrisonCap + "). Build barracks or towers."; break; }
            if (s.population <= 2) { err = "Not enough people to spare"; break; }
            if (!s.stock.has(Items.EMERALD, u.emeralds)) { err = "Needs " + u.emeralds + " emeralds"; break; }
            List<Item> gear = new ArrayList<>();
            for (Piece need : u.needs) {
                Item it = armouryItem(s, u, need);
                if (it == null) { err = "The armoury has no " + needName(need) + ". Order one from the smiths."; break; }
                gear.add(it);
                s.stock.take(it, 1);
            }
            if (err != null) { gear.forEach(it -> s.stock.force(it, 1)); break; }
            s.stock.take(Items.EMERALD, u.emeralds);
            s.population--;
            Settlement.Training t = new Settlement.Training();
            t.type = u;
            gear.forEach(it -> t.gear.add(new ItemStack(it)));
            s.training.add(t);
            trained++;
        }
        if (trained > 0) s.log(trained + " " + (trained > 1 ? u.plural() : u.label) + " began training");
        return err;
    }

    static String needName(Piece p) {
        return p == Piece.SWORD ? "sword, axe or mace" : p.label.toLowerCase(Locale.ROOT);
    }

    /** The best item in the stockpile for a needed piece, preferring the unit's kit. */
    @Nullable
    static Item armouryItem(Settlement s, UnitType u, Piece need) {
        Kit k = s.kit(u);
        var kp = Smithing.get(k.weapon);
        if (kp != null) {
            var r = kp.pieces().get(need == Piece.SWORD && k.weaponPiece.melee() ? k.weaponPiece : need);
            if (r != null && s.stock.has(r.item(), 1)) return r.item();
        }
        List<Smithing.Pattern> pats = new ArrayList<>(Smithing.patterns().values());
        Collections.reverse(pats);
        for (var p : pats) {
            for (var e : p.pieces().entrySet()) {
                boolean fits = need == Piece.SWORD ? e.getKey().melee() : e.getKey() == need;
                if (fits && s.stock.has(e.getValue().item(), 1)) return e.getValue().item();
            }
        }
        // plain vanilla gear the player dropped in works too
        Item[] fallback = switch (need) {
            case SWORD -> new Item[]{Items.NETHERITE_SWORD, Items.DIAMOND_SWORD, Items.IRON_SWORD, Items.STONE_SWORD, Items.WOODEN_SWORD, Items.IRON_AXE, Items.STONE_AXE};
            case SHIELD -> new Item[]{Items.SHIELD};
            case BOW -> new Item[]{Items.BOW};
            case CROSSBOW -> new Item[]{Items.CROSSBOW};
            default -> new Item[0];
        };
        for (Item it : fallback) if (s.stock.has(it, 1)) return it;
        return null;
    }

    @Nullable
    static String cancelTraining(Settlement s, int index) {
        if (index < 0 || index >= s.training.size()) return null;
        Settlement.Training t = s.training.remove(index);
        t.gear.forEach(g -> s.stock.force(g.getItem(), g.getCount()));
        s.stock.force(Items.EMERALD, t.type.emeralds);
        s.population++;
        return null;
    }

    @Nullable
    static String kit(Settlement s, CompoundTag d) {
        UnitType u = UnitType.valueOf(d.getString("unit"));
        Kit k = s.kit(u);
        if (d.contains("armor")) k.armor = d.getString("armor");
        if (d.contains("weapon")) k.weapon = d.getString("weapon");
        if (d.contains("piece")) k.weaponPiece = Piece.valueOf(d.getString("piece"));
        if (d.contains("shield")) k.shield = d.getBoolean("shield");
        return null;
    }

    @Nullable
    static String equipAll(ServerLevel level, Settlement s) {
        int n = 0;
        for (Settlement.Soldier so : s.soldiers) {
            Entity e = level.getEntity(so.entity);
            if (e == null || !e.isAlive()) continue;
            Simulation.equip(s, e, so.type);
            n++;
        }
        return n == 0 ? "No soldiers nearby to equip" : null;
    }

    // Individual recruit commands never accept client-supplied XP, ranks, costs, or owners.
    @Nullable
    static Settlement.Soldier soldier(Settlement s, CompoundTag d) {
        if (!d.hasUUID("soldier")) return null;
        UUID id = d.getUUID("soldier");
        for (Settlement.Soldier so : s.soldiers) if (so.entity.equals(id)) return so;
        return null;
    }

    @Nullable
    static String royalGuard(ServerPlayer player, ServerLevel level, Settlement s, CompoundTag d) {
        if (!player.getUUID().equals(s.owner)) return "Only this town's owner can appoint personal Royal Guards";
        Settlement.Soldier so = soldier(s, d);
        if (so == null) return "No such recruit";
        Entity e = SoldierProgression.findEntity(player.server, so.entity);
        if (e == null || !e.isAlive() || !s.owner.equals(RecruitsBridge.owner(e))) return "Your recruit must be loaded and still belong to you";
        boolean appoint = d.getBoolean("on");
        if (appoint == so.royalGuard) return null;
        if (appoint) {
            String why = royalGuardBlocker(s, so);
            if (why != null) return why;
            s.stock.take(Items.EMERALD, 32);
        }
        so.royalGuard = appoint;
        if (!appoint && e.level() != level) RecruitsBridge.holdAt(e, e.blockPosition());
        else Simulation.applyStance((ServerLevel) e.level(), s, e, so, s.soldiers.indexOf(so));
        s.log(so.name + (appoint ? " joined your Royal Guard" : " returned to the town garrison"));
        return null;
    }

    /** Why this recruit can't be appointed a Royal Guard yet, or null. */
    @Nullable
    public static String royalGuardBlocker(Settlement s, Settlement.Soldier so) {
        if (!s.has("command")) return "Research Command first";
        if (SoldierSkills.earnedPoints(so.skillXp) < 2) return "Royal Guards must reach the Soldier title (skill rank 2, 100 combat XP)";
        if (!s.stock.has(Items.EMERALD, 32)) return "Royal Guard promotion costs 32 emeralds from town stock";
        return null;
    }

    @Nullable
    static String learnSoldierSkill(ServerPlayer player, Settlement s, CompoundTag d) {
        if (!player.getUUID().equals(s.owner)) return "Only this town's owner can train its recruits";
        Settlement.Soldier so = soldier(s, d);
        SoldierSkills skill = SoldierSkills.byId(d.getString("skill"));
        if (so == null || skill == null) return "Unknown recruit or skill";
        if (so.skills.contains(skill)) return "This recruit already learned that skill";
        if (!skill.unlocked(so.skills)) return "Learn " + skill.prerequisite.label + " first";
        if (SoldierSkills.earnedPoints(so.skillXp) < skill.requiredRank) return "This skill needs recruit rank " + skill.requiredRank;
        if (SoldierSkills.availablePoints(so.skillXp, so.skills) < 1) return "Earn a skill point through combat or drill";
        Entity e = SoldierProgression.findEntity(player.server, so.entity);
        if (e == null || !e.isAlive() || !s.owner.equals(RecruitsBridge.owner(e))) return "Your recruit must be loaded and still belong to you";
        so.skills.add(skill);
        if (e instanceof net.minecraft.world.entity.LivingEntity le) SoldierProgression.applySkills(le, so);
        s.log(so.name + " learned " + skill.label);
        return null;
    }

    @Nullable
    static String drillSoldier(ServerPlayer player, Settlement s, CompoundTag d) {
        if (!player.getUUID().equals(s.owner)) return "Only this town's owner can drill its recruits";
        Settlement.Soldier so = soldier(s, d);
        if (so == null) return "No such recruit";
        return SoldierRanks.drill(s, so, SoldierProgression.findEntity(player.server, so.entity), player.server.overworld().getGameTime());
    }

    @Nullable
    static String respecSoldier(ServerPlayer player, Settlement s, CompoundTag d) {
        if (!player.getUUID().equals(s.owner)) return "Only this town's owner can retrain its recruits";
        Settlement.Soldier so = soldier(s, d);
        if (so == null) return "No such recruit";
        return SoldierRanks.respec(s, so, SoldierProgression.findEntity(player.server, so.entity));
    }

    @Nullable
    static String chooseSpecial(ServerPlayer player, Settlement s, CompoundTag d) {
        if (!player.getUUID().equals(s.owner)) return "Only this town's owner can train its recruits";
        Settlement.Soldier so = soldier(s, d);
        if (so == null) return "Unknown recruit";
        boolean had = so.specials.contains(livingrealms.military.SpecialSkill.byId(d.getString("special")));
        String err = livingrealms.military.SoldierSpecials.choose(so, d.getString("special"));
        if (err == null) s.log(so.name + (had ? " set aside " : " readied ") + livingrealms.military.SpecialSkill.valueOf(d.getString("special")).label);
        return err;
    }

    @Nullable
    static String dismissSoldier(ServerPlayer player, ServerLevel level, Settlement s, CompoundTag d) {
        Settlement.Soldier so = soldier(s, d);
        if (so == null) return "No such recruit";
        Entity e = SoldierProgression.findEntity(player.server, so.entity);
        if (e == null || !e.isAlive()) return "The recruit must be loaded before dismissal";
        if (e instanceof net.minecraft.world.entity.LivingEntity le) SoldierProgression.clearSkills(le);
        if (so.royalGuard) RecruitsBridge.holdAt(e, e.blockPosition());
        s.soldiers.remove(so);
        return null;
    }

    // ------------------------------------------------------------------ stockpile

    @Nullable
    static String withdraw(ServerPlayer player, Settlement s, CompoundTag d) {
        Item it = BuiltInRegistries.ITEM.get(new ResourceLocation(d.getString("item")));
        int n = s.stock.takeUpTo(it, Math.max(1, Math.min(it.getMaxStackSize() * 4, d.getInt("count"))));
        while (n > 0) {
            int c = Math.min(n, it.getMaxStackSize());
            ItemStack st = new ItemStack(it, c);
            if (!player.getInventory().add(st)) player.drop(st, false);
            n -= c;
        }
        return null;
    }

    @Nullable
    static String deposit(ServerPlayer player, Settlement s, String item) {
        var inv = player.getInventory();
        int moved = 0;
        for (int i = 9; i < 36; i++) {
            ItemStack st = inv.getItem(i);
            if (!livingrealms.settlement.Stockpile.canStore(st)) continue;
            if (!item.isEmpty() && !BuiltInRegistries.ITEM.getKey(st.getItem()).toString().equals(item)) continue;
            int fit = s.stock.add(st.getItem(), st.getCount());
            st.shrink(fit);
            moved += fit;
        }
        if (moved == 0) return s.stock.free() == 0 ? "The stockpile is full. Build a Storehouse." : "Nothing to deposit (hotbar and named or enchanted items are kept)";
        tell(player, "Deposited " + moved + " items");
        return null;
    }

    private Actions() {}

    /** The look of what the villagers build from now on: a family (plains, desert, savanna, taiga, snowy) or "biome". */
    static String style(ServerLevel level, Settlement s, String family) {
        if (family.equals("biome")) s.style = livingrealms.building.Procedural.biomeStyle(level, s.center);
        else if (livingrealms.building.Procedural.FAMILIES.contains(family)) s.style = "minecraft:" + family;
        else return "Unknown style " + family;
        s.dirty = true;
        s.log("Builders now follow the " + livingrealms.building.Procedural.styleFamily(s.style) + " style");
        return null;
    }
}
