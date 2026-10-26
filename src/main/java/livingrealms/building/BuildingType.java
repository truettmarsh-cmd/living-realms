package livingrealms.building;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;

import java.util.ArrayList;
import java.util.List;

/**
 * Something a settlement can build. Procedural types ({@code proc:}) have one blueprint per level and change shape when
 * upgraded; template types (found among the structure templates of vanilla and modded villages) keep their shape and
 * gain workplaces and housing per level.
 */
public final class BuildingType {
    public final String id;
    public final String name;
    public final Category category;
    /** Style key such as {@code minecraft:plains} or {@code any} for procedural types that follow the settlement style. */
    public final String style;
    public final String styleName;
    /** Blueprint keys, one per level. Procedural keys end without a style; the settlement's style is appended. */
    public final List<String> levels;
    public final int maxLevel;
    public final int minTier;
    public final String research;
    public final int maxCount;
    public final int sink;
    public final String source;
    /** Extra keyword for workshops (butcher, fisher, mason...) that picks what they produce. */
    public final String trade;
    // filled from the level 1 blueprint
    public int w, h, d, beds, blocks;

    public BuildingType(String id, String name, Category category, String style, String styleName, List<String> levels,
                        int maxLevel, int minTier, String research, int maxCount, int sink, String source, String trade) {
        this.id = id;
        this.name = name;
        this.category = category;
        this.style = style;
        this.styleName = styleName;
        this.levels = levels;
        this.maxLevel = maxLevel;
        this.minTier = minTier;
        this.research = research;
        this.maxCount = maxCount;
        this.sink = sink;
        this.source = source;
        this.trade = trade;
    }

    public boolean procedural() { return levels.get(0).startsWith("proc:"); }

    /** Blueprint key for a level, in the given settlement style for procedural types. */
    public String blueprintKey(int level, String settlementStyle) {
        String k = levels.get(Math.min(level, levels.size()) - 1);
        return k.startsWith("proc:") ? k + "/" + Procedural.styleFamily(settlementStyle) : k;
    }

    public CompoundTag toTag() {
        CompoundTag t = new CompoundTag();
        t.putString("id", id);
        t.putString("name", name);
        t.putString("cat", category.name());
        t.putString("style", style);
        t.putString("styleName", styleName);
        ListTag l = new ListTag();
        for (String s : levels) l.add(StringTag.valueOf(s));
        t.put("levels", l);
        t.putInt("maxLevel", maxLevel);
        t.putInt("tier", minTier);
        t.putString("research", research == null ? "" : research);
        t.putInt("maxCount", maxCount);
        t.putInt("sink", sink);
        t.putString("source", source);
        t.putString("trade", trade == null ? "" : trade);
        t.putIntArray("dims", new int[]{w, h, d, beds, blocks});
        return t;
    }

    public static BuildingType fromTag(CompoundTag t) {
        List<String> levels = new ArrayList<>();
        for (Tag s : t.getList("levels", Tag.TAG_STRING)) levels.add(s.getAsString());
        String research = t.getString("research");
        String trade = t.getString("trade");
        BuildingType b = new BuildingType(t.getString("id"), t.getString("name"), Category.valueOf(t.getString("cat")),
                t.getString("style"), t.getString("styleName"), levels, t.getInt("maxLevel"), t.getInt("tier"),
                research.isEmpty() ? null : research, t.getInt("maxCount"), t.getInt("sink"), t.getString("source"),
                trade.isEmpty() ? null : trade);
        int[] dims = t.getIntArray("dims");
        if (dims.length == 5) { b.w = dims[0]; b.h = dims[1]; b.d = dims[2]; b.beds = dims[3]; b.blocks = dims[4]; }
        return b;
    }
}
