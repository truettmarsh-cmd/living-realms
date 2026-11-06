package livingrealms.military;

import livingrealms.LivingRealms;
import livingrealms.chronicle.Chronicle;
import livingrealms.settlement.Merit;
import livingrealms.settlement.Settlement;
import livingrealms.settlement.SettlementManager;
import livingrealms.settlement.Tier;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.BlockParticleOption;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.TextColor;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.tags.DamageTypeTags;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.damagesource.DamageTypes;
import net.minecraft.world.effect.MobEffectCategory;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.attributes.Attribute;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.AbstractArrow;
import net.minecraft.world.entity.projectile.Arrow;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.EntityJoinLevelEvent;
import net.minecraftforge.event.entity.EntityLeaveLevelEvent;
import net.minecraftforge.event.entity.living.LivingAttackEvent;
import net.minecraftforge.event.entity.living.LivingDeathEvent;
import net.minecraftforge.event.entity.living.LivingEquipmentChangeEvent;
import net.minecraftforge.event.entity.living.LivingHurtEvent;
import net.minecraftforge.event.entity.living.LivingKnockBackEvent;
import net.minecraftforge.event.server.ServerStoppedEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.server.ServerLifecycleHooks;

import javax.annotation.Nullable;
import java.util.*;

/**
 * Heroes (docs/heroes.md): the recruit endgame. A town soldier with a legendary mastery, every skill point and enough
 * merit or battles behind it can be Ascended for a one-time price. A Hero has a gold name, +20% max health, no
 * knockback, no fear, an aura that steadies the recruits around it, Valor levels past the top skill rank, and its
 * path's ultimate ability, used on its own in battle every two minutes. Its fall is told across the server.
 * Hero damage and defence bonuses are their own budget (HERO_CAP, HERO_DEFENCE_CAP), on top of every other budget.
 * State is saved per recruit UUID in HeroData, not in the town.
 */
@Mod.EventBusSubscriber(modid = LivingRealms.MODID)
public final class Heroes {
    // ------------------------------------------------------------------ numbers
    public static final int ASCEND_EMERALDS = 128, BATTLES_NEEDED = 10, MAX_CAP = 5;
    public static final float MERIT_NEEDED = Merit.THRESHOLD;
    public static final double HEALTH_BONUS = .20, VALOR_DAMAGE = .01, VALOR_HEALTH = .01, CHARGE_BONUS = .20, BULWARK_REDUCTION = .50;
    /** The Hero budgets: Valor and Royal Charge share HERO_CAP; Bulwark is the only reduction. */
    public static final double HERO_CAP = .30, HERO_DEFENCE_CAP = .50;
    public static final int VALOR_MAX = 10, VALOR_STEP = 50;
    /** A hero's morale never stays under HERO_MORALE; every 5s its owner's recruits within AURA_RANGE below AURA_MORALE gain AURA_GAIN. */
    public static final double AURA_RANGE = 12;
    public static final float HERO_MORALE = 60, AURA_MORALE = 50, AURA_GAIN = 5;
    /** Recruits near a hero fall back at 20% health instead of 30% (45% when shaken). */
    public static final double INSPIRED_FALL_BACK = .2;
    public static final int HEIRLOOM_XP = 150, BATTLE_QUIET = 600;
    public static final int BLADE_STORM_TICKS = 80, BULWARK_TICKS = 160, RAIN_TICKS = 100, CHARGE_TICKS = 120, PIN_TICKS = 60;
    public static final double BLADE_STORM_RADIUS = 3.5, BULWARK_RADIUS = 5, EARTHSHAKER_RADIUS = 6, IMPALE_RANGE = 12, RAIN_RADIUS = 4, MIRACLE_RADIUS = 10;
    public static final float BLADE_STORM_DAMAGE = 3, EARTHSHAKER_DAMAGE = 6, IMPALE_DAMAGE = 8, RAIN_DAMAGE = 3;
    static final String HEIRLOOM = "LivingRealmsHeirloom", ULTIMATE_SHOT = "LivingRealmsUltimate", BULWARKED = "LivingRealmsBulwarked";
    private static final UUID HEALTH = UUID.fromString("7c3e9a10-4b2d-4f7e-8a51-3d6c2b9e1f01");
    private static final UUID RESIST = UUID.fromString("7c3e9a10-4b2d-4f7e-8a51-3d6c2b9e1f03");
    private static final TextColor GOLD = TextColor.fromLegacyFormat(ChatFormatting.GOLD);

    /** In memory: loaded heroes, ultimates still running, riders still charging, the last things said (for the self-test). */
    private static final Map<UUID, Entity> LOADED = new HashMap<>();
    private static final List<Active> ACTIVE = new ArrayList<>();
    private static final Map<UUID, Long> CHARGING = new HashMap<>();
    static String lastFall = "", lastAscension = "", lastClaim = "";
    /** Ultimates deal their own damage; nothing they do starts another. */
    private static boolean busy;

    static final class Active {
        final Ultimate u;
        final UUID hero, owner;
        final ResourceKey<Level> dim;
        Vec3 center;
        final long until;
        long next;
        Active(Ultimate u, UUID hero, UUID owner, ResourceKey<Level> dim, Vec3 center, long start, long until) {
            this.u = u; this.hero = hero; this.owner = owner; this.dim = dim; this.center = center; this.next = start; this.until = until;
        }
    }

    // ------------------------------------------------------------------ cap

    /** The Citadel of Heroes adds CITADEL_BONUS places on top of the 1–5 cap. */
    public static int capBonus(UUID owner) { return livingrealms.wonders.Wonders.has(owner, livingrealms.wonders.Wonder.CITADEL) ? CITADEL_BONUS : 0; }

    /** Extra hero places while the kingdom holds the Citadel of Heroes (docs/wonders.md). */
    public static final int CITADEL_BONUS = 2;

    /** Cities (rank City or Capital) the player holds. */
    public static int cities(MinecraftServer server, UUID owner) {
        int n = 0;
        for (Settlement s : SettlementManager.get(server).ownedBy(owner)) if (s.tier.ordinal() >= Tier.CITY.ordinal()) n++;
        return n;
    }

    /** One hero per city held, at least 1 and at most 5, plus capBonus. */
    public static int cap(MinecraftServer server, UUID owner) { return Math.min(MAX_CAP, Math.max(1, cities(server, owner))) + capBonus(owner); }

    /** Heroes the player has now (one discarded for good holds no place). */
    public static int count(MinecraftServer server, UUID owner) {
        int n = 0;
        for (var e : HeroData.get(server).all()) if (owner.equals(e.getValue().owner) && !e.getValue().removed) n++;
        return n;
    }

    @Nullable
    public static HeroData.Hero hero(MinecraftServer server, UUID recruit) { return HeroData.get(server).hero(recruit); }

    public static boolean isHero(UUID recruit) {
        MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
        return server != null && hero(server, recruit) != null;
    }

    public static String fullName(HeroData.Hero h) { return h.name + (h.epithet.isEmpty() ? "" : " " + h.epithet); }

    // ------------------------------------------------------------------ valor

    /** Total Valor XP for a level: 100, 300, 600 … 5500 for level 10. */
    public static int valorNeeded(int level) { return VALOR_STEP * level * (level + 1); }

    public static int valorLevel(int xp) {
        int l = 0;
        while (l < VALOR_MAX && xp >= valorNeeded(l + 1)) l++;
        return l;
    }

    public static String stars(int valor) { return "★".repeat(Math.max(0, valor)) + "☆".repeat(Math.max(0, VALOR_MAX - valor)); }

