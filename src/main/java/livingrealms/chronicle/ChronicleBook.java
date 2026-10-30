package livingrealms.chronicle;

import livingrealms.settlement.Settlement;
import net.minecraft.ChatFormatting;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;

/** The chronicle as a vanilla written book: a title page, then the entries oldest first, a heading for each year. */
public final class ChronicleBook {
    /** A vanilla book page holds 14 lines of about 19 characters. */
    static final int LINES = 14, CHARS = 19, MAX_PAGES = 100;
    /** Emeralds the town scribe charges for a copy from the City Manager. */
    public static final int FEE = 1;

    public static ItemStack make(Settlement s) {
        ItemStack book = new ItemStack(Items.WRITTEN_BOOK);
        CompoundTag tag = book.getOrCreateTag();
        String title = "Chronicle of " + (s.name.isEmpty() ? "a town" : s.name);
        tag.putString("title", title.length() > 32 ? title.substring(0, 32) : title);
        tag.putString("author", s.isAi() || s.ownerName.isEmpty() ? "the town scribe" : "the scribes of " + s.ownerName);
        tag.putInt("generation", 0);
        ListTag pages = new ListTag();
        for (Component page : pages(s)) pages.add(StringTag.valueOf(Component.Serializer.toJson(page)));
        tag.put("pages", pages);
        return book;
    }

    public static List<Component> pages(Settlement s) {
        List<Component> out = new ArrayList<>();
        var entries = s.chronicle.entries;
        MutableComponent cover = Component.literal("\n").append(Component.literal("The Chronicle of\n" + (s.name.isEmpty() ? "a town" : s.name))
                .withStyle(ChatFormatting.BOLD)).append(Component.literal("\n\n" + s.tier.label + "\n"
                + (entries.isEmpty() ? "No history yet." : entries.size() + " entries,\n" + Chronicle.date(entries.get(0).day()) + "\nto " + Chronicle.date(entries.get(entries.size() - 1).day()))
                + "\n\nWritten " + Chronicle.date(Chronicle.today())));
        out.add(cover);
        Page page = new Page();
        int year = -1;
        for (Chronicle.Entry e : entries) {
            int y = Chronicle.year(e.day());
            if (y != year) {
                year = y;
                if (page.lines > LINES - 4) out.add(page.flush());
                page.add(Component.literal("Year " + y + "\n").withStyle(ChatFormatting.BOLD, ChatFormatting.DARK_RED), 1);
            }
            String head = "Day " + e.day() + "\n";
            int need = 1 + lines(e.text()) + 1;
            if (page.lines + Math.min(need, 4) > LINES) out.add(page.flush());
            page.add(Component.literal(head).withStyle(ChatFormatting.DARK_GRAY), 1);
            // a long entry runs over to the next page word by word
            StringBuilder line = new StringBuilder();
            for (String word : e.text().split(" ")) {
                if (line.length() + word.length() + 1 > CHARS && line.length() > 0) {
                    if (page.lines + 1 > LINES) out.add(page.flush());
                    page.add(Component.literal(line + " "), 1);
                    line.setLength(0);
                }
                if (line.length() > 0) line.append(' ');
                line.append(word);
            }
            if (page.lines + 1 > LINES) out.add(page.flush());
            page.add(Component.literal(line + "\n\n"), 2);
            if (out.size() >= MAX_PAGES - 1) break;
        }
        if (page.text != null) out.add(page.flush());
        return out.size() > MAX_PAGES ? out.subList(0, MAX_PAGES) : out;
    }

    static int lines(String text) { return Math.max(1, (text.length() + CHARS - 1) / CHARS); }

    static final class Page {
        @Nullable MutableComponent text;
        int lines;
        void add(Component c, int n) {
            if (text == null) text = Component.empty();
            text.append(c);
            lines += n;
        }
        Component flush() {
            Component c = text == null ? Component.empty() : text;
            text = null;
            lines = 0;
            return c;
        }
    }

    /** City Manager: one emerald from the stockpile buys a fresh copy (free in creative). */
    @Nullable
    public static String give(ServerPlayer player, Settlement s) {
        if (!player.isCreative() && !s.stock.take(Items.EMERALD, FEE)) return "The scribe asks " + FEE + " emerald from the stockpile";
        ItemStack book = make(s);
        if (!player.getInventory().add(book)) player.drop(book, false);
        player.displayClientMessage(Component.literal("The scribe hands you the Chronicle of " + s.name).withStyle(ChatFormatting.GOLD), true);
        return null;
    }

    private ChronicleBook() {}
}
