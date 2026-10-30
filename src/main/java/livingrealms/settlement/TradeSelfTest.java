package livingrealms.settlement;

import com.mojang.authlib.GameProfile;
import livingrealms.LivingRealms;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.npc.WanderingTrader;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.AABB;
import net.minecraftforge.common.util.FakePlayerFactory;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.server.ServerStartedEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

/** Trade and caravans (docs/trade.md): ./gradlew runServer -PtradeTest -PtradeTestDir=<fresh dir>; writes trade-test-result.txt. */
@Mod.EventBusSubscriber(modid = LivingRealms.MODID)
public final class TradeSelfTest {
    private static int checks;
    private static void check(boolean value, String detail) {
        if (!value) throw new IllegalStateException(detail);
        checks++;
    }

    /** The walking part waits on real server ticks; this holds it between ticks. */
    private static Walk walk;
    private static final class Walk {
        SettlementManager m; Settlement from, to; Trade.Caravan c; long deadline, started; int glassBefore;
        final List<long[]> forced = new ArrayList<>();
    }

    @SubscribeEvent
    public static void started(ServerStartedEvent event) {
        if (!Boolean.getBoolean("livingrealms.tradeTest")) return;
        ServerLevel level = event.getServer().overworld();
        try {
            economy(level);
            physical(level);
        } catch (Throwable error) {
            finish(event.getServer(), "FAIL: " + error, error);
        }
    }

    @SubscribeEvent
    public static void tick(TickEvent.ServerTickEvent e) {
        if (walk == null || e.phase != TickEvent.Phase.END) return;
        MinecraftServer server = e.getServer();
        try {
            long now = server.overworld().getGameTime();
            if (walk.c.status == Trade.Status.ARRIVED) {
                arrived(server.overworld());
                finish(server, "PASS: " + checks + " trade assertions", null);
            } else if (now > walk.deadline) {
                throw new IllegalStateException("A walking caravan did not arrive in time: " + walk.c.status + " progress " + walk.c.progress
                        + " physical " + walk.c.physical + " note " + walk.c.note);
            }
        } catch (Throwable error) {
            finish(server, "FAIL: " + error, error);
        }
    }

    private static void finish(MinecraftServer server, String report, Throwable error) {
        if (walk != null) {
            for (long[] ch : walk.forced) server.overworld().setChunkForced((int) ch[0], (int) ch[1], false);
            walk.m.trade.caravans.removeIf(c -> c.from.equals(walk.from.id) || c.to.equals(walk.from.id));
            walk.m.remove(walk.from); walk.m.remove(walk.to);
            walk.m.trade.nextStep = 0;
            walk = null;
        }
        Caravans.riskScale = 1;
        if (error == null) LivingRealms.LOG.info("TRADE SELF TEST {}", report);
        else LivingRealms.LOG.error("TRADE SELF TEST FAILED", error);
        try { Files.writeString(Path.of("trade-test-result.txt"), report); } catch (Exception ex) { throw new RuntimeException(ex); }
        server.halt(false);
    }

    private static Settlement town(SettlementManager m, UUID owner, String name, String style, int x, int z) {
        Settlement s = new Settlement(); s.owner = owner; s.ownerName = owner == null ? "" : name;
        s.name = name; s.style = style; s.center = new BlockPos(x, 100, z); s.population = 10;
        m.add(s); return s;
    }