    /**
     * Combat XP a Hero at the top skill rank couldn't take goes into Valor (SoldierRanks.grant). Trickle XP still counts
     * against the soldier's daily cap. Returns the Valor XP gained.
     */
    public static int valor(Settlement s, Settlement.Soldier so, @Nullable Entity e, int amount, boolean trickle, long now) {
        MinecraftServer server = e != null && e.getServer() != null ? e.getServer() : ServerLifecycleHooks.getCurrentServer();
        if (server == null || amount <= 0) return 0;
        HeroData data = HeroData.get(server);
        HeroData.Hero h = data.hero(so.entity);
        if (h == null || h.valorXp >= valorNeeded(VALOR_MAX)) return 0;
        if (trickle) {
            long day = now / SoldierRanks.DAY;
            if (so.trickleDay != day) { so.trickleDay = day; so.trickleXp = 0; }
            amount = Math.min(amount, SoldierRanks.DAILY_TRICKLE_CAP - so.trickleXp);
            if (amount <= 0) return 0;
            so.trickleXp += amount;
        }
        int before = h.valor();
        amount = Math.min(amount, valorNeeded(VALOR_MAX) - h.valorXp);
        h.valorXp += amount;
        data.setDirty();
        s.dirty = true;
        int after = h.valor();
        if (after > before) {
            String msg = fullName(h) + " reached Valor " + after + " " + stars(after);
            s.log(msg);
            ServerPlayer owner = s.owner == null ? null : server.getPlayerList().getPlayer(s.owner);
            if (owner != null) owner.sendSystemMessage(Component.literal("✦ " + msg).withStyle(ChatFormatting.GOLD));
            if (e instanceof LivingEntity le) {
                perks(le, h);
                if (le.level() instanceof ServerLevel level) fx(level, le.position().add(0, le.getBbHeight() * .7, 0), ParticleTypes.WAX_ON, 20, SoundEvents.PLAYER_LEVELUP, .8f);
            }
        }
        return amount;
    }

    // ------------------------------------------------------------------ ascension

    /** What ascension asks for, met or not: {label, detail, met}. */
    record Need(String label, String detail, boolean met) {}

    static List<Need> needs(MinecraftServer server, Settlement s, Settlement.Soldier so, long now) {
        PathSkill m = SoldierPaths.mastery(so);
        Merit.decay(so.merit, now);
        int battles = battles(server, s.owner, so.entity);
        return List.of(
                new Need("Legendary mastery", m == null ? "None yet" : m.label, m != null),
                new Need("Every skill point", "Skill rank " + SoldierRanks.rank(so) + " / " + SoldierSkills.MAX_POINTS, so.skillXp >= SoldierRanks.MAX_XP),
                new Need("Proven in battle", "Merit " + (int) so.merit.merit + " / " + (int) MERIT_NEEDED + " or " + battles + " / " + BATTLES_NEEDED + " battles survived",
                        so.merit.merit >= MERIT_NEEDED || battles >= BATTLES_NEEDED));
    }

    /** Reported battles the recruit lived through: counted since 0.12, or found in the owner's kept reports, whichever is more. */
    public static int battles(MinecraftServer server, @Nullable UUID owner, UUID recruit) {
        int n = HeroData.get(server).battles(recruit);
        if (owner == null) return n;
        int kept = 0;
        for (var r : livingrealms.battle.BattleData.get(server).of(owner))
            for (var f : r.fighters) if (recruit.equals(f.id) && !f.fell) kept++;
        return Math.max(n, kept);
    }

    /** A battle report was written: everyone in it who lived has survived one more battle. */
    public static void survived(MinecraftServer server, livingrealms.battle.BattleReport r) {
        HeroData data = HeroData.get(server);
        for (var f : r.fighters) if (!f.fell && f.id != null) data.survived(f.id);
    }

    /** Why this soldier can't be ascended now (requirements and cap, not the price), or null. */
    @Nullable
    public static String blocker(MinecraftServer server, Settlement s, Settlement.Soldier so, long now) {
        if (hero(server, so.entity) != null) return so.name + " is already a Hero";
        for (Need n : needs(server, s, so, now)) if (!n.met()) return "Needs " + n.label().toLowerCase(Locale.ROOT) + ": " + n.detail();
        if (s.owner == null) return "Only a player's recruits can become Heroes";
        int cap = cap(server, s.owner), have = count(server, s.owner);
        if (have >= cap) {
            int bonus = capBonus(s.owner);
            return "You already have " + have + (have == 1 ? " Hero" : " Heroes") + " (one per city you hold, at most " + MAX_CAP
                    + (bonus > 0 ? ", +" + bonus + " from the Citadel of Heroes" : "") + ": " + cap + " now)";
        }
        return null;
    }

    /** The rare item: a nether star, or an Ice and Fire dragon heart when that mod is present. */
    public static List<Item> rareItems() {
        List<Item> out = new ArrayList<>(List.of(Items.NETHER_STAR));
        for (String id : new String[]{"fire_dragon_heart", "ice_dragon_heart", "lightning_dragon_heart"}) {
            Item it = ForgeRegistries.ITEMS.getValue(new ResourceLocation("iceandfire", id));
            if (it != null && it != Items.AIR) out.add(it);
        }
        return out;
    }

    public static String cost() {
        return ASCEND_EMERALDS + " emeralds + a Nether Star" + (rareItems().size() > 1 ? " or a dragon heart" : "");
    }

    static int held(@Nullable ServerPlayer p, Item item) {
        return p == null ? 0 : p.getInventory().clearOrCountMatchingItems(st -> st.is(item), 0, p.inventoryMenu.getCraftSlots());
    }

    /** The settlement action "ascendHero" (Path page). */
    @Nullable
    public static String ascend(ServerPlayer player, Settlement s, CompoundTag d) {
        if (!player.getUUID().equals(s.owner)) return "Only this town's owner can raise its Heroes";
        if (!d.hasUUID("soldier")) return "Unknown recruit";
        Settlement.Soldier so = null;
        for (Settlement.Soldier x : s.soldiers) if (x.entity.equals(d.getUUID("soldier"))) so = x;
        if (so == null) return "Unknown recruit";
        Entity e = SoldierProgression.findEntity(player.server, so.entity);
        return ascend(player.server, player, s, so, e, player.server.overworld().getGameTime());
    }

    /**
     * Ascends a soldier. Emeralds come from town stock; the rare item from the payer's inventory first, then town stock.
     * A null payer pays from town stock only. Returns an error, or null.
     */
    @Nullable
    public static String ascend(MinecraftServer server, @Nullable ServerPlayer payer, Settlement s, Settlement.Soldier so, @Nullable Entity e, long now) {
        String why = blocker(server, s, so, now);
        if (why != null) return why;
        if (!(e instanceof LivingEntity le) || !e.isAlive() || s.owner == null || !s.owner.equals(RecruitsBridge.owner(e)))
            return "Your recruit must be loaded and still belong to you";
        if (!s.stock.has(Items.EMERALD, ASCEND_EMERALDS)) return "Ascension costs " + cost() + "; emeralds come from town stock (" + s.stock.count(Items.EMERALD) + " there)";
        Item rare = null;
        boolean fromPlayer = false;
        for (Item it : rareItems()) if (held(payer, it) > 0) { rare = it; fromPlayer = true; break; }
        if (rare == null) for (Item it : rareItems()) if (s.stock.has(it, 1)) { rare = it; break; }
        if (rare == null) return "Ascension costs " + cost() + "; bring the rare item in your inventory or put it in town stock";
        s.stock.take(Items.EMERALD, ASCEND_EMERALDS);
        if (fromPlayer) { final Item r = rare; payer.getInventory().clearOrCountMatchingItems(st -> st.is(r), 1, payer.inventoryMenu.getCraftSlots()); }
        else s.stock.take(rare, 1);
        HeroData data = HeroData.get(server);
        HeroData.Hero h = data.add(e.getUUID());
        PathSkill m = SoldierPaths.mastery(so);
        h.owner = s.owner; h.name = so.name; h.epithet = m == null ? "" : m.epithet(); h.mastery = m == null ? "" : m.name();
        h.path = so.path; h.ascended = now; h.ultimateReady = now; h.seen = now;
        data.setDirty();
        LOADED.put(e.getUUID(), e);
        perks(le, h);
        le.setHealth(le.getMaxHealth());
        String name = fullName(h);
        s.log(name + " ascended as a Hero");
        legend(s, name + " was raised up as a Hero of " + s.name + ", and from that day " + (so.path == null ? "fought" : "fought as no " + so.path.label.toLowerCase(Locale.ROOT) + " had fought before") + ".");
        lastAscension = "✦ " + name + " of " + s.name + " has ascended as a Hero.";
        server.getPlayerList().broadcastSystemMessage(Component.literal(lastAscension).withStyle(ChatFormatting.GOLD), false);
        if (e.level() instanceof ServerLevel level) {
            level.sendParticles(ParticleTypes.TOTEM_OF_UNDYING, e.getX(), e.getY(1), e.getZ(), 60, .5, .8, .5, .4);
            for (int i = 0; i < 12; i++) level.sendParticles(ParticleTypes.END_ROD, e.getX(), e.getY() + i * .4, e.getZ(), 2, .1, .1, .1, .01);
            level.playSound(null, e.blockPosition(), SoundEvents.UI_TOAST_CHALLENGE_COMPLETE, SoundSource.NEUTRAL, 1f, 1f);
            level.playSound(null, e.blockPosition(), SoundEvents.BEACON_ACTIVATE, SoundSource.NEUTRAL, 1f, 1.2f);
        }
        s.dirty = true;
        return null;
    }

