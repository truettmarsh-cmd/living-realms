package livingrealms;

import livingrealms.block.CharterBlock;
import livingrealms.block.CharterBlockEntity;
import livingrealms.block.DeliveryChestBlock;
import livingrealms.block.DeliveryChestBlockEntity;
import livingrealms.entity.SettlerEntity;
import livingrealms.item.PlannerItem;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Rarity;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.material.MapColor;
import net.minecraftforge.event.entity.EntityAttributeCreationEvent;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;

public final class Registry {
    static final DeferredRegister<Block> BLOCKS = DeferredRegister.create(ForgeRegistries.BLOCKS, LivingRealms.MODID);
    static final DeferredRegister<Item> ITEMS = DeferredRegister.create(ForgeRegistries.ITEMS, LivingRealms.MODID);
    static final DeferredRegister<BlockEntityType<?>> BLOCK_ENTITIES = DeferredRegister.create(ForgeRegistries.BLOCK_ENTITY_TYPES, LivingRealms.MODID);
    static final DeferredRegister<EntityType<?>> ENTITIES = DeferredRegister.create(ForgeRegistries.ENTITY_TYPES, LivingRealms.MODID);
    static final DeferredRegister<CreativeModeTab> TABS = DeferredRegister.create(Registries.CREATIVE_MODE_TAB, LivingRealms.MODID);

    public static final RegistryObject<Block> CHARTER = BLOCKS.register("town_charter", () -> new CharterBlock(
            BlockBehaviour.Properties.of().mapColor(MapColor.WOOD).strength(2.5f, 1200f).sound(SoundType.WOOD).noOcclusion()));
    public static final RegistryObject<Block> DELIVERY_CHEST = BLOCKS.register("delivery_barrel", () -> new DeliveryChestBlock(
            BlockBehaviour.Properties.of().mapColor(MapColor.WOOD).strength(2.5f).sound(SoundType.WOOD)));

    public static final RegistryObject<Item> CHARTER_ITEM = ITEMS.register("town_charter",
            () -> new BlockItem(CHARTER.get(), new Item.Properties().stacksTo(1).rarity(Rarity.RARE)));
    public static final RegistryObject<Item> DELIVERY_CHEST_ITEM = ITEMS.register("delivery_barrel",
            () -> new BlockItem(DELIVERY_CHEST.get(), new Item.Properties()));
    public static final RegistryObject<Item> PLANNER = ITEMS.register("architects_plans",
            () -> new PlannerItem(new Item.Properties().stacksTo(1).rarity(Rarity.UNCOMMON)));

    @SuppressWarnings("ConstantConditions")
    public static final RegistryObject<BlockEntityType<CharterBlockEntity>> CHARTER_BE = BLOCK_ENTITIES.register("town_charter",
            () -> BlockEntityType.Builder.of(CharterBlockEntity::new, CHARTER.get()).build(null));
    @SuppressWarnings("ConstantConditions")
    public static final RegistryObject<BlockEntityType<DeliveryChestBlockEntity>> DELIVERY_CHEST_BE = BLOCK_ENTITIES.register("delivery_barrel",
            () -> BlockEntityType.Builder.of(DeliveryChestBlockEntity::new, DELIVERY_CHEST.get()).build(null));

    public static final RegistryObject<EntityType<SettlerEntity>> SETTLER = ENTITIES.register("settler",
            () -> EntityType.Builder.of(SettlerEntity::new, MobCategory.MISC).sized(0.6f, 1.95f).clientTrackingRange(10)
                    .build(LivingRealms.id("settler").toString()));

    public static final RegistryObject<CreativeModeTab> TAB = TABS.register("main", () -> CreativeModeTab.builder()
            .title(Component.translatable("itemGroup.livingrealms"))
            .icon(() -> new ItemStack(CHARTER_ITEM.get()))
            .displayItems((params, out) -> {
                out.accept(CHARTER_ITEM.get());
                out.accept(PLANNER.get());
                out.accept(DELIVERY_CHEST_ITEM.get());
            }).build());

    static void register(IEventBus bus) {
        BLOCKS.register(bus);
        ITEMS.register(bus);
        BLOCK_ENTITIES.register(bus);
        ENTITIES.register(bus);
        TABS.register(bus);
        bus.addListener((EntityAttributeCreationEvent e) -> e.put(SETTLER.get(), SettlerEntity.attributes().build()));
    }

    private Registry() {}
}
