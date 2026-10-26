package livingrealms.smithing;

import livingrealms.LivingRealms;
import livingrealms.building.BuildingCatalog;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.item.*;
import net.minecraft.world.item.crafting.*;
import net.minecraftforge.fml.ModList;

import java.util.*;

/** Optional equipment is discovered on the server after recipes and tags have finished loading. */
public final class EquipmentDiscovery {
    public static Set<String> namespaces() {
        Set<String> ids = new HashSet<>(Set.of("magistuarmory", "iceandfire"));
        boolean changed;
        do {
            changed = false;
            for (var mod : ModList.get().getMods()) {
                if (mod.getModId().equals(LivingRealms.MODID)) continue;
                // Dependency closure covers addons of addons without a maintained list of item ids.
                if (mod.getDependencies().stream().anyMatch(d -> d.isMandatory() && ids.contains(d.getModId())))
                    changed |= ids.add(mod.getModId());
                if (mod.getDisplayName().toLowerCase(Locale.ROOT).contains("epic knights")) changed |= ids.add(mod.getModId());
            }
        } while (changed);
        return ids;
    }

    public static Piece piece(Item item) {
        if (item instanceof ArmorItem a) return switch (a.getEquipmentSlot()) {
            case HEAD -> Piece.HELMET; case CHEST -> Piece.CHESTPLATE;
            case LEGS -> Piece.LEGGINGS; case FEET -> Piece.BOOTS; default -> null;
        };
        String path = BuiltInRegistries.ITEM.getKey(item).getPath();
        if (path.startsWith("dragonarmor_") || path.startsWith("hippogryph_armor_") || item instanceof HorseArmorItem) return Piece.DRAGON_ARMOR;
        if (BuiltInRegistries.ITEM.getKey(item).getNamespace().equals("iceandfire")
                && (path.contains("gauntlet") || path.equals("gorgon_head") || path.equals("cockatrice_scepter")
                || path.equals("lich_staff") || path.equals("dread_queen_staff"))) return Piece.SPECIAL_WEAPON;
        if (item instanceof ShieldItem) return Piece.SHIELD;
        if (item instanceof CrossbowItem) return Piece.CROSSBOW;
        if (item instanceof BowItem) return Piece.BOW;
        if (item instanceof ArrowItem) return Piece.ARROWS;
        if (item instanceof AxeItem || path.contains("axe") && !path.contains("pickaxe")) return Piece.AXE;
        if (item instanceof TridentItem || path.contains("spear") || path.contains("pike") || path.contains("lance") || path.contains("halberd") || path.contains("glaive")) return Piece.LANCE;
        if (path.contains("mace") || path.contains("hammer") || path.contains("club")) return Piece.MACE;
        if (item instanceof SwordItem || (!(item instanceof DiggerItem) && !item.getDefaultAttributeModifiers(EquipmentSlot.MAINHAND).get(Attributes.ATTACK_DAMAGE).isEmpty())) return Piece.SWORD;
        return null;
    }

    public static void add(MinecraftServer server, Map<String, Smithing.Pattern> out) {
        Set<String> mods = namespaces();
        Map<Item, Recipe<?>> recipes = new HashMap<>();
        // Prefer a complete shaped recipe over dyeing/conversion recipes; ties are stable across reloads.
        List<Recipe<?>> ordered = new ArrayList<>(server.getRecipeManager().getRecipes());
        ordered.sort(Comparator.<Recipe<?>>comparingInt(r -> r.getIngredients().size()).reversed().thenComparing(r -> r.getId().toString()));
        for (Recipe<?> r : ordered) {
            if (!(r instanceof ShapedRecipe || r instanceof ShapelessRecipe || r instanceof SmithingTransformRecipe)) continue;
            ItemStack result = r.getResultItem(server.registryAccess());
            if (result.isEmpty() || !plain(result) || !mods.contains(BuiltInRegistries.ITEM.getKey(result.getItem()).getNamespace())) continue;
            if (cost(r).isEmpty()) continue;
            recipes.putIfAbsent(result.getItem(), r);
        }
        Map<String, Map<Piece, Smithing.Recipe>> sets = new TreeMap<>();
        Map<String, String> setResearch = new HashMap<>();
        int discovered = 0, commissions = 0;
        for (Item item : BuiltInRegistries.ITEM) {
            ResourceLocation id = BuiltInRegistries.ITEM.getKey(item);
            if (!mods.contains(id.getNamespace())) continue;
            Piece piece = piece(item);
            if (piece == null) continue;
            Recipe<?> recipe = recipes.get(item);
            Map<String, Integer> cost = recipe == null ? commission(item, piece) : cost(recipe);
            if (cost.isEmpty()) continue;
            String research = research(id, piece);
            int count = recipe == null ? 1 : recipe.getResultItem(server.registryAccess()).getCount();
            if (recipe == null) { research = "master_armourer"; commissions++; }
            Smithing.Recipe result = new Smithing.Recipe(item, count, cost, research);
            String name = item.getDescription().getString();
            out.put("equipment/" + id.getNamespace() + "/" + id.getPath(), new Smithing.Pattern(
                    "equipment/" + id.getNamespace() + "/" + id.getPath(), name + (recipe == null ? " · commission" : ""), research,
                    100, id.getNamespace().equals("iceandfire") ? 0x82CBE8 : 0xC0B08A, Map.of(piece, result)));
            discovered++;
            if (piece.armor()) {
                String stem = id.getPath().replaceFirst("_(helmet|chestplate|leggings|boots)$", "");
                if (!stem.equals(id.getPath())) {
                    String group = id.getNamespace() + "/" + stem;
                    sets.computeIfAbsent(group, k -> new EnumMap<>(Piece.class)).put(piece, result);
                    setResearch.put(group, research);
                }
            }
        }
        sets.forEach((id, pieces) -> {
            if (pieces.size() != 4) return;
            String key = "equipment_set/" + id;
            out.put(key, new Smithing.Pattern(key, BuildingCatalog.pretty(id.substring(id.indexOf('/') + 1)),
                    setResearch.get(id), 101, 0xC0B08A, pieces));
        });
        LivingRealms.LOG.info("Living Realms equipment: {} items ({} master commissions), {} complete armour sets; namespaces {}",
                discovered, commissions, sets.values().stream().filter(s -> s.size() == 4).count(), mods);
    }

