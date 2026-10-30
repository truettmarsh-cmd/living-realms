package livingrealms.dragon;

import livingrealms.LivingRealms;
import livingrealms.military.DragonRiders;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.MobSpawnType;
import net.minecraft.world.entity.TamableAnimal;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;

import javax.annotation.Nullable;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

/**
 * Ice and Fire and Dragon Bond, by reflection and registry names only: nothing here loads a class of either mod, so
 * every call is safe (and does nothing) when they are missing. Failures are logged once.
 */
public final class DragonApi {
    public static final String IAF = "iceandfire", BOND = "dragonbond";
    /** Egg colours in Ice and Fire's order: four variants per element. */
    static final List<String> EGGS = List.of("red", "green", "bronze", "gray", "blue", "white", "sapphire", "silver",
            "electric", "amythest", "copper", "black");
    static final List<String> ELEMENTS = List.of("fire", "ice", "lightning");
    /** Ice and Fire commands: wander, sit, escort. */
    public static final int WANDER = 0, SIT = 1, ESCORT = 2;
    private static final Set<String> WARNED = new HashSet<>();

    public static boolean installed() { return DragonRiders.installed(); }

    public static boolean bond() { return livingrealms.compat.Compat.loaded(BOND); }

    public static boolean dragon(@Nullable Entity e) { return e != null && installed() && DragonRiders.dragon(e); }

    static void warn(String what, Throwable t) {
        if (WARNED.add(what)) LivingRealms.LOG.warn("Living Realms dragons: {} failed: {}", what, t.toString());
    }

    @Nullable
    static Object call(Entity e, String method) {
        try { return e.getClass().getMethod(method).invoke(e); }
        catch (Throwable t) { warn(method, t); return null; }
    }

    static void call(Entity e, String method, Class<?> type, Object arg) {
        try { e.getClass().getMethod(method, type).invoke(e, arg); }
        catch (Throwable t) { warn(method, t); }
    }

    public static int stage(Entity e) { return call(e, "getDragonStage") instanceof Integer i ? i : 0; }

    public static int ageDays(Entity e) { return call(e, "getAgeInDays") instanceof Integer i ? i : 0; }

    /** Sets the age and lets Ice and Fire size its health, armour and damage for it at once. */
    public static void setAgeDays(Entity e, int days) {
        call(e, "setAgeInDays", int.class, days);
        for (Class<?> c = e.getClass(); c != null && c != Object.class; c = c.getSuperclass()) {
            try {
                var m = c.getDeclaredMethod("updateAttributes");
                m.setAccessible(true);
                m.invoke(e);
                return;
            } catch (NoSuchMethodException next) {
            } catch (Throwable t) { warn("updateAttributes", t); return; }
        }
    }

    public static int command(Entity e) { return call(e, "getCommand") instanceof Integer i ? i : -1; }

    public static void command(Entity e, int c) { if (command(e) != c) call(e, "setCommand", int.class, c); }

    public static void flying(Entity e, boolean on) { call(e, "setFlying", boolean.class, on); call(e, "setHovering", boolean.class, false); }

    public static void breathing(Entity e, boolean on) { call(e, "setBreathingFire", boolean.class, on); }

    /** Dead, or one of Ice and Fire's dragon corpses ("model dead"). */
    public static boolean dead(@Nullable Entity e) {
        return e == null || !e.isAlive() || e.isRemoved() || call(e, "isModelDead") instanceof Boolean b && b;
    }

    public static String element(Entity e) {
        String path = BuiltInRegistries.ENTITY_TYPE.getKey(e.getType()).getPath();
        return path.startsWith("ice") ? "ice" : path.startsWith("lightning") ? "lightning" : "fire";
    }

    public static boolean wild(Entity e) { return dragon(e) && e instanceof TamableAnimal t && !t.isTame(); }

    /** "Ember", or "a fire dragon" when it has no name. */
    public static String name(Entity e) {
        return e.hasCustomName() ? e.getCustomName().getString() : "a " + element(e) + " dragon";
    }

    // ------------------------------------------------------------------ eggs

    /** Element and variant of an Ice and Fire dragon egg item, or null. */
    @Nullable
    public static int[] egg(Item item) {
        ResourceLocation id = BuiltInRegistries.ITEM.getKey(item);
        if (!id.getNamespace().equals(IAF) || !id.getPath().startsWith("dragonegg_")) return null;
        int i = EGGS.indexOf(id.getPath().substring("dragonegg_".length()));
        return i < 0 ? null : new int[]{i / 4, i % 4};
    }