    /** Production, prices, routes, a simulated caravan, ambushes, hostile acts, export bans and save/load, on a private ledger. */
    private static void economy(ServerLevel level) {
        MinecraftServer server = level.getServer();
        UUID a = UUID.randomUUID(), b = UUID.randomUUID();
        var m = new SettlementManager();
        Settlement plains = town(m, a, "Wheatford", "minecraft:plains", 8192, 0);
        Settlement desert = town(m, null, "Sandmouth", "minecraft:desert", 8492, 0);
        Settlement enemy = town(m, b, "Ironhold", "minecraft:taiga", 8342, 200);
        long now = 1000;

        // ---- production
        Trade.produce(plains, 1.0);
        check(plains.stock.count(Items.WHEAT) == 24 && plains.stock.count(Items.WHITE_WOOL) == 6, "A plains town of 10 makes 24 wheat and 6 wool a day: " + plains.stock.count(Items.WHEAT));
        check(plains.stock.count(Items.SAND) == 0 && plains.stock.count(Items.EMERALD) == 0, "A plains town makes no sand");
        Trade.produce(desert, 1.0);
        check(desert.stock.count(Items.SAND) > 0 && desert.stock.count(Items.GLASS) > 0 && desert.stock.count(Items.WHEAT) == 0, "A desert town makes sand and glass, no wheat");
        Plot mine = new Plot(); mine.id = 1; mine.typeId = "livingrealms:mine"; mine.state = Plot.State.BUILT; desert.plots.add(mine);
        check(livingrealms.building.BuildingCatalog.get(mine.typeId) != null, "The Mine building exists");
        check(Trade.production(desert).getOrDefault(Items.IRON_INGOT, 0.0) > 0 && Trade.production(plains).getOrDefault(Items.IRON_INGOT, 0.0) == 0,
                    "An NPC town's mine yields iron for trade; a player town's buildings work through the simulation instead");
        desert.plots.clear();
        desert.stock.force(Items.SAND, Trade.SOFT_CAP);
        for (int i = 0; i < 20; i++) Trade.produce(desert, 1.0);
        check(desert.stock.count(Items.SAND) <= Trade.SOFT_CAP + 1, "Production stops at the soft cap: " + desert.stock.count(Items.SAND));

        // ---- prices follow supply and need
        Map<net.minecraft.world.item.Item, Integer> pw = Trade.wants(plains);
        double scarce = Trade.price(plains, pw, Items.GLASS);
        check(Trade.need(plains, pw, Items.GLASS) == 14 && scarce > Trade.base(Items.GLASS), "A town short of glass pays over the base price: " + scarce);
        plains.stock.force(Items.GLASS, 200);
        double glut = Trade.price(plains, pw, Items.GLASS);
        check(glut < Trade.base(Items.GLASS) && glut >= Trade.base(Items.GLASS) * 0.25, "Plentiful glass is cheap, never below a quarter: " + glut);
        plains.stock.take(Items.GLASS, 200);
        Plot site = new Plot(); site.id = 2; site.typeId = "livingrealms:house"; site.state = Plot.State.BUILDING;
        site.blueprintKey = "none"; plains.plots.add(site);
        check(Trade.wants(plains).get(Items.GLASS) == 14, "A site without a blueprint adds no wants");
        plains.plots.clear();

        // ---- routes
        Wars.open(server, m, a, b, now, false);
        Trade.routes(server, m, now);
        Trade.Route pd = m.trade.route(plains.id, desert.id), pe = m.trade.route(plains.id, enemy.id), de = m.trade.route(desert.id, enemy.id);
        check(pd != null && pd.active && pd.blocked.isEmpty(), "Neighbouring towns at peace form an open route");
        check(pe != null && !pe.active && pe.blocked.equals("At war"), "Towns at war keep no open route: " + (pe == null ? null : pe.blocked));
        check(de != null && de.active, "A neutral NPC town trades with both sides");
        Settlement far = town(m, null, "Faraway", "minecraft:plains", 8192 + Trade.RANGE + 400, 0);
        Trade.routes(server, m, now);
        check(m.trade.routes.values().stream().noneMatch(r -> r.involves(far.id)), "Towns out of reach form no route");
        m.remove(far);

        // ---- dispatch: the desert's spare glass for the plains town's shortage
        desert.stock.force(Items.GLASS, 100 - desert.stock.count(Items.GLASS));
        int sandBefore = desert.stock.count(Items.SAND);
        Trade.Caravan c = Trade.dispatch(server, m, pd, now);
        check(c != null && c.from.equals(desert.id) && c.to.equals(plains.id) && c.cargo.getOrDefault(Items.GLASS, 0) == 14,
                "A caravan loads exactly the glass the plains town is short of: " + (c == null ? null : c.cargo));
        check(desert.stock.count(Items.GLASS) == 86 && desert.stock.count(Items.SAND) == sandBefore - c.cargo.getOrDefault(Items.SAND, 0), "Its cargo leaves the stockpile");
        check(c.status == Trade.Status.LOADING && c.value > 0 && c.guards == 0, "It loads first; a town without soldiers sends it unguarded");
        check(Trade.dispatch(server, m, pd, now + Trade.COOLDOWN + 1) == null, "One caravan at a time on a route");

        // ---- simulated travel
        Caravans.riskScale = 0;
        double before = Trade.price(plains, Trade.wants(plains), Items.GLASS);
        double wealth = desert.wealth;
        Caravans.tickAll(server, m, now + Caravans.LOAD_TICKS - 20, 20);
        check(c.status == Trade.Status.LOADING, "Still loading before its time");
        long t = now + Caravans.LOAD_TICKS;
        Caravans.tickAll(server, m, t, 20);
        check(c.status == Trade.Status.ROAD && !c.physical, "It sets out; with nobody near, its travel is simulated");
        int steps = 0;
        while (!c.done() && steps++ < 1000) { t += 20; Caravans.tickAll(server, m, t, 20); }
        double expect = Caravans.distance(desert, plains) / (Caravans.SPEED * 20);
        check(c.status == Trade.Status.ARRIVED && Math.abs(steps - expect) <= 2, "A simulated caravan arrives at walking pace: " + steps + " steps for " + expect);
        check(plains.stock.count(Items.GLASS) == 14 && desert.wealth > wealth, "Goods reach the buyer; the seller earns: " + desert.wealth);
        double after = Trade.price(plains, Trade.wants(plains), Items.GLASS);
        check(after < before, "The price of glass fell once it arrived: " + before + " -> " + after);
        check(m.trade.ledger(plains.id).prosperity > 0 && m.trade.ledger(desert.id).prosperity > 0 && pd.trips == 1, "Both towns prosper from the trip");
        check(Trade.growthBonus(plains) > 0 && Trade.buildBonus(plains) > 1 && Simulation.speed(plains) > 1, "A trading town grows and builds faster");
        double p0 = m.trade.ledger(plains.id).prosperity;
        pd.lastSent = t;
        Trade.step(server, m, t, 1.0);
        m.trade.caravans.removeIf(x -> x != c);
        check(m.trade.ledger(plains.id).prosperity < p0, "Prosperity fades without trade");

        // ---- export bans
        plains.stock.take(Items.GLASS, 14);
        pd.lastSent = 0;
        m.trade.ledger(desert.id).bans.add(Items.GLASS);
        Trade.Caravan banned = Trade.dispatch(server, m, pd, t);
        check(banned == null || !banned.cargo.containsKey(Items.GLASS), "A banned good is never exported");
        if (banned != null) m.trade.caravans.remove(banned);
        m.trade.ledger(desert.id).bans.remove(Items.GLASS);

        // ---- danger
        Trade.Caravan risky = new Trade.Caravan();
        risky.from = plains.id; risky.to = desert.id;
        check(Caravans.raiders(m, plains, desert) == enemy, "A road past a town at war is watched by its raiders");
        double r0 = Caravans.tripRisk(m, risky, plains, desert, false);
        risky.guards = 2;
        double r2 = Caravans.tripRisk(m, risky, plains, desert, false), re = Caravans.tripRisk(m, risky, plains, desert, true);
        check(r0 > Caravans.RAIDERS && Math.abs(r2 - r0 / 2) < 1e-9 && Math.abs(re - r2 / 2) < 1e-9, "Guards and an escort lower the odds: " + r0 + " " + r2 + " " + re);
        risky.guards = 0;
        check(Caravans.tripRisk(m, risky, desert, plains, false) < Caravans.RAIDERS, "A neutral town's caravan has no raiders to fear");

        Trade.Caravan doomed = caravan(m, desert, plains, 0, t);
        Caravans.ambush(server, level, m, doomed, false, 0.0, t);
        check(doomed.status == Trade.Status.LOST && doomed.cargo.isEmpty() && m.trade.ledger(desert.id).lost == 1 && doomed.note.contains("ambushed"),
                "An unguarded caravan that loses the roll is lost with its goods");
        check(desert.log.stream().anyMatch(l -> l.contains("was lost")), "Its town hears of it");
        Trade.Caravan tough = caravan(m, desert, plains, 2, t);
        Caravans.ambush(server, level, m, tough, false, 0.5, t);
        check(tough.status == Trade.Status.ROAD && tough.cargo.get(Items.SAND) == 15 && tough.note.startsWith("Fought off"),
                "Guards beat off an ambush; a quarter of the goods are lost: " + tough.cargo);
        Caravans.ambush(server, level, m, tough, false, 0.5, t);
        check(tough.ambushes == 2, "Ambushes counted");
        m.trade.caravans.remove(tough);

        // ---- save and load
        m.trade.ledger(plains.id).bans.add(Items.WHEAT);
        Trade.Caravan onRoad = caravan(m, desert, plains, 1, t);
        onRoad.progress = 0.4;
        pd.closedBy.add(plains.id);
        var back = SettlementManager.load(m.save(new CompoundTag()));
        Trade.Caravan restored = back.trade.caravan(onRoad.id);
        check(restored != null && restored.status == Trade.Status.ROAD && restored.cargo.equals(onRoad.cargo) && restored.progress == 0.4 && restored.guards == 1,
                "A caravan on the road survives save and load");
        check(back.trade.routes.size() == m.trade.routes.size() && back.trade.route(plains.id, desert.id).closedBy.contains(plains.id)
                && back.trade.route(plains.id, desert.id).trips == pd.trips, "Routes, closures and trips survive save and load");
        check(back.trade.ledger(plains.id).bans.contains(Items.WHEAT) && Math.abs(back.trade.ledger(desert.id).prosperity - m.trade.ledger(desert.id).prosperity) < 1e-9
                && back.trade.ledger(desert.id).lost == 1, "Bans, prosperity and losses survive save and load");
        check(!new SettlementManager().save(new CompoundTag()).contains("trade") && SettlementManager.load(new CompoundTag()).trade.isEmpty(),
                "No trade, no save change; old saves load with no trade");
        Trade.routes(server, m, t);
        check(!pd.active && pd.blocked.startsWith("Closed by"), "A ruler can close a route");
        pd.closedBy.clear();
        m.trade.caravans.remove(onRoad);

        // ---- attacking a caravan is a hostile act
        Trade.Caravan victim = caravan(m, desert, plains, 0, t);
        Trade.hostileAct(server, m, victim, a, t);
        Trade.routes(server, m, t);
        check(m.trade.grievances.containsKey(Statecraft.pair(a, desert.kingdomId())) && !pd.active && pd.blocked.startsWith("Suspended"),
                "A first attack suspends trade between the kingdoms");
        check(m.state.war(a, desert.kingdomId()) == null, "One attack is not yet war");
        Trade.hostileAct(server, m, victim, a, t + 100);
        check(m.state.war(a, desert.kingdomId()) != null, "A second attack while trade is suspended means war");
        Trade.hostileAct(server, m, victim, desert.kingdomId(), t);
        check(m.trade.grievances.size() == 1, "A kingdom cannot offend itself");
        Trade.Caravan gone = caravan(m, plains, desert, 0, t);
        m.remove(desert);
        Caravans.tickAll(server, m, t + 20, 20);
        check(gone.status == Trade.Status.LOST && gone.note.contains("market is gone"), "A caravan whose market is gone is lost, not stuck");
        Caravans.riskScale = 1;
    }

