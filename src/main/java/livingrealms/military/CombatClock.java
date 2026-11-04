package livingrealms.military;

import livingrealms.LivingRealms;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.living.LivingDeathEvent;
import net.minecraftforge.event.entity.living.LivingHurtEvent;
import net.minecraftforge.event.server.ServerStoppedEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import javax.annotation.Nullable;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * When each owned recruit last fought: the one definition of "in combat" that resupply, bonds and battle reports share.
 * A recruit fights when it hits, or is hit by, something that isn't its owner or one of its owner's recruits. Kept in
 * memory only (game time per recruit UUID), forgotten on death and when the server stops.
 */
@Mod.EventBusSubscriber(modid = LivingRealms.MODID)
public final class CombatClock {
    /** Combat marks older than this are dropped by the sweep; nothing asks about older fights. */
    static final long FORGET = 6000;
    private static final Map<UUID, Long> LAST = new HashMap<>();

    /** True when a recruit hitting (or hit by) this entity is in a fight: not its owner and not one of the owner's recruits. */
    public static boolean foe(Entity recruit, @Nullable Entity other) {
        UUID owner = RecruitsBridge.owner(recruit);
        if (other == null || other == recruit || owner == null || owner.equals(other.getUUID())) return false;
        return !(RecruitsBridge.isRecruit(other) && owner.equals(RecruitsBridge.owner(other)));
    }

    static boolean tracked(Entity e) { return RecruitsBridge.isRecruit(e) && RecruitsBridge.owner(e) != null; }

    /** Marks a recruit as fighting at this game time. */
    public static void mark(UUID id, long time) { LAST.put(id, time); }

    /** Game time of the recruit's last fight, or Long.MIN_VALUE if it hasn't fought lately. */
    public static long last(UUID id) { return LAST.getOrDefault(id, Long.MIN_VALUE); }

    /** Fought within the last {@code ticks} ticks of {@code now}. */
    public static boolean within(UUID id, long now, long ticks) {
        Long t = LAST.get(id);
        return t != null && now >= t && now - t <= ticks;
    }

    /** Every recruit's last fight. Do not modify. */
    public static Map<UUID, Long> all() { return Collections.unmodifiableMap(LAST); }

    /** A recruit replaced by a new entity keeps its last fight. */
    public static void move(UUID from, UUID to) {
        Long t = LAST.remove(from);
        if (t != null) mark(to, t);
    }

    public static void forget(UUID id) { LAST.remove(id); }

    public static void clear() { LAST.clear(); }

    @SubscribeEvent(priority = EventPriority.HIGH)
    public static void hurt(LivingHurtEvent event) {
        if (event.isCanceled() || event.getAmount() <= 0 || !(event.getEntity().level() instanceof ServerLevel level)) return;
        LivingEntity victim = event.getEntity();
        Entity attacker = event.getSource().getEntity();
        long time = level.getServer().overworld().getGameTime();
        if (attacker != null && attacker != victim && tracked(attacker) && foe(attacker, victim)) mark(attacker.getUUID(), time);
        if (tracked(victim) && foe(victim, attacker)) mark(victim.getUUID(), time);
    }

    @SubscribeEvent
    public static void died(LivingDeathEvent event) {
        if (!event.getEntity().level().isClientSide()) LAST.remove(event.getEntity().getUUID());
    }

    @SubscribeEvent
    public static void tick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END || event.getServer().getTickCount() % 6000 != 0 || LAST.isEmpty()) return;
        long now = event.getServer().overworld().getGameTime();
        LAST.values().removeIf(t -> now - t > FORGET || t > now);
    }

    @SubscribeEvent
    public static void stopped(ServerStoppedEvent event) { LAST.clear(); }

    private CombatClock() {}
}