    /** A LEGEND line in the town chronicle with its own words (the LEGEND template is for masteries). */
    static void legend(Settlement s, String text) {
        Chronicle.Entry e = Chronicle.record(s, Chronicle.Kind.NOTE, text);
        int i = s.chronicle.entries.lastIndexOf(e);
        if (i >= 0) s.chronicle.entries.set(i, new Chronicle.Entry(e.day(), Chronicle.Kind.LEGEND, e.text()));
    }

    // ------------------------------------------------------------------ perks

    static boolean hasHealthBonus(net.minecraft.world.entity.ai.attributes.AttributeInstance hp) { return hp.getModifier(HEALTH) != null; }

    /** Takes the Hero health and knockback perks off a recruit that is no longer a Hero. */
    public static void clearPerks(LivingEntity e) {
        for (var at : List.of(Attributes.MAX_HEALTH, Attributes.KNOCKBACK_RESISTANCE)) {
            var instance = e.getAttribute(at);
            if (instance != null) { instance.removeModifier(HEALTH); instance.removeModifier(RESIST); }
        }
        if (e.getHealth() > e.getMaxHealth()) e.setHealth(e.getMaxHealth());
    }

    /** +20% max health (+1% more a Valor level), knockback immunity and the gold name. Called every second for loaded heroes. */
    public static void perks(LivingEntity e, HeroData.Hero h) {
        modifier(e, Attributes.MAX_HEALTH, HEALTH, HEALTH_BONUS + h.valor() * VALOR_HEALTH, AttributeModifier.Operation.MULTIPLY_TOTAL);
        modifier(e, Attributes.KNOCKBACK_RESISTANCE, RESIST, 1, AttributeModifier.Operation.ADDITION);
        Component name = e.getCustomName();
        if (name == null || !GOLD.equals(name.getStyle().getColor())) {
            // an unnamed recruit would read "Recruit": give it a lasting name of its own, the same one every time
            String text = name != null ? name.getString() : heroName(e.getUUID());
            e.setCustomName(Component.literal(text).withStyle(ChatFormatting.GOLD));
        }
        e.setCustomNameVisible(true);
        if (e.getHealth() > e.getMaxHealth()) e.setHealth(e.getMaxHealth());
    }

    static String heroName(UUID id) {
        net.minecraft.util.RandomSource r = net.minecraft.util.RandomSource.create(id.getMostSignificantBits() ^ id.getLeastSignificantBits());
        boolean female = r.nextBoolean();
        return livingrealms.citizen.PersonNames.first("plains", female, r) + " " + livingrealms.citizen.PersonNames.family("plains", female, r);
    }

    private static void modifier(LivingEntity e, Attribute attribute, UUID id, double amount, AttributeModifier.Operation op) {
        var instance = e.getAttribute(attribute);
        if (instance == null) return;
        var old = instance.getModifier(id);
        if (old != null && old.getAmount() == amount && old.getOperation() == op) return;
        if (old != null) instance.removeModifier(id);
        if (amount == 0) return;
        // bonus health is saved with the entity, or a reload clamps its health to the unbuffed maximum
        var mod = new AttributeModifier(id, "Living Realms hero", amount, op);
        if (attribute == Attributes.MAX_HEALTH) instance.addPermanentModifier(mod); else instance.addTransientModifier(mod);
    }

    /** Once a second: perks, fear, the aura, the glow, and Miracle's battle ending. */
    static void pulse(MinecraftServer server, Entity e, HeroData.Hero h, long time) {
        if (!(e instanceof LivingEntity le) || !(e.level() instanceof ServerLevel level)) return;
        HeroData data = HeroData.get(server);
        h.seen = time; h.removed = false;
        UUID owner = RecruitsBridge.owner(e);
        if (owner != null) h.owner = owner;
        var rec = SoldierProgression.record(server, e);
        if (rec != null) { h.name = rec.soldier().name; if (rec.soldier().path != null) h.path = rec.soldier().path; }
        perks(le, h);
        // no fear: a hero's morale doesn't stay low and it never panics
        float morale = RecruitsBridge.morale(e);
        if (morale < HERO_MORALE) RecruitsBridge.addMorale(e, HERO_MORALE - morale);
        livingrealms.compat.Compat.steady(e, HERO_MORALE);
        if (time / 20 % 5 == 0) aura(le, h, time);
        if (time / 20 % 2 == 0) level.sendParticles(ParticleTypes.WAX_ON, e.getX(), e.getY() + e.getBbHeight() + .35, e.getZ(), 3, .3, .15, .3, 0);
        if (h.miracleSpent && !CombatClock.within(e.getUUID(), time, BATTLE_QUIET)) h.miracleSpent = false;
        data.setDirty();
    }

    /** The owner's recruits within 12 blocks whose morale is under 50 gain 5. Returns how many. */
    public static int aura(LivingEntity hero, HeroData.Hero h, long time) {
        int n = 0;
        for (LivingEntity x : allies(hero, h.owner, AURA_RANGE)) {
            if (!RecruitsBridge.isRecruit(x) || RecruitsBridge.morale(x) >= AURA_MORALE) continue;
            RecruitsBridge.addMorale(x, AURA_GAIN);
            n++;
        }
        return n;
    }

    /** A recruit within 12 blocks of a loaded hero of the same owner (the hero itself included): it holds the line longer. */
    public static boolean inspired(LivingEntity e) {
        if (LOADED.isEmpty() || !RecruitsBridge.isRecruit(e)) return false;
        UUID owner = RecruitsBridge.owner(e);
        if (owner == null) return false;
        for (Entity hero : LOADED.values())
            if (hero.isAlive() && hero.level() == e.level() && owner.equals(RecruitsBridge.owner(hero)) && hero.distanceToSqr(e) <= AURA_RANGE * AURA_RANGE) return true;
        return false;
    }

    @SubscribeEvent
    public static void knockback(LivingKnockBackEvent event) {
        if (!event.getEntity().level().isClientSide && LOADED.containsKey(event.getEntity().getUUID())) event.setCanceled(true);
    }

    // ------------------------------------------------------------------ combat budget