    private static Trade.Caravan caravan(SettlementManager m, Settlement from, Settlement to, int guards, long now) {
        Trade.Caravan c = new Trade.Caravan();
        c.from = from.id; c.to = to.id; c.status = Trade.Status.ROAD; c.since = now; c.guards = guards;
        c.cargo.put(Items.SAND, 20); c.value = 4;
        m.trade.caravans.add(c);
        return c;
    }

    /** A walking caravan in the real world: trader, pack animal and guard; stale bodies culled; ambush; death; then a full walk. */
    private static void physical(ServerLevel level) {
        MinecraftServer server = level.getServer();
        var m = SettlementManager.get(server);
        Caravans.riskScale = 0;
        m.trade.nextStep = Long.MAX_VALUE;
        Walk w = new Walk();
        w.m = m;
        w.from = town(m, null, "Caravan Start", "minecraft:plains", 12288, 0);
        w.to = town(m, null, "Caravan End", "minecraft:plains", 12352, 0);
        walk = w;
        for (int cx = 12256 >> 4; cx <= 12384 >> 4; cx++) for (int cz = -2; cz <= 1; cz++) {
            level.setChunkForced(cx, cz, true);
            w.forced.add(new long[]{cx, cz});
        }
        for (int x = 12260; x <= 12380; x++) for (int z = -12; z <= 12; z++) {
            level.setBlock(new BlockPos(x, 99, z), Blocks.STONE.defaultBlockState(), 2);
            for (int y = 100; y < 106; y++) level.setBlock(new BlockPos(x, y, z), Blocks.AIR.defaultBlockState(), 2);
        }
        long now = server.overworld().getGameTime();
        BlockPos start = Trade.market(w.from, w.to);
        check(start.getX() == 12288 + 15 && Trade.market(w.to, w.from).getX() == 12352 - 15, "Caravans load on the market place, on the side facing the road");

        // stale bodies: a tagged entity whose caravan travels simulated is dropped as it loads
        Trade.Caravan sim = caravan(m, w.from, w.to, 0, now);
        WanderingTrader stray = EntityType.WANDERING_TRADER.create(level);
        stray.moveTo(start.getX() + .5, 100, start.getZ() + .5, 0, 0);
        Caravans.tag(stray, sim);
        check(!level.addFreshEntity(stray), "A stale caravan body never rejoins the world");

        // a caravan losing its trader to unloaded land carries on, simulated, from where it was
        check(Caravans.materialize(level, sim, w.from, w.to, start, now) && sim.physical, "A caravan takes to the road for real");
        Entity simTrader = level.getEntity(sim.trader);
        simTrader.teleportTo(start.getX() + 10.5, 100, start.getZ() + .5);
        Caravans.walk(server, level, m, sim, w.from, w.to, now + 20, Caravans.distance(w.from, w.to));
        double seen = sim.progress;
        UUID simBeast = sim.beast;
        simTrader.discard();
        Caravans.walk(server, level, m, sim, w.from, w.to, now + 40, Caravans.distance(w.from, w.to));
        check(!sim.physical && sim.status == Trade.Status.ROAD && sim.progress >= seen && seen > 0.2 && sim.trader == null && level.getEntity(simBeast) == null,
                "A trader gone from loaded land is never a lost caravan: it travels on simulated from " + seen);
        m.trade.caravans.remove(sim);

        // ambush and death: the goods spill and the caravan is lost; a player's blow is a hostile act
        Trade.Caravan doomed = caravan(m, w.from, w.to, 1, now);
        check(Caravans.materialize(level, doomed, w.from, w.to, start, now), "Second caravan walks");
        Entity trader = level.getEntity(doomed.trader), beast = level.getEntity(doomed.beast), guard = level.getEntity(doomed.guardIds.get(0));
        check(trader instanceof WanderingTrader && beast instanceof net.minecraft.world.entity.animal.horse.AbstractChestedHorse chest && chest.hasChest()
                && ((Mob) beast).getLeashHolder() == trader && livingrealms.military.RecruitsBridge.isRecruit(guard),
                "A trader leads a laden pack animal on a lead, with a soldier as guard");
        check(trader.getPersistentData().getUUID(Caravans.TAG).equals(doomed.id) && guard.getPersistentData().hasUUID(Caravans.TAG), "Every part of it knows its caravan");
        Caravans.ambush(server, level, m, doomed, true, 0, now);
        List<Mob> band = level.getEntitiesOfClass(Mob.class, trader.getBoundingBox().inflate(24), x -> x.getTarget() == trader);
        check(!band.isEmpty() && doomed.note.startsWith("Ambushed") && doomed.status == Trade.Status.ROAD, "A walking caravan is attacked by a real band: " + band.size());
        band.forEach(Entity::discard);
        var raider = FakePlayerFactory.get(level, new GameProfile(UUID.randomUUID(), "TradeTestRaider"));
        ((LivingEntity) trader).hurt(level.damageSources().playerAttack(raider), 1000);
        check(doomed.status == Trade.Status.LOST && doomed.cargo.isEmpty() && !doomed.physical, "Killing the trader loses the caravan");
        int spilled = level.getEntitiesOfClass(ItemEntity.class, new AABB(trader.blockPosition()).inflate(3), i -> i.getItem().is(Items.SAND))
                .stream().mapToInt(i -> i.getItem().getCount()).sum();
        check(spilled == 20, "Its goods drop where it fell: " + spilled);
        check(level.getEntity(guard.getUUID()) == null && level.getEntity(beast.getUUID()) == null, "The rest of the caravan goes home");
        check(m.trade.grievances.containsKey(Statecraft.pair(raider.getUUID(), w.from.kingdomId())), "Killing a caravan's trader counts as a hostile act");
        level.getEntitiesOfClass(ItemEntity.class, new AABB(trader.blockPosition()).inflate(3)).forEach(Entity::discard);
        m.trade.grievances.clear();
        m.trade.caravans.remove(doomed);

        // the full walk, on real ticks: the caravan must reach the other market by itself
        Trade.Caravan c = caravan(m, w.from, w.to, 1, now);
        c.cargo.clear(); c.cargo.put(Items.GLASS, 20);
        w.c = c;
        w.glassBefore = w.to.stock.count(Items.GLASS);
        check(Caravans.materialize(level, c, w.from, w.to, start, now), "Walking caravan spawned");
        var saved = SettlementManager.load(m.save(new CompoundTag())).trade.caravan(c.id);
        check(saved != null && saved.physical && c.trader.equals(saved.trader) && c.beast.equals(saved.beast) && saved.guardIds.equals(c.guardIds),
                "A walking caravan's trader, animal and guards survive save and load");
        w.started = now;
        w.deadline = now + 2400;
    }

    private static void arrived(ServerLevel level) {
        Walk w = walk;
        long took = level.getGameTime() - w.started;
        LivingRealms.LOG.info("Trade self test: the walking caravan covered {} blocks in {} ticks", (int) Caravans.distance(w.from, w.to), took);
        check(took >= 60, "It walked there rather than appearing: " + took + " ticks");
        check(w.to.stock.count(Items.GLASS) == w.glassBefore + 20, "The walking caravan delivered its goods");
        check(!w.c.physical && w.c.trader == null && level.getEntitiesOfClass(WanderingTrader.class,
                new AABB(Trade.market(w.to, w.from)).inflate(16), t -> t.getPersistentData().hasUUID(Caravans.TAG)).isEmpty(),
                "On arrival the caravan's bodies leave the world");
    }

    private TradeSelfTest() {}
}
