package livingrealms.wonders;

import livingrealms.LivingRealms;
import livingrealms.settlement.Settlement;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.item.ArmorItem;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.event.ItemAttributeModifierEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.util.UUID;

/**
 * Masterwork gear (Grand Forge): armour and weapons a Grand Forge kingdom's armoury hands to its recruits carry one tag
 * and a small bonus: +1 armour and +1 toughness on armour, +1.5 attack damage on melee weapons. The bonus is added by
 * {@link ItemAttributeModifierEvent}, so the item keeps its own figures. The stockpile keeps plain items only: an unworn
 * masterwork piece goes back as a plain one ({@link #plain}); a worn one stays with its recruit like any worn gear. In a
 * player's inventory it is plain again ({@link #playerTick}).
 */
@Mod.EventBusSubscriber(modid = LivingRealms.MODID)
public final class Masterwork {
    public static final String TAG = "LivingRealmsMasterwork";
    public static final double ARMOR = 1, TOUGHNESS = 1, DAMAGE = 1.5;
    static final UUID[] ARMOR_IDS = {UUID.fromString("6c9b6f3e-2a51-4f0e-9a51-5e1d2b7a0c01"), UUID.fromString("6c9b6f3e-2a51-4f0e-9a51-5e1d2b7a0c02"),
            UUID.fromString("6c9b6f3e-2a51-4f0e-9a51-5e1d2b7a0c03"), UUID.fromString("6c9b6f3e-2a51-4f0e-9a51-5e1d2b7a0c04")};
    static final UUID[] TOUGH_IDS = {UUID.fromString("6c9b6f3e-2a51-4f0e-9a51-5e1d2b7a0c11"), UUID.fromString("6c9b6f3e-2a51-4f0e-9a51-5e1d2b7a0c12"),
            UUID.fromString("6c9b6f3e-2a51-4f0e-9a51-5e1d2b7a0c13"), UUID.fromString("6c9b6f3e-2a51-4f0e-9a51-5e1d2b7a0c14")};
    static final UUID DAMAGE_ID = UUID.fromString("6c9b6f3e-2a51-4f0e-9a51-5e1d2b7a0c21");

    public static boolean is(ItemStack st) { return !st.isEmpty() && st.hasTag() && st.getTag().getBoolean(TAG); }

    /** Armour, or a weapon whose main-hand figures include attack damage. */
    public static boolean eligible(ItemStack st) {
        if (st.isEmpty() || st.getMaxStackSize() > 1) return false;
        if (st.getItem() instanceof ArmorItem) return true;
        return st.getItem().getDefaultAttributeModifiers(EquipmentSlot.MAINHAND).containsKey(Attributes.ATTACK_DAMAGE);
    }

    /** The stack as the town's armoury hands it over: Masterwork when the kingdom has the Grand Forge. */
    public static ItemStack forged(Settlement s, ItemStack st) {
        if (eligible(st) && Wonders.has(s, Wonder.FORGE)) mark(st);
        return st;
    }

    public static ItemStack mark(ItemStack st) {
        st.getOrCreateTag().putBoolean(TAG, true);
        return st;
    }

    /** A copy without the masterwork mark (so an unworn piece can go back to the stockpile); other stacks unchanged. */
    public static ItemStack plain(ItemStack st) {
        if (!is(st)) return st;
        ItemStack c = st.copy();
        c.getTag().remove(TAG);
        if (c.getTag().isEmpty()) c.setTag(null);
        return c;
    }

    @SubscribeEvent
    public static void attributes(ItemAttributeModifierEvent e) {
        ItemStack st = e.getItemStack();
        if (!is(st)) return;
        EquipmentSlot slot = e.getSlotType();
        if (st.getItem() instanceof ArmorItem armor) {
            if (armor.getEquipmentSlot() != slot) return;
            int i = slot.getIndex() & 3;
            e.addModifier(Attributes.ARMOR, new AttributeModifier(ARMOR_IDS[i], "Masterwork", ARMOR, AttributeModifier.Operation.ADDITION));
            e.addModifier(Attributes.ARMOR_TOUGHNESS, new AttributeModifier(TOUGH_IDS[i], "Masterwork", TOUGHNESS, AttributeModifier.Operation.ADDITION));
        } else if (slot == EquipmentSlot.MAINHAND && e.getOriginalModifiers().containsKey(Attributes.ATTACK_DAMAGE)) {
            e.addModifier(Attributes.ATTACK_DAMAGE, new AttributeModifier(DAMAGE_ID, "Masterwork", DAMAGE, AttributeModifier.Operation.ADDITION));
        }
    }

    /**
     * Masterwork is the kingdom's gift to its recruits, not loot: a piece that reaches a player's inventory (taken off a
     * recruit, or picked up from a fallen one) loses its mark within a second and is plain gear again.
     */
    @SubscribeEvent
    public static void playerTick(net.minecraftforge.event.TickEvent.PlayerTickEvent e) {
        if (e.phase != net.minecraftforge.event.TickEvent.Phase.END || e.player.level().isClientSide || e.player.tickCount % 20 != 0) return;
        var inv = e.player.getInventory();
        for (int i = 0; i < inv.getContainerSize(); i++) {
            ItemStack st = inv.getItem(i);
            if (is(st)) inv.setItem(i, plain(st));
        }
    }

    /** The tooltip line, client side only. */
    @Mod.EventBusSubscriber(modid = LivingRealms.MODID, value = net.minecraftforge.api.distmarker.Dist.CLIENT)
    public static final class Tooltip {
        @SubscribeEvent
        public static void tooltip(net.minecraftforge.event.entity.player.ItemTooltipEvent e) {
            if (is(e.getItemStack())) e.getToolTip().add(1, Component.literal("Masterwork · Grand Forge").withStyle(ChatFormatting.GOLD));
        }
    }

    private Masterwork() {}
}