    private static boolean plain(ItemStack stack) {
        // New damageable stacks carry Damage:0; only non-default NBT is unsupported by the stockpile.
        return !stack.hasTag() || java.util.Objects.equals(stack.getTag(), new ItemStack(stack.getItem()).getTag());
    }

    /** Use one actual accepted ingredient per slot. Exact item costs avoid consuming overlapping tags twice. */
    private static Map<String, Integer> cost(Recipe<?> recipe) {
        Map<String, Integer> result = new LinkedHashMap<>();
        if (recipe instanceof SmithingTransformRecipe smithing) {
            // Vanilla smithing does not expose its three slots through getIngredients().
            List<java.util.function.Predicate<ItemStack>> slots = List.of(smithing::isTemplateIngredient, smithing::isBaseIngredient, smithing::isAdditionIngredient);
            for (var slot : slots) {
                Item chosen = BuiltInRegistries.ITEM.stream().filter(item -> slot.test(new ItemStack(item)))
                        .min(Comparator.comparing(item -> BuiltInRegistries.ITEM.getKey(item).toString())).orElse(null);
                if (chosen == null) return Map.of();
                result.merge(BuiltInRegistries.ITEM.getKey(chosen).toString(), 1, Integer::sum);
            }
            return result;
        }
        for (Ingredient ingredient : recipe.getIngredients()) {
            if (ingredient.isEmpty()) continue;
            if (!ingredient.isSimple()) return Map.of(); // don't discard custom NBT requirements
            ItemStack choice = Arrays.stream(ingredient.getItems()).filter(s -> !s.isEmpty() && plain(s))
                    .sorted(Comparator.comparing(s -> BuiltInRegistries.ITEM.getKey(s.getItem()).toString())).findFirst().orElse(ItemStack.EMPTY);
            if (choice.isEmpty()) return Map.of();
            result.merge(BuiltInRegistries.ITEM.getKey(choice.getItem()).toString(), 1, Integer::sum);
        }
        return result;
    }

    /** Explicit expensive commissions for loot-only gear; never invent cheap iron substitutes for legendary gear. */
    private static Map<String, Integer> commission(Item item, Piece piece) {
        Map<String, Integer> cost = new LinkedHashMap<>();
        ResourceLocation id = BuiltInRegistries.ITEM.getKey(item);
        Ingredient material = item instanceof ArmorItem a ? a.getMaterial().getRepairIngredient()
                : item instanceof TieredItem t ? t.getTier().getRepairIngredient() : Ingredient.EMPTY;
        ItemStack[] repair = material.getItems();
        if (repair.length > 0 && plain(repair[0])) cost.put(BuiltInRegistries.ITEM.getKey(repair[0].getItem()).toString(), piece.armor() ? 12 : 8);
        if (id.getNamespace().equals("iceandfire")) {
            Item bone = BuiltInRegistries.ITEM.get(new ResourceLocation("iceandfire", "dragonbone"));
            if (bone != Items.AIR) cost.put("iceandfire:dragonbone", 16);
        }
        cost.put("minecraft:netherite_ingot", 4);
        cost.put("minecraft:diamond", 8);
        cost.put("minecraft:emerald", 32);
        return cost;
    }

    private static String research(ResourceLocation id, Piece piece) {
        String p = id.getPath();
        if (p.contains("dragonsteel")) return "dragonsteel_forging";
        if (id.getNamespace().equals("iceandfire")) return "mythic_smithing";
        if (p.contains("netherite")) return "netherite_forging";
        if (p.contains("diamond")) return "diamond_smithing";
        return piece.armor() ? "plate_armour" : "steelmaking";
    }
    private EquipmentDiscovery() {}
}