    /** Valor (+1% a level) and Royal Charge (+20%), capped at HERO_CAP. */
    public static double attack(HeroData data, LivingEntity attacker, long time) {
        HeroData.Hero h = data.hero(attacker.getUUID());
        double bonus = h == null ? 0 : h.valor() * VALOR_DAMAGE;
        if (CHARGING.getOrDefault(attacker.getUUID(), Long.MIN_VALUE) > time) bonus += CHARGE_BONUS;
        return Math.min(HERO_CAP, bonus);
    }

    /** Bulwark: 50% less damage for the owner's recruits (and the owner) inside a dome, capped at HERO_DEFENCE_CAP. */
    public static double defend(LivingEntity victim, long time) {
        double r = 0;
        for (Active a : ACTIVE)
            if (a.u == Ultimate.BULWARK && time <= a.until && victim.level().dimension().equals(a.dim) && friendly(a.owner, victim)
                    && victim.position().distanceToSqr(a.center) <= BULWARK_RADIUS * BULWARK_RADIUS) r = BULWARK_REDUCTION;
        return Math.min(HERO_DEFENCE_CAP, r);
    }

    @SubscribeEvent(priority = EventPriority.LOW)
    public static void hurt(LivingHurtEvent event) {
        if (event.isCanceled() || event.getAmount() <= 0 || !(event.getEntity().level() instanceof ServerLevel level)) return;
        LivingEntity victim = event.getEntity();
        DamageSource source = event.getSource();
        long time = RecruitCombat.now(victim);
        double factor = 1;
        if (source.getEntity() instanceof LivingEntity attacker && attacker != victim && RecruitsBridge.isRecruit(attacker) && CombatClock.foe(attacker, victim)) {
            HeroData data = HeroData.get(level.getServer());
            if (ultimate(source)) {
                HeroData.Hero h = data.hero(attacker.getUUID());
                factor *= 1 + (h == null ? 0 : Math.min(HERO_CAP, h.valor() * VALOR_DAMAGE)); // an ultimate's own damage: raised by Valor alone
            } else factor *= 1 + attack(data, attacker, time);
        }
        if (!ACTIVE.isEmpty() && !source.is(DamageTypeTags.BYPASSES_INVULNERABILITY)) factor *= 1 - defend(victim, time);
        if (factor != 1) event.setAmount((float) (event.getAmount() * factor));
    }

    /** Damage an ultimate deals itself (a Rain arrow, or a Blade Storm, Earthshaker or Impale blow): fixed, so skill, path, bond and decree bonuses leave it alone. */
    public static boolean ultimate(DamageSource source) {
        return busy || source.getDirectEntity() != null && source.getDirectEntity().getPersistentData().getBoolean(ULTIMATE_SHOT);
    }

    /** A Rain arrow hits for RAIN_DAMAGE, whatever its speed, crits or the hero's bow. */
    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void rainDamage(LivingHurtEvent event) {
        Entity direct = event.getSource().getDirectEntity();
        if (direct != null && !event.getEntity().level().isClientSide && direct.getPersistentData().getBoolean(ULTIMATE_SHOT)) event.setAmount(RAIN_DAMAGE);
    }

    /** Rain of Arrows never hurts the hero's own side. */
    @SubscribeEvent
    public static void attacked(LivingAttackEvent event) {
        Entity direct = event.getSource().getDirectEntity();
        if (direct == null || event.getEntity().level().isClientSide || !direct.getPersistentData().getBoolean(ULTIMATE_SHOT)) return;
        UUID owner = event.getSource().getEntity() == null ? null : RecruitsBridge.owner(event.getSource().getEntity());
        if (owner != null && friendly(owner, event.getEntity())) event.setCanceled(true);
    }

    /** The hero's side: its owner and their team-mates, the owner's recruits, pets and tamed mounts, any mount carrying one of those, and townsfolk. */
    static boolean friendly(@Nullable UUID owner, Entity x) {
        if (owner == null) return false;
        if (x instanceof Player p) {
            if (p.getUUID().equals(owner)) return true;
            Player ruler = p.level().getPlayerByUUID(owner);
            return ruler != null && p.isAlliedTo(ruler);
        }
        if (RecruitsBridge.isRecruit(x)) return owner.equals(RecruitsBridge.owner(x));
        if (x instanceof net.minecraft.world.entity.OwnableEntity o && owner.equals(o.getOwnerUUID())) return true;
        if (x instanceof net.minecraft.world.entity.npc.AbstractVillager || x instanceof livingrealms.entity.SettlerEntity) return true;
        for (Entity rider : x.getPassengers()) if (friendly(owner, rider)) return true;
        return false;
    }

    /** The owner's loaded recruits within r blocks, the hero left out. */
    static List<LivingEntity> allies(LivingEntity hero, @Nullable UUID owner, double r) {
        if (owner == null) return List.of();
        return hero.level().getEntitiesOfClass(LivingEntity.class, hero.getBoundingBox().inflate(r),
                x -> x != hero && x.isAlive() && RecruitsBridge.isRecruit(x) && owner.equals(RecruitsBridge.owner(x)) && x.distanceToSqr(hero) <= r * r);
    }

    static List<LivingEntity> foes(Mob hero, Vec3 at, double r) {
        LivingEntity target = hero.getTarget();
        UUID owner = RecruitsBridge.owner(hero);
        return hero.level().getEntitiesOfClass(LivingEntity.class, new AABB(at, at).inflate(r),
                x -> x != hero && x.isAlive() && !friendly(owner, x) && (x == target || SoldierSpecials.hostile(hero, x)) && x.position().distanceToSqr(at) <= r * r);
    }

    /** Damage an ultimate deals: the hero's, but not a weapon hit, so path splashes and specials don't chain off it. */
    static DamageSource source(LivingEntity hero) {
        return new DamageSource(hero.level().registryAccess().registryOrThrow(Registries.DAMAGE_TYPE).getHolderOrThrow(DamageTypes.MOB_ATTACK), null, hero);
    }

    /** One ultimate blow; it lands even inside the target's hurt cooldown, since ultimates strike between ordinary blows. */
    static void hit(LivingEntity hero, LivingEntity x, float damage) {
        x.invulnerableTime = 0;
        x.hurt(source(hero), damage);
    }

    // ------------------------------------------------------------------ ultimates

    public static boolean ready(HeroData.Hero h, Ultimate u, long time) {
        if (u == Ultimate.MIRACLE && h.miracleSpent) return false;
        return time >= h.ultimateReady || h.ultimateReady - time > Ultimate.COOLDOWN; // a stale stamp from a rewound clock never locks forever
    }

    @Nullable
    static SoldierPath path(MinecraftServer server, Entity e, HeroData.Hero h) {
        var rec = SoldierProgression.record(server, e);
        return rec != null && rec.soldier().path != null ? rec.soldier().path : h.path;
    }

