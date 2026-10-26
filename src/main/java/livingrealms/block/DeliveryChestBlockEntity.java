package livingrealms.block;

import livingrealms.Registry;
import livingrealms.settlement.Settlement;
import livingrealms.settlement.SettlementManager;
import net.minecraft.core.BlockPos;
import net.minecraft.core.NonNullList;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.ContainerHelper;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BaseContainerBlockEntity;
import net.minecraft.world.level.block.state.BlockState;

public class DeliveryChestBlockEntity extends BaseContainerBlockEntity {
    private NonNullList<ItemStack> items = NonNullList.withSize(27, ItemStack.EMPTY);

    public DeliveryChestBlockEntity(BlockPos pos, BlockState state) { super(Registry.DELIVERY_CHEST_BE.get(), pos, state); }

    public static void serverTick(Level level, BlockPos pos, BlockState state, DeliveryChestBlockEntity be) {
        if (level.getGameTime() % 40 != (pos.asLong() & 31) % 40 || !(level instanceof ServerLevel sl)) return;
        Settlement s = SettlementManager.get(sl.getServer()).at(sl.dimension(), pos);
        if (s == null || s.isAi()) return;
        boolean moved = false;
        for (ItemStack st : be.items) {
            if (st.isEmpty() || st.hasTag()) continue;
            int fit = s.stock.add(st.getItem(), st.getCount());
            if (fit > 0) { st.shrink(fit); moved = true; }
        }
        if (moved) { be.setChanged(); s.dirty = true; }
    }

    @Override
    protected Component getDefaultName() { return Component.translatable("block.livingrealms.delivery_barrel"); }

    @Override
    protected AbstractContainerMenu createMenu(int id, Inventory inv) { return ChestMenu.threeRows(id, inv, this); }

    @Override
    public int getContainerSize() { return 27; }

    @Override
    public boolean isEmpty() { return items.stream().allMatch(ItemStack::isEmpty); }

    @Override
    public ItemStack getItem(int i) { return items.get(i); }

    @Override
    public ItemStack removeItem(int i, int n) {
        ItemStack r = ContainerHelper.removeItem(items, i, n);
        if (!r.isEmpty()) setChanged();
        return r;
    }

    @Override
    public ItemStack removeItemNoUpdate(int i) { return ContainerHelper.takeItem(items, i); }

    @Override
    public void setItem(int i, ItemStack s) {
        items.set(i, s);
        setChanged();
    }

    @Override
    public boolean stillValid(net.minecraft.world.entity.player.Player p) { return p.distanceToSqr(worldPosition.getCenter()) < 64; }

    @Override
    public void clearContent() { items.clear(); }

    @Override
    protected void saveAdditional(CompoundTag t) {
        super.saveAdditional(t);
        ContainerHelper.saveAllItems(t, items);
    }

    @Override
    public void load(CompoundTag t) {
        super.load(t);
        items = NonNullList.withSize(27, ItemStack.EMPTY);
        ContainerHelper.loadAllItems(t, items);
    }
}
