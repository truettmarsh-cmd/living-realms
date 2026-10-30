package livingrealms.dragon;

import livingrealms.building.RoostBlueprint;
import livingrealms.chronicle.Chronicle;
import livingrealms.military.DragonRiders;
import livingrealms.settlement.Plot;
import livingrealms.settlement.Settlement;
import livingrealms.settlement.SettlementManager;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.TamableAnimal;
import net.minecraft.world.item.Item;
import net.minecraft.world.phys.AABB;

import javax.annotation.Nullable;
import java.util.*;

/**
 * Dragon Roosts (docs/dragons.md). A town with a standing roost keeps one dragon: the keepers hatch an Ice and Fire egg
 * from the stockpile, or a tamed dragon of the ruler's, ordered to sit in the roost, moves in. The dragon perches on the
 * nest, flies home when it strays, grows faster on the keepers' food, and is the mount a Dragon Rider trained in the
 * town takes. Without Ice and Fire a roost is only a building.
 */
public final class Roosts {
    public static final String ID = "livingrealms:" + RoostBlueprint.KIND, RESEARCH = "dragonry";
    public static final int TICK = 40, ADOPT_RANGE = 16, HOME_RANGE = 48, FAR = 64, RIDER_STAGE = 3;

    @Nullable
    public static Plot roost(Settlement s) {
        for (Plot p : s.plots) if (p.typeId.equals(ID) && p.standing()) return p;
        return null;
    }

    public static boolean has(Settlement s) { return roost(s) != null; }

    /** The nest block, in the middle of the court. */
    public static BlockPos nest(Plot p) {
        var b = p.box();
        return new BlockPos((b.minX() + b.maxX()) / 2, p.origin.getY() + 2, (b.minZ() + b.maxZ()) / 2);
    }

    /** Where the dragon sits: on the nest. */
    public static BlockPos perch(Plot p) { return nest(p).above(); }