    /** Uses the hero's ultimate if it is ready and the fight calls for it. Returns the ultimate used, or null. */
    @Nullable
    public static Ultimate act(MinecraftServer server, Mob hero, HeroData.Hero h, long time) {
        if (busy || !(hero.level() instanceof ServerLevel level)) return null;
        LivingEntity target = hero.getTarget();
        if (target == null || !target.isAlive()) return null;
        Ultimate u = Ultimate.of(path(server, hero, h));
        if (u == null || !ready(h, u, time)) return null;
        double d = hero.distanceTo(target);
        boolean big = target.getMaxHealth() >= 40 || target.getBbWidth() > 1.2f;
        boolean fire = switch (u) {
            case BLADE_STORM -> foes(hero, hero.position(), 4).size() >= 2 || big && d <= 3;
            case BULWARK -> foes(hero, hero.position(), 10).size() >= 3 || wounded(hero, h.owner, 6, .5f, 1);
            case EARTHSHAKER -> foes(hero, hero.position(), EARTHSHAKER_RADIUS).size() >= 3 || big && d <= 4;
            case IMPALE -> d >= 4 && d <= IMPALE_RANGE && hero.hasLineOfSight(target);
            case RAIN_OF_ARROWS -> d >= 8 && d <= 40 && (foes(hero, target.position(), RAIN_RADIUS).size() >= 2 || big);
            case ROYAL_CHARGE -> hero.getVehicle() instanceof LivingEntity mount && !DragonRiders.dragon(mount) && d >= 6 && d <= 28;
            case MIRACLE -> wounded(hero, h.owner, MIRACLE_RADIUS, .3f, 1) || wounded(hero, h.owner, MIRACLE_RADIUS, .5f, 2);
        };
        if (!fire) return null;
        h.ultimateReady = time + Ultimate.COOLDOWN;
        UUID owner = h.owner != null ? h.owner : RecruitsBridge.owner(hero);
        switch (u) {
            case BLADE_STORM -> {
                ACTIVE.add(new Active(u, hero.getUUID(), owner, level.dimension(), hero.position(), time, time + BLADE_STORM_TICKS));
                level.playSound(null, hero.blockPosition(), SoundEvents.PLAYER_ATTACK_SWEEP, SoundSource.NEUTRAL, 1.2f, .6f);
            }
            case BULWARK -> {
                ACTIVE.add(new Active(u, hero.getUUID(), owner, level.dimension(), hero.position(), time, time + BULWARK_TICKS));
                hero.swing(InteractionHand.OFF_HAND);
                level.playSound(null, hero.blockPosition(), SoundEvents.BEACON_ACTIVATE, SoundSource.NEUTRAL, 1.2f, 1.4f);
                level.playSound(null, hero.blockPosition(), SoundEvents.SHIELD_BLOCK, SoundSource.NEUTRAL, 1f, .7f);
                dome(level, hero.position(), 60);
            }
            case EARTHSHAKER -> earthshaker(level, hero);
            case IMPALE -> impale(level, hero, target);
            case RAIN_OF_ARROWS -> {
                ACTIVE.add(new Active(u, hero.getUUID(), owner, level.dimension(), target.position(), time, time + RAIN_TICKS));
                hero.swing(InteractionHand.MAIN_HAND);
                level.playSound(null, hero.blockPosition(), SoundEvents.CROSSBOW_LOADING_END, SoundSource.NEUTRAL, 1f, .7f);
                level.sendParticles(ParticleTypes.CRIT, target.getX(), target.getY() + 10, target.getZ(), 40, RAIN_RADIUS * .6, .5, RAIN_RADIUS * .6, .05);
            }
            case ROYAL_CHARGE -> royalCharge(level, hero, owner, time);
            case MIRACLE -> { miracle(level, hero, owner); h.miracleSpent = true; }
        }
        HeroData.get(server).setDirty();
        livingrealms.battle.BattleReports.saved(hero, level.damageSources().mobAttack(target), "Ultimate: " + u.label);
        ServerPlayer p = owner == null ? null : server.getPlayerList().getPlayer(owner);
        if (p != null && p.level() == level && p.distanceToSqr(hero) < 96 * 96)
            p.displayClientMessage(Component.literal("✦ " + fullName(h) + " unleashed " + u.label + "!").withStyle(ChatFormatting.GOLD), true);
        return u;
    }

    /** At least n of the hero and the owner's recruits (and the owner) within r blocks are under the given share of health. */
    static boolean wounded(LivingEntity hero, @Nullable UUID owner, double r, float share, int n) {
        List<LivingEntity> near = new ArrayList<>(allies(hero, owner, r));
        near.add(hero);
        Player p = owner == null ? null : hero.level().getPlayerByUUID(owner);
        if (p != null && p.isAlive() && p.distanceToSqr(hero) <= r * r) near.add(p);
        return near.stream().filter(x -> x.getHealth() < x.getMaxHealth() * share).count() >= n;
    }

    static void earthshaker(ServerLevel level, Mob hero) {
        hero.swing(InteractionHand.MAIN_HAND);
        busy = true;
        try {
            for (LivingEntity x : foes(hero, hero.position(), EARTHSHAKER_RADIUS)) {
                hit(hero, x, EARTHSHAKER_DAMAGE);
                x.knockback(1.5, hero.getX() - x.getX(), hero.getZ() - x.getZ());
                x.setDeltaMovement(x.getDeltaMovement().add(0, .45, 0));
                x.hurtMarked = true;
                x.addEffect(new MobEffectInstance(MobEffects.MOVEMENT_SLOWDOWN, 100, 1));
            }
        } finally { busy = false; }
        BlockState ground = level.getBlockState(hero.blockPosition().below());
        ParticleOptions dust = ground.isAir() ? ParticleTypes.CLOUD : new BlockParticleOption(ParticleTypes.BLOCK, ground);
        for (int ring = 1; ring <= 6; ring++)
            for (int i = 0; i < 8 + ring * 4; i++) {
                double a = 2 * Math.PI * i / (8 + ring * 4);
                level.sendParticles(dust, hero.getX() + Math.cos(a) * ring, hero.getY() + .1, hero.getZ() + Math.sin(a) * ring, 3, .1, .05, .1, .15);
            }
        level.sendParticles(ParticleTypes.EXPLOSION, hero.getX(), hero.getY() + .2, hero.getZ(), 3, 1, .1, 1, 0);
        level.playSound(null, hero.blockPosition(), SoundEvents.GENERIC_EXPLODE, SoundSource.NEUTRAL, 1f, .6f);
        level.playSound(null, hero.blockPosition(), SoundEvents.ANVIL_LAND, SoundSource.NEUTRAL, .8f, .5f);
    }

    static void impale(ServerLevel level, Mob hero, LivingEntity target) {
        Vec3 from = hero.position(), dir = target.position().subtract(from).multiply(1, 0, 1);
        if (dir.lengthSqr() < .01) dir = hero.getLookAngle().multiply(1, 0, 1);
        dir = dir.normalize();
        double reach = Math.min(IMPALE_RANGE, hero.distanceTo(target) + 1.5);
        Vec3 to = from.add(dir.scale(reach));
        List<LivingEntity> line = new ArrayList<>(foes(hero, from.add(dir.scale(reach / 2)), reach / 2 + 2));
        final Vec3 d0 = dir;
        line.removeIf(x -> distanceToLine(x.position(), from, d0, reach) > 1.5 + x.getBbWidth() / 2);
        line.sort(Comparator.comparingDouble(x -> x.distanceToSqr(hero)));
        LivingEntity pinned = null;
        busy = true;
        try {
            for (LivingEntity x : line) {
                hit(hero, x, IMPALE_DAMAGE);
                if (pinned == null && (x.getMaxHealth() >= 40 || x.getBbWidth() > 1.2f || x.isPassenger())) {
                    pinned = x;
                    x.addEffect(new MobEffectInstance(MobEffects.MOVEMENT_SLOWDOWN, PIN_TICKS, 6));
                    x.addEffect(new MobEffectInstance(MobEffects.WEAKNESS, PIN_TICKS, 1));
                    x.setDeltaMovement(Vec3.ZERO);
                    level.sendParticles(ParticleTypes.ENCHANTED_HIT, x.getX(), x.getY(.5), x.getZ(), 20, .4, .4, .4, .2);
                    level.playSound(null, x.blockPosition(), SoundEvents.TRIDENT_HIT, SoundSource.NEUTRAL, 1.2f, .7f);
                } else x.knockback(.8, -d0.x, -d0.z);
            }
        } finally { busy = false; }
        hero.swing(InteractionHand.MAIN_HAND);
        hero.setDeltaMovement(dir.x * 1.6, .15, dir.z * 1.6);
        hero.hurtMarked = true;
        for (int i = 0; i <= 16; i++) {
            Vec3 p = from.lerp(to, i / 16.0);
            level.sendParticles(ParticleTypes.CRIT, p.x, p.y + 1, p.z, 3, .15, .2, .15, .05);
            level.sendParticles(ParticleTypes.CLOUD, p.x, p.y + .1, p.z, 1, .1, 0, .1, .01);
        }
        level.playSound(null, hero.blockPosition(), SoundEvents.TRIDENT_RIPTIDE_3, SoundSource.NEUTRAL, 1f, .9f);
        lastPinned = pinned == null ? null : pinned.getUUID();
    }

