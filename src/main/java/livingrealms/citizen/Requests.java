package livingrealms.citizen;

import livingrealms.quest.QuestData;
import livingrealms.settlement.Job;
import livingrealms.settlement.Settlement;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.RandomSource;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;

/**
 * "A citizen asks…": now and then someone in a player's town wants something (a golden apple for a feverish child, iron
 * for the smith), posted on the town's bounty board as a delivery. Granting it gladdens them for a few days.
 */
public final class Requests {
    public static final double DAILY_CHANCE = 0.2, OPEN_DAYS = 3, GLAD_DAYS = 5;

    record Ask(Citizen who, Item item, int count, String why) {}

    static void daily(Settlement s, RandomSource r) {
        People p = s.people;
        if (p.askBy > 0 && (p.clock > p.askUntil || p.get(p.askBy) == null)) clear(p);
        if (s.isAi() || p.askBy > 0 || s.population < 4 || r.nextDouble() >= DAILY_CHANCE) return;
        Ask a = pick(s, r);
        if (a == null) return;
        open(p, a.who, a.item, a.count, a.why);
    }

    static void open(People p, Citizen who, Item item, int count, String why) {
        p.askBy = who.id;
        p.askItem = BuiltInRegistries.ITEM.getKey(item).toString();
        p.askCount = count;
        p.askWhy = why;
        p.askUntil = p.clock + OPEN_DAYS;
        p.askBounty = null;
    }

    @Nullable
    static Ask pick(Settlement s, RandomSource r) {
        People p = s.people;
        List<Ask> out = new ArrayList<>();
        for (Citizen k : p.children()) {
            if (!k.child(p.clock) || !k.has(Trait.SICKLY) && r.nextInt(4) != 0) continue;
            Citizen parent = p.get(k.mother);
            if (parent == null) parent = p.get(k.father);
            if (parent != null) out.add(new Ask(parent, Items.GOLDEN_APPLE, 1,
                    "little " + k.first + " has a fever, and only a golden apple will mend " + (k.female ? "her" : "him")));
        }
        for (Citizen c : p.adults()) {
            switch (c.job) {
                case SMITH -> out.add(new Ask(c, Items.IRON_INGOT, 16, "the smith wants iron for a new anvil"));
                case FARMER -> { if (r.nextInt(3) == 0) out.add(new Ask(c, Items.BONE_MEAL, 24, "a field is failing and needs bone meal")); }
                case SCHOLAR -> out.add(new Ask(c, Items.BOOK, 6, "the scholar wants books to copy"));
                case PRIEST -> out.add(new Ask(c, Items.CANDLE, 8, "the chapel has run out of candles"));
                case INNKEEPER -> out.add(new Ask(c, Items.HONEY_BOTTLE, 4, "the tavern wants honey for mead"));
                default -> {}
            }
        }
        return out.isEmpty() ? null : out.get(r.nextInt(out.size()));
    }

    static void clear(People p) {
        p.askBy = 0; p.askItem = ""; p.askWhy = ""; p.askCount = 0; p.askUntil = 0; p.askBounty = null;
    }

    public static String itemName(String id) {
        ResourceLocation rl = ResourceLocation.tryParse(id);
        Item it = rl == null ? Items.AIR : BuiltInRegistries.ITEM.get(rl);
        return it == Items.AIR ? id : it.getDescription().getString();
    }

    /** Called when the board has room (Bounties.make): the open request, once, as a delivery bounty. */
    @Nullable
    public static QuestData.Bounty bounty(Settlement s, long now, long lifetime) {
        People p = s.people;
        if (p.askBy <= 0 || p.askBounty != null) return null;
        Citizen who = p.get(p.askBy);
        ResourceLocation rl = ResourceLocation.tryParse(p.askItem);
        Item item = rl == null ? Items.AIR : BuiltInRegistries.ITEM.get(rl);
        if (who == null || item == Items.AIR) { clear(p); return null; }
        QuestData.Bounty x = new QuestData.Bounty();
        x.kind = QuestData.Kind.DELIVER;
        x.item = item;
        x.need = Math.max(1, p.askCount);
        x.reward = 2 + s.tier.number() + (item == Items.GOLDEN_APPLE ? 2 : 0);
        x.reason = who.name() + " asks: " + p.askWhy;
        x.posted = now;
        x.expires = now + lifetime;
        p.askBounty = x.id;
        p.askUntil = Math.max(p.askUntil, p.clock + OPEN_DAYS);
        s.dirty = true;
        return x;
    }

    /** A bounty was completed (Bounties.complete): if it was a citizen's request, they are glad of it. */
    public static void done(Settlement s, QuestData.Bounty x) {
        People p = s.people;
        if (p.askBounty == null || !p.askBounty.equals(x.id)) return;
        Citizen who = p.get(p.askBy);
        if (who != null) {
            who.gladUntil = p.clock + GLAD_DAYS;
            s.log(who.name() + " is grateful for the " + x.item.getDescription().getString().toLowerCase(java.util.Locale.ROOT));
        }
        clear(p);
        s.dirty = true;
    }

    /** Whether this citizen's job could ask for something (for the self-test). */
    static boolean asks(Job j) { return j == Job.SMITH || j == Job.SCHOLAR || j == Job.PRIEST || j == Job.INNKEEPER || j == Job.FARMER; }

    private Requests() {}
}