    @Nullable
    public static Item eggItem(int element, int variant) {
        if (!installed()) return null;
        Item it = BuiltInRegistries.ITEM.get(new ResourceLocation(IAF, "dragonegg_" + EGGS.get(element * 4 + variant)));
        return it == net.minecraft.world.item.Items.AIR ? null : it;
    }

    static final String[][] NAMES = {
            {"Ember", "Cinder", "Ashwing", "Pyra", "Scorch", "Brand", "Vulcan", "Smoulder"},
            {"Rime", "Frostfang", "Glacia", "Hoar", "Sleet", "Winterhide", "Shiver", "Icemaw"},
            {"Thunderclap", "Volt", "Stormwing", "Arc", "Tempest", "Skyrend", "Flash", "Galvan"}};

    /** A newly hatched, tamed dragon of this element and colour, at the given place. Null without Ice and Fire. */
    @Nullable
    public static TamableAnimal hatch(ServerLevel level, BlockPos at, int element, int variant, UUID owner, long seed) {
        if (!installed()) return null;
        EntityType<?> type = BuiltInRegistries.ENTITY_TYPE.get(new ResourceLocation(IAF, ELEMENTS.get(element) + "_dragon"));
        if (!BuiltInRegistries.ENTITY_TYPE.getKey(type).getNamespace().equals(IAF)) return null;
        Entity e = type.create(level);
        if (!(e instanceof TamableAnimal d)) { if (e != null) e.discard(); return null; }
        d.moveTo(at.getX() + .5, at.getY(), at.getZ() + .5, (float) (seed % 360), 0);
        try { d.finalizeSpawn(level, level.getCurrentDifficultyAt(at), MobSpawnType.BREEDING, null, null); }
        catch (Throwable t) { warn("finalizeSpawn", t); }
        call(d, "setVariant", int.class, variant);
        call(d, "setGender", boolean.class, (seed & 1) == 0);
        setAgeDays(d, 1);
        d.setTame(true);
        d.setOwnerUUID(owner);
        d.setPersistenceRequired();
        d.setCustomName(Component.literal(NAMES[element][(int) Math.floorMod(seed >> 3, NAMES[element].length)]));
        d.setHealth(d.getMaxHealth());
        command(d, SIT);
        level.addFreshEntity(d);
        return d;
    }

    /** A grown wild dragon (for tests): element, age in days. */
    @Nullable
    public static LivingEntity spawnWild(ServerLevel level, BlockPos at, int element, int days) {
        if (!installed()) return null;
        EntityType<?> type = BuiltInRegistries.ENTITY_TYPE.get(new ResourceLocation(IAF, ELEMENTS.get(element) + "_dragon"));
        Entity e = type.create(level);
        if (!(e instanceof LivingEntity d)) return null;
        d.moveTo(at.getX() + .5, at.getY(), at.getZ() + .5, 0, 0);
        setAgeDays(d, days);
        d.setHealth(d.getMaxHealth());
        level.addFreshEntity(d);
        return d;
    }

    // ------------------------------------------------------------------ Dragon Bond

    /** Dragon Bond's nest block, or a hay bale without it. */
    public static Block nest() {
        if (bond()) {
            Block b = BuiltInRegistries.BLOCK.get(new ResourceLocation(BOND, "dragon_nest"));
            if (b != Blocks.AIR) return b;
        }
        return Blocks.HAY_BLOCK;
    }

    /** Makes the roost the dragon's Dragon Bond home (its nest), so it heals and bonds there. */
    static void bondHome(TamableAnimal d, ServerLevel level, BlockPos nest) {
        if (!bond() || !level.getBlockState(nest).is(nest())) return;
        var root = d.getPersistentData();
        var data = root.getCompound("DragonBond");
        if (!root.contains("DragonBond")) data.putInt("level", 1);
        var h = data.getCompound("home");
        h.putString("dim", level.dimension().location().toString());
        h.putInt("x", nest.getX()); h.putInt("y", nest.getY()); h.putInt("z", nest.getZ());
        data.put("home", h);
        root.put("DragonBond", data);
    }

    /** Dragon Bond's level for the dragon, 0 without the mod. */
    static int bondLevel(Entity d) {
        if (!bond()) return 0;
        return d.getPersistentData().getCompound("DragonBond").getInt("level");
    }

    static String pretty(String element) { return element.substring(0, 1).toUpperCase(Locale.ROOT) + element.substring(1); }

    private DragonApi() {}
}