    @Nullable static UUID lastPinned;

    static double distanceToLine(Vec3 p, Vec3 from, Vec3 dir, double length) {
        Vec3 rel = p.subtract(from).multiply(1, 0, 1);
        double along = Math.max(0, Math.min(length, rel.dot(dir)));
        return rel.subtract(dir.scale(along)).length();
    }

    static void royalCharge(ServerLevel level, Mob hero, @Nullable UUID owner, long time) {
        List<LivingEntity> riders = new ArrayList<>(allies(hero, owner, 12));
        riders.removeIf(x -> !(x.getVehicle() instanceof LivingEntity m) || DragonRiders.dragon(m));
        riders.add(hero);
        for (LivingEntity r : riders) {
            if (r.getVehicle() instanceof LivingEntity mount) {
                mount.addEffect(new MobEffectInstance(MobEffects.MOVEMENT_SPEED, CHARGE_TICKS, 2));
                level.sendParticles(ParticleTypes.CLOUD, mount.getX(), mount.getY() + .3, mount.getZ(), 12, .5, .2, .5, .05);
            }
            CHARGING.put(r.getUUID(), time + CHARGE_TICKS);
            level.sendParticles(ParticleTypes.FIREWORK, r.getX(), r.getY() + r.getBbHeight() + .5, r.getZ(), 10, .3, .3, .3, .05);
        }
        hero.swing(InteractionHand.MAIN_HAND);
        level.playSound(null, hero.blockPosition(), SoundEvents.RAID_HORN.value(), SoundSource.NEUTRAL, 2f, 1.1f);
        level.playSound(null, hero.blockPosition(), SoundEvents.HORSE_ANGRY, SoundSource.NEUTRAL, 1f, .8f);
    }

    static void miracle(ServerLevel level, Mob hero, @Nullable UUID owner) {
        List<LivingEntity> saved = new ArrayList<>(allies(hero, owner, MIRACLE_RADIUS));
        saved.add(hero);
        Player p = owner == null ? null : level.getPlayerByUUID(owner);
        if (p != null && p.isAlive() && p.distanceToSqr(hero) <= MIRACLE_RADIUS * MIRACLE_RADIUS) saved.add(p);
        for (LivingEntity x : saved) {
            x.setHealth(x.getMaxHealth());
            x.clearFire();
            for (MobEffectInstance fx : new ArrayList<>(x.getActiveEffects()))
                if (fx.getEffect().getCategory() == MobEffectCategory.HARMFUL) x.removeEffect(fx.getEffect());
            level.sendParticles(ParticleTypes.HEART, x.getX(), x.getY(1), x.getZ(), 5, .4, .3, .4, 0);
            level.sendParticles(ParticleTypes.TOTEM_OF_UNDYING, x.getX(), x.getY(.5), x.getZ(), 20, .3, .6, .3, .3);
        }
        for (int i = 0; i < 20; i++) level.sendParticles(ParticleTypes.END_ROD, hero.getX(), hero.getY() + i * .5, hero.getZ(), 2, .15, .1, .15, .01);
        hero.swing(InteractionHand.MAIN_HAND);
        level.playSound(null, hero.blockPosition(), SoundEvents.TOTEM_USE, SoundSource.NEUTRAL, 1f, 1.1f);
        level.playSound(null, hero.blockPosition(), SoundEvents.BEACON_POWER_SELECT, SoundSource.NEUTRAL, 1f, 1.3f);
    }

    /** A shimmering hemisphere of n points. */
    static void dome(ServerLevel level, Vec3 c, int n) {
        double golden = Math.PI * (3 - Math.sqrt(5));
        for (int i = 0; i < n; i++) {
            double y = i / (double) n, r = Math.sqrt(1 - y * y), a = golden * i;
            level.sendParticles(ParticleTypes.END_ROD, c.x + Math.cos(a) * r * BULWARK_RADIUS, c.y + y * BULWARK_RADIUS, c.z + Math.sin(a) * r * BULWARK_RADIUS, 1, 0, 0, 0, 0);
        }
    }

    private static java.lang.reflect.Method pickup;

    /** What picking up this arrow or trident gives (its enchantments kept); empty if that can't be read. */
    static ItemStack pickupItem(AbstractArrow arrow) {
        try {
            if (pickup == null) pickup = net.minecraftforge.fml.util.ObfuscationReflectionHelper.findMethod(AbstractArrow.class, "m_7941_");
            Object st = pickup.invoke(arrow);
            return st instanceof ItemStack i ? i.copy() : ItemStack.EMPTY;
        } catch (ReflectiveOperationException | RuntimeException ex) {
            return arrow instanceof net.minecraft.world.entity.projectile.ThrownTrident ? ItemStack.EMPTY : new ItemStack(net.minecraft.world.item.Items.ARROW);
        }
    }

    /** Runs the ultimates that last: Blade Storm's spins, Bulwark's dome and the Rain of Arrows. Called every tick. */
    public static void run(MinecraftServer server, long time) {
        for (Iterator<Active> it = ACTIVE.iterator(); it.hasNext(); ) {
            Active a = it.next();
            ServerLevel level = server.getLevel(a.dim);
            Entity e = level == null ? null : level.getEntity(a.hero);
            if (time > a.until || !(e instanceof Mob hero) || !hero.isAlive()) { it.remove(); continue; }
            switch (a.u) {
                case BLADE_STORM -> {
                    if (time < a.next) break;
                    a.next = time + 10;
                    busy = true;
                    try { for (LivingEntity x : foes(hero, hero.position(), BLADE_STORM_RADIUS)) hit(hero, x, BLADE_STORM_DAMAGE); }
                    finally { busy = false; }
                    hero.setYRot(hero.getYRot() + 120); hero.yBodyRot = hero.getYRot(); hero.yHeadRot = hero.getYRot();
                    hero.swing(InteractionHand.MAIN_HAND);
                    for (int i = 0; i < 8; i++) {
                        double ang = 2 * Math.PI * i / 8 + time * .4;
                        level.sendParticles(ParticleTypes.SWEEP_ATTACK, hero.getX() + Math.cos(ang) * 2, hero.getY(.5), hero.getZ() + Math.sin(ang) * 2, 1, 0, 0, 0, 0);
                    }
                    level.playSound(null, hero.blockPosition(), SoundEvents.PLAYER_ATTACK_SWEEP, SoundSource.NEUTRAL, 1f, 1.2f + level.random.nextFloat() * .3f);
                }
                case BULWARK -> {
                    for (Projectile p : level.getEntitiesOfClass(Projectile.class, new AABB(a.center, a.center).inflate(BULWARK_RADIUS + 1),
                            p -> p.isAlive() && p.position().distanceToSqr(a.center) <= BULWARK_RADIUS * BULWARK_RADIUS
                                    && !p.getPersistentData().getBoolean(BULWARKED)
                                    && p.getOwner() != null && !friendly(a.owner, p.getOwner()) && p.getOwner() != hero)) {
                        level.sendParticles(ParticleTypes.ENCHANTED_HIT, p.getX(), p.getY(), p.getZ(), 8, .1, .1, .1, .1);
                        level.playSound(null, p.blockPosition(), SoundEvents.SHIELD_BLOCK, SoundSource.NEUTRAL, .8f, 1.2f);
                        // a trident or an arrow someone can pick up is knocked down, not destroyed
                        // (as an item on the ground, so it can't fall on anyone)
                        if (p instanceof AbstractArrow arrow && (arrow instanceof net.minecraft.world.entity.projectile.ThrownTrident || arrow.pickup == AbstractArrow.Pickup.ALLOWED)) {
                            ItemStack drop = pickupItem(arrow);
                            if (!drop.isEmpty()) {
                                net.minecraft.world.entity.item.ItemEntity item = new net.minecraft.world.entity.item.ItemEntity(level, p.getX(), p.getY(), p.getZ(), drop);
                                item.setDeltaMovement(0, -.1, 0);
                                item.setDefaultPickUpDelay();
                                level.addFreshEntity(item);
                            }
                        }
                        p.discard();
                        blocked++;
                    }
                    if (time >= a.next) { a.next = time + 10; dome(level, a.center, 36); }
                }
                case RAIN_OF_ARROWS -> {
                    if (time < a.next) break;
                    a.next = time + 5;
                    for (int i = 0; i < 3; i++) {
                        double ang = level.random.nextDouble() * 2 * Math.PI, r = Math.sqrt(level.random.nextDouble()) * RAIN_RADIUS;
                        Arrow arrow = new Arrow(level, hero);
                        arrow.setPos(a.center.x + Math.cos(ang) * r, a.center.y + 12, a.center.z + Math.sin(ang) * r);
                        arrow.setBaseDamage(RAIN_DAMAGE);
                        arrow.pickup = AbstractArrow.Pickup.DISALLOWED;
                        arrow.getPersistentData().putBoolean(ULTIMATE_SHOT, true);
                        arrow.shoot(0, -1, 0, 1.8f, 4f);
                        level.addFreshEntity(arrow);
                        shot++;
                    }
                    level.playSound(null, BlockPos.containing(a.center).above(6), SoundEvents.ARROW_SHOOT, SoundSource.NEUTRAL, 1f, .8f + level.random.nextFloat() * .4f);
                }
                default -> it.remove();
            }
        }
        if (!CHARGING.isEmpty()) CHARGING.values().removeIf(t -> t < time);
    }

