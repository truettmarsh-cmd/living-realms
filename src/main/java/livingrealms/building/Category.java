package livingrealms.building;

import livingrealms.settlement.Job;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;

/** What a building is for. The job and number of workplaces per building level come from here. */
public enum Category {
    CIVIC("Civic", 0xE8C15A, null, 0, Items.BELL),
    HOUSING("Housing", 0x8CC084, null, 0, Items.RED_BED),
    CONSTRUCTION("Construction", 0xD39A5C, Job.BUILDER, 2, Items.SCAFFOLDING),
    FARM("Farming", 0xB8D35C, Job.FARMER, 2, Items.WHEAT),
    LUMBER("Lumber", 0x9C7A4E, Job.LUMBERJACK, 2, Items.OAK_LOG),
    MINE("Mining", 0x9AA3AD, Job.MINER, 2, Items.IRON_PICKAXE),
    SMITHY("Smithing", 0xE07A4A, Job.SMITH, 2, Items.ANVIL),
    LIBRARY("Scholarship", 0x7FA6E0, Job.SCHOLAR, 2, Items.BOOKSHELF),
    MARKET("Trade", 0x5FD0A0, Job.MERCHANT, 2, Items.EMERALD),
    WORKSHOP("Crafts", 0xC7A0D8, Job.CRAFTSMAN, 1, Items.CRAFTING_TABLE),
    TAVERN("Tavern", 0xD8A848, Job.INNKEEPER, 1, Items.HONEY_BOTTLE),
    CHAPEL("Faith", 0xF0E6C8, Job.PRIEST, 1, Items.CANDLE),
    STABLE("Stables", 0xB08860, Job.STABLEHAND, 1, Items.SADDLE),
    STORAGE("Storage", 0xA88B6A, Job.HAULER, 1, Items.BARREL),
    BARRACKS("Barracks", 0xD05050, null, 0, Items.IRON_SWORD),
    TOWER("Defence", 0x8F9AB0, null, 0, Items.STONE_BRICKS),
    WALL("Walls", 0x7C8494, null, 0, Items.STONE_BRICK_WALL),
    DECOR("Decoration", 0xE89AC8, null, 0, Items.POPPY),
    ROAD("Roads", 0xB09A7A, null, 0, Items.DIRT_PATH);

    public final String label;
    public final int color;
    public final Job job;
    public final int slotsPerLevel;
    public final Item icon;

    Category(String label, int color, Job job, int slotsPerLevel, Item icon) {
        this.label = label;
        this.color = color;
        this.job = job;
        this.slotsPerLevel = slotsPerLevel;
        this.icon = icon;
    }
}