    /** Who a roost dragon answers to: the ruler, or for an NPC kingdom a keeper's name that stands for the kingdom. */
    public static UUID owner(Settlement s) {
        return s.owner != null ? s.owner : UUID.nameUUIDFromBytes(("livingrealms:dragon/" + s.kingdomId()).getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    @Nullable
    public static DragonData.Roost record(MinecraftServer server, Settlement s) { return DragonData.get(server).roosts.get(s.id); }

    /** The town's dragon if it is loaded and alive. */
    @Nullable
    public static TamableAnimal dragon(MinecraftServer server, Settlement s) {
        DragonData.Roost r = record(server, s);
        ServerLevel level = server.getLevel(s.dim);
        if (r == null || r.dragon == null || level == null) return null;
        return level.getEntity(r.dragon) instanceof TamableAnimal d && !DragonApi.dead(d) ? d : null;
    }

    // ------------------------------------------------------------------ the tick

    public static void tick(MinecraftServer server, SettlementManager m) {
        DragonData data = DragonData.get(server);
        Set<UUID> towns = new HashSet<>();
        for (Settlement s : m.all()) {
            if (s.mirror()) continue;
            towns.add(s.id);
            Plot p = roost(s);
            DragonData.Roost r = data.roosts.get(s.id);
            if (p == null) {
                if (r != null && !r.hasDragon() && !r.incubating()) data.roosts.remove(s.id);
                else if (r != null) r.state = "The roost is gone";
                continue;
            }
            if (r == null) data.roosts.put(s.id, r = new DragonData.Roost());
            tick(server, data, s, p, r);
        }
        data.roosts.keySet().removeIf(id -> !towns.contains(id));
    }

    static void tick(MinecraftServer server, DragonData data, Settlement s, Plot p, DragonData.Roost r) {
        if (!r.announced && p.state == Plot.State.BUILT) {
            r.announced = true;
            Chronicle.record(s, Chronicle.Kind.DRAGON, "The Dragon Roost of " + s.name + " was finished, its nest lined with hay and gold.");
        }
        if (!DragonApi.installed()) { r.state = "Needs Ice and Fire"; return; }
        ServerLevel level = server.getLevel(s.dim);
        if (level == null) return;
        long now = level.getGameTime();
        BlockPos perch = perch(p);
        UUID expect = owner(s);
        if (r.hasDragon()) { keep(level, data, s, p, r, perch, expect, now); return; }
        if (r.incubating()) {
            long left = Math.max(0, r.hatchAt - now);
            r.state = "Egg in the embers: hatches in " + (left >= 24000 ? left / 24000 + " d" : left / 1200 + " min");
            if (left == 0 && level.hasChunkAt(perch)) hatch(level, s, p, r, perch, expect);
            return;
        }
        // an egg from the stockpile
        for (Item it : List.copyOf(s.stock.view().keySet())) {
            int[] egg = DragonApi.egg(it);
            if (egg == null || !s.stock.take(it, 1)) continue;
            setEgg(r, egg[0], egg[1], now + DragonConfig.i(DragonConfig.HATCH_TICKS));
            s.log("The keepers laid a " + DragonApi.EGGS.get(egg[0] * 4 + egg[1]) + " dragon egg in the embers of the roost");
            s.dirty = true;
            return;
        }
        // a dragon of the ruler's, ordered to sit in the roost
        if (level.hasChunkAt(perch)) for (TamableAnimal d : level.getEntitiesOfClass(TamableAnimal.class, new AABB(perch).inflate(ADOPT_RANGE),
                d -> DragonApi.dragon(d) && d.isTame() && expect.equals(d.getOwnerUUID()) && !DragonApi.dead(d) && d.getPassengers().isEmpty()
                        && DragonApi.command(d) == DragonApi.SIT && !housed(data, d.getUUID()))) {
            r.dragon = d.getUUID();
            r.hatched = false;
            r.fedDay = now / 24000;
            see(r, d);
            DragonApi.bondHome(d, level, nest(p));
            Chronicle.record(s, Chronicle.Kind.DRAGON, r.name + ", a " + r.element + " dragon of " + (s.ownerName.isEmpty() ? "the ruler" : s.ownerName) + "'s, came to live in the roost of " + s.name + ".");
            tell(level, s, r.name + " has moved into the roost of " + s.name, ChatFormatting.GOLD);
            return;
        }
        if (s.isAi() && DragonConfig.on(DragonConfig.NPC_DRAGONS)) {
            if (r.nextEgg == DragonData.UNSET) r.nextEgg = now + DragonConfig.i(DragonConfig.NPC_EGG_DAYS) * 24000L;
            else if (now >= r.nextEgg) {
                int seed = Math.floorMod(s.id.hashCode() + r.hatchings, 12);
                setEgg(r, seed / 4, seed % 4, now + DragonConfig.i(DragonConfig.HATCH_TICKS));
                r.nextEgg = DragonData.UNSET;
                s.log("Dragon hunters sold the keepers an egg");
                return;
            }
        }
        r.state = "Empty: deliver a dragon egg, or sit your tamed dragon in the roost";
    }

    static void setEgg(DragonData.Roost r, int element, int variant, long hatchAt) {
        r.eggElement = element; r.eggVariant = variant; r.hatchAt = hatchAt;
    }

    static boolean housed(DragonData data, UUID dragon) {
        for (DragonData.Roost o : data.roosts.values()) if (dragon.equals(o.dragon)) return true;
        return false;
    }

    static void hatch(ServerLevel level, Settlement s, Plot p, DragonData.Roost r, BlockPos perch, UUID owner) {
        TamableAnimal d = DragonApi.hatch(level, perch, r.eggElement, r.eggVariant, owner, level.getGameTime() ^ s.id.getLeastSignificantBits() ^ r.hatchings);
        if (d == null) { r.state = "The egg would not hatch"; return; }
        r.eggElement = -1;
        r.dragon = d.getUUID();
        r.hatched = true;
        r.hatchings++;
        r.fedDay = level.getGameTime() / 24000;
        see(r, d);
        DragonApi.bondHome(d, level, nest(p));
        Chronicle.record(s, Chronicle.Kind.DRAGON, "A " + r.element + " dragon hatched in the roost of " + s.name + "; the keepers named it " + r.name + ".");
        tell(level, s, "A " + r.element + " dragon has hatched in the roost of " + s.name + ": " + r.name, ChatFormatting.GOLD);
        s.dirty = true;
    }

    static void see(DragonData.Roost r, Entity d) {
        r.name = DragonApi.name(d);
        r.element = DragonApi.element(d);
        r.stage = DragonApi.stage(d);
        r.ageDays = DragonApi.ageDays(d);
        if (d instanceof TamableAnimal t) { r.health = t.getHealth(); r.maxHealth = t.getMaxHealth(); }
    }

    /** Looks after a roost's dragon: who owns it, where it is, feeding, flying it home. */
    static void keep(ServerLevel level, DragonData data, Settlement s, Plot p, DragonData.Roost r, BlockPos perch, UUID expect, long now) {
        Entity e = level.getEntity(r.dragon);
        if (e == null) {
            if (level.hasChunkAt(perch) && !r.state.startsWith("Away")) r.state = "Away from the roost";
            return;
        }
        if (DragonApi.dead(e) || !(e instanceof TamableAnimal d)) {
            Chronicle.record(s, Chronicle.Kind.DRAGON, r.name + ", the dragon of " + s.name + "'s roost, died.");
            tell(level, s, r.name + ", the dragon of " + s.name + ", has died", ChatFormatting.RED);
            r.clearDragon();
            r.state = "Empty";
            return;
        }
        if (!expect.equals(d.getOwnerUUID())) {
            if (r.hatched) {
                // the roost's own dragon passes with the town
                d.setOwnerUUID(expect);
                s.log(r.name + " now answers to the new ruler of " + s.name);
            } else {
                s.log(r.name + " left the roost with its owner");
                r.clearDragon();
                return;
            }
        }
        see(r, d);
        if (Flight.flying(d)) { r.state = Flight.ACTIVE.get(d.getUUID()).kind == Flight.Kind.SORTIE ? "On a sortie" : "In the air"; return; }
        if (!d.getPassengers().isEmpty()) { r.state = "Carrying " + d.getPassengers().get(0).getName().getString(); return; }
        if (DragonApi.command(d) == DragonApi.ESCORT) { r.state = "With its owner"; return; }
        if (d.getTarget() != null && d.getTarget().isAlive() && d.distanceToSqr(perch.getX(), perch.getY(), perch.getZ()) < 96 * 96) { r.state = "Fighting"; return; }
        double dist = d.distanceToSqr(perch.getX() + .5, perch.getY(), perch.getZ() + .5);
        if (dist > FAR * FAR && level.hasChunkAt(perch)) {
            d.teleportTo(perch.getX() + .5, perch.getY(), perch.getZ() + .5);
            dist = 0;
        }
        if (dist > 36) {
            DragonApi.command(d, DragonApi.WANDER);
            d.getNavigation().moveTo(perch.getX() + .5, perch.getY(), perch.getZ() + .5, 1);
            r.state = "Flying home";
        } else {
            DragonApi.command(d, DragonApi.SIT);
            r.state = "Perched";
        }
        long day = now / 24000;
        if (r.fedDay < day && dist < HOME_RANGE * HOME_RANGE) {
            if (r.stage < 4) DragonApi.setAgeDays(d, Math.min(75, r.ageDays + DragonConfig.i(DragonConfig.GROWTH_DAYS) * (int) Math.min(3, day - r.fedDay)));
            r.fedDay = day;
        }
        DragonApi.bondHome(d, level, nest(p));
    }

    static void tell(ServerLevel level, Settlement s, String msg, ChatFormatting colour) {
        s.log(msg);
        if (s.owner == null) return;
        ServerPlayer p = level.getServer().getPlayerList().getPlayer(s.owner);
        if (p != null) p.sendSystemMessage(Component.literal(msg).withStyle(colour));
    }

    // ------------------------------------------------------------------ riders

    /** A roost dragon able to carry a Dragon Rider trained in the town at this spot, or null. */
    @Nullable
    public static Entity forRider(ServerLevel level, BlockPos at, UUID owner) {
        if (!DragonApi.installed() || owner == null) return null;
        SettlementManager m = SettlementManager.get(level.getServer());
        Settlement s = m.at(level.dimension(), at);
        if (s == null || !owner.equals(owner(s)) && !owner.equals(s.kingdomId())) return null;
        TamableAnimal d = dragon(level.getServer(), s);
        if (d == null || Flight.flying(d) || !DragonRiders.eligible(d, owner)) return null;
        return d;
    }

    // ------------------------------------------------------------------ for the City Manager

    public static CompoundTag view(MinecraftServer server, Settlement s) {
        CompoundTag t = new CompoundTag();
        Plot p = roost(s);
        DragonData.Roost r = record(server, s);
        t.putBoolean("roost", p != null);
        t.putBoolean("installed", DragonApi.installed());
        t.putBoolean("scorpions", Scorpions.armed(s));
        if (r != null) {
            t.putString("state", r.state);
            if (r.hasDragon()) {
                t.putString("name", r.name);
                t.putString("element", r.element);
                t.putInt("stage", r.stage);
                t.putFloat("hp", r.health);
                t.putFloat("max", r.maxHealth);
                t.putBoolean("hatched", r.hatched);
                t.putBoolean("rider", r.stage >= RIDER_STAGE);
                TamableAnimal d = dragon(server, s);
                if (d != null && DragonApi.bond()) t.putInt("bond", DragonApi.bondLevel(d));
            } else if (r.incubating()) t.putString("egg", DragonApi.EGGS.get(r.eggElement * 4 + r.eggVariant));
        }
        String attack = DragonAttacks.status(s);
        if (attack != null) t.putString("attack", attack);
        return t;
    }

    private Roosts() {}
}