    /** For the self-test: projectiles Bulwark stopped and arrows the Rain loosed. */
    static int blocked, shot;

    static boolean running(Ultimate u, UUID hero) { for (Active a : ACTIVE) if (a.u == u && a.hero.equals(hero)) return true; return false; }

    static void fx(ServerLevel level, Vec3 at, ParticleOptions particle, int count, SoundEvent sound, float pitch) {
        level.sendParticles(particle, at.x, at.y, at.z, count, .4, .4, .4, .05);
        level.playSound(null, at.x, at.y, at.z, sound, SoundSource.NEUTRAL, .8f, pitch);
    }

    // ------------------------------------------------------------------ ticks and loading

    @SubscribeEvent
    public static void tick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        MinecraftServer server = event.getServer();
        long time = server.overworld().getGameTime();
        try {
            if (!ACTIVE.isEmpty() || !CHARGING.isEmpty()) run(server, time);
            if (server.getTickCount() % 6000 == 0) HeroData.get(server).prune(server, time);
            if (LOADED.isEmpty() || server.getTickCount() % 10 != 0) return;
            HeroData data = HeroData.get(server);
            boolean second = server.getTickCount() % 20 == 0;
            for (Entity e : new ArrayList<>(LOADED.values())) {
                HeroData.Hero h = data.hero(e.getUUID());
                if (h == null || !e.isAlive()) { if (h == null) LOADED.remove(e.getUUID(), e); continue; }
                if (second) pulse(server, e, h, time);
                if (e instanceof Mob mob) act(server, mob, h, time);
            }
        } catch (RuntimeException ex) { LivingRealms.LOG.error("Heroes failed", ex); }
    }

    @SubscribeEvent
    public static void joined(EntityJoinLevelEvent e) {
        if (e.getLevel().isClientSide() || !RecruitsBridge.isRecruit(e.getEntity()) || e.getLevel().getServer() == null) return;
        HeroData data = HeroData.get(e.getLevel().getServer());
        HeroData.Hero h = data.hero(e.getEntity().getUUID());
        if (h == null) return;
        LOADED.put(e.getEntity().getUUID(), e.getEntity());
        if (h.removed) { h.removed = false; data.setDirty(); }
    }

    @SubscribeEvent
    public static void left(EntityLeaveLevelEvent e) {
        if (e.getLevel().isClientSide()) return;
        Entity x = e.getEntity();
        if (!LOADED.remove(x.getUUID(), x)) return;
        // discarded for good (not unloaded): a promotion through Villager Recruits' own screen; it holds no place under the cap
        if (x.getRemovalReason() == Entity.RemovalReason.DISCARDED && e.getLevel().getServer() != null) {
            HeroData data = HeroData.get(e.getLevel().getServer());
            HeroData.Hero h = data.hero(x.getUUID());
            if (h != null && !h.removed) { h.removed = true; data.setDirty(); }
        }
    }

    @SubscribeEvent
    public static void stopped(ServerStoppedEvent event) { reset(); }

    static void reset() { LOADED.clear(); ACTIVE.clear(); CHARGING.clear(); busy = false; }

    /** For the self-test: a hero record made by hand is loaded. */
    static void track(Entity e) { LOADED.put(e.getUUID(), e); }

    /** A recruit was replaced by a new entity (a promotion through Recruit Command): its hero record follows. */
    public static void moved(MinecraftServer server, UUID from, UUID to) {
        HeroData data = HeroData.get(server);
        data.move(from, to);
        Entity old = LOADED.remove(from);
        Entity n = SoldierProgression.findEntity(server, to);
        if (data.hero(to) != null && n != null) LOADED.put(to, n);
        for (Active a : new ArrayList<>(ACTIVE)) if (a.hero.equals(from)) ACTIVE.remove(a);
        Long c = CHARGING.remove(from);
        if (c != null) CHARGING.put(to, c);
    }

    // ------------------------------------------------------------------ death

    private static UUID dyingId;
    @Nullable private static SoldierProgression.Record dyingRecord;

    /** Before ServerEvents forgets the soldier. */
    @SubscribeEvent(priority = EventPriority.HIGH)
    public static void dying(LivingDeathEvent event) {
        if (!(event.getEntity().level() instanceof ServerLevel level) || !LOADED.containsKey(event.getEntity().getUUID())) return;
        dyingId = event.getEntity().getUUID();
        dyingRecord = SoldierProgression.record(level.getServer(), event.getEntity());
    }

    /** The death is certain and the drops not yet made: the hero falls, and its weapon becomes an heirloom. */
    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void died(LivingDeathEvent event) {
        LivingEntity dead = event.getEntity();
        if (!(dead.level() instanceof ServerLevel level)) return;
        SoldierProgression.Record rec = dead.getUUID().equals(dyingId) ? dyingRecord : null;
        dyingId = null; dyingRecord = null;
        HeroData data = HeroData.get(level.getServer());
        if (event.isCanceled()) return;
        if (data.hero(dead.getUUID()) == null) {
            if (RecruitsBridge.isRecruit(dead)) data.forget(dead.getUUID());
            return;
        }
        fell(level.getServer(), dead, event.getSource().getEntity(), rec == null ? null : rec.settlement());
    }

    /** A hero fell: told across the server, in its town's chronicle and memorial; its weapon is an heirloom. */
    public static void fell(MinecraftServer server, LivingEntity dead, @Nullable Entity killer, @Nullable Settlement town) {
        HeroData data = HeroData.get(server);
        HeroData.Hero h = data.hero(dead.getUUID());
        if (h == null) return;
        long time = server.overworld().getGameTime();
        String name = fullName(h);
        String foe = killer == null || killer == dead ? "" : livingrealms.Text.who(killer);
        long days = Math.max(0, (time - h.ascended) / 24000);
        lastFall = "✝ The hero " + name + (town == null ? "" : " of " + town.name) + " has fallen" + (foe.isEmpty() ? "." : ", slain by " + foe + ".");
        server.getPlayerList().broadcastSystemMessage(Component.literal(lastFall).withStyle(ChatFormatting.GOLD), false);
        if (town != null) {
            legend(town, "The hero " + name + " fell" + (foe.isEmpty() ? "" : " to " + foe) + ", " + (days == 0 ? "on the day of their ascension" : "after " + days + (days == 1 ? " day" : " days") + " as a Hero")
                    + (h.valor() > 0 ? " and Valor " + h.valor() : "") + ". All the realm mourned.");
            Chronicle.markFall(dead.getUUID());
            CompoundTag m = new CompoundTag();
            m.putString("n", name);
            m.putInt("a", h.valor());
            m.putString("c", foe.isEmpty() ? "in battle" : "in battle, slain by " + foe);
            m.putString("j", "Hero" + (h.path == null ? "" : " · " + h.path.label));
            m.putLong("d", Chronicle.today());
            m.putBoolean("hero", true);
            town.people.memorial.addFirst(m);
            while (town.people.memorial.size() > livingrealms.citizen.People.MEMORIAL) town.people.memorial.removeLast();
            town.log(name + " fell; the realm mourns its Hero");
            town.dirty = true;
        }
        ItemStack weapon = dead.getItemBySlot(EquipmentSlot.MAINHAND);
        if (!weapon.isEmpty()) heirloom(weapon, name);
        if (dead.level() instanceof ServerLevel level) {
            level.sendParticles(ParticleTypes.SOUL, dead.getX(), dead.getY(.5), dead.getZ(), 30, .4, .6, .4, .05);
            level.playSound(null, dead.blockPosition(), SoundEvents.WITHER_DEATH, SoundSource.NEUTRAL, .6f, 1.4f);
        }
        UUID id = dead.getUUID();
        data.forget(id);
        LOADED.remove(id);
        ACTIVE.removeIf(a -> a.hero.equals(id));
        CHARGING.remove(id);
    }

    // ------------------------------------------------------------------ heirlooms

    /** Names the fallen hero's weapon and lets it teach one recruit. */
    static void heirloom(ItemStack stack, String hero) {
        if (stack.getTagElement(HEIRLOOM) != null) return;
        CompoundTag t = stack.getOrCreateTagElement(HEIRLOOM);
        t.putString("hero", hero);
        t.putInt("xp", HEIRLOOM_XP);
        stack.setHoverName(Component.literal(hero + "'s " + stack.getItem().getDescription().getString()).withStyle(ChatFormatting.GOLD));
        lore(stack, "Heirloom of a fallen Hero", "Arm a town recruit with it: +" + HEIRLOOM_XP + " skill XP, once");
    }

    static void lore(ItemStack stack, String... lines) {
        ListTag lore = new ListTag();
        for (String s : lines) lore.add(StringTag.valueOf(Component.Serializer.toJson(Component.literal(s).withStyle(ChatFormatting.GRAY, ChatFormatting.ITALIC))));
        stack.getOrCreateTagElement("display").put("Lore", lore);
    }

    public static boolean heirloom(ItemStack stack) { return stack.getTagElement(HEIRLOOM) != null; }

    /** A town recruit takes up an unclaimed heirloom: it gains the skill XP once. */
    @SubscribeEvent
    public static void equipped(LivingEquipmentChangeEvent event) {
        if (event.getSlot() != EquipmentSlot.MAINHAND || !(event.getEntity().level() instanceof ServerLevel level) || !RecruitsBridge.isRecruit(event.getEntity())) return;
        CompoundTag t = event.getTo().getTagElement(HEIRLOOM);
        if (t == null || t.getBoolean("claimed")) return;
        claim(level.getServer(), event.getEntity(), event.getTo());
    }

    /** Returns the skill XP granted (0 if this recruit can't take it: not a town soldier, or already claimed). */
    static int claim(MinecraftServer server, LivingEntity e, ItemStack stack) {
        CompoundTag t = stack.getTagElement(HEIRLOOM);
        if (t == null || t.getBoolean("claimed")) return 0;
        var rec = SoldierProgression.record(server, e);
        if (rec == null) return 0;
        Settlement.Soldier so = rec.soldier();
        int xp = t.getInt("xp");
        boolean hero = hero(server, so.entity) != null;
        // a recruit at the top rank that isn't a Hero has nowhere to put the XP: the heirloom waits for one who can use it
        if (!hero && so.skillXp >= SoldierRanks.MAX_XP) return 0;
        t.putBoolean("claimed", true);
        t.putString("claimedBy", so.name);
        lore(stack, "Heirloom of a fallen Hero", "Its strength passed to " + so.name);
        int got = SoldierRanks.grant(rec.settlement(), so, e, xp, false, server.overworld().getGameTime());
        int valor = hero ? xp - got : 0;
        List<String> gains = new ArrayList<>();
        if (got > 0) gains.add("+" + got + " skill XP");
        if (valor > 0) gains.add("+" + valor + " Valor XP");
        lastClaim = so.name + " took up " + stack.getHoverName().getString() + (gains.isEmpty() ? "" : " (" + String.join(", ", gains) + ")");
        rec.settlement().log(lastClaim);
        ServerPlayer owner = rec.settlement().owner == null ? null : server.getPlayerList().getPlayer(rec.settlement().owner);
        if (owner != null) owner.sendSystemMessage(Component.literal("✦ " + lastClaim).withStyle(ChatFormatting.GOLD));
        rec.settlement().dirty = true;
        return Math.max(got, 1);
    }

    // ------------------------------------------------------------------ UI contract

    /**
     * The "hero" part of a soldier's view, for the Path page. A Hero: {hero:true, name, valor, valorXp, valorFrom,
     * valorTo, ultimate, ultimateDesc, cooldown, spent, cap, count}. Otherwise: {hero:false, needs:[{label,detail,met}],
     * cost, emeralds, can, why, cap, count}.
     */
    public static void describe(Settlement s, Settlement.Soldier so, CompoundTag out) {
        MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
        if (server == null) return;
        long now = server.overworld().getGameTime();
        CompoundTag t = new CompoundTag();
        HeroData.Hero h = hero(server, so.entity);
        if (s.owner != null) { t.putInt("cap", cap(server, s.owner)); t.putInt("count", count(server, s.owner)); t.putInt("cities", cities(server, s.owner)); }
        Ultimate u = Ultimate.of(h != null && h.path != null ? h.path : so.path);
        if (u != null) { t.putString("ultimate", u.label); t.putString("ultimateDesc", u.description); }
        if (h != null) {
            t.putBoolean("hero", true);
            t.putString("name", fullName(h));
            int v = h.valor();
            t.putInt("valor", v); t.putInt("valorXp", h.valorXp);
            t.putInt("valorFrom", valorNeeded(v)); t.putInt("valorTo", v >= VALOR_MAX ? 0 : valorNeeded(v + 1));
            t.putInt("cooldown", (int) Math.max(0, ready(h, Ultimate.BLADE_STORM, now) ? 0 : (h.ultimateReady - now + 19) / 20));
            t.putBoolean("spent", h.miracleSpent);
            t.putLong("day", Chronicle.dayOf(h.ascended));
        } else {
            t.putBoolean("hero", false);
            ListTag needs = new ListTag();
            for (Need n : needs(server, s, so, now)) {
                CompoundTag r = new CompoundTag();
                r.putString("label", n.label()); r.putString("detail", n.detail()); r.putBoolean("met", n.met());
                needs.add(r);
            }
            t.put("needs", needs);
            t.putString("cost", cost());
            t.putInt("emeralds", s.stock.count(Items.EMERALD));
            String why = blocker(server, s, so, now);
            t.putBoolean("can", why == null);
            t.putString("why", why == null ? "" : why);
        }
        out.put("hero", t);
    }

    private Heroes() {}
}
