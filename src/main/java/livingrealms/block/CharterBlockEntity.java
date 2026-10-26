package livingrealms.block;

import livingrealms.Registry;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

import javax.annotation.Nullable;
import java.util.UUID;

public class CharterBlockEntity extends BlockEntity {
    @Nullable public UUID settlement;

    public CharterBlockEntity(BlockPos pos, BlockState state) { super(Registry.CHARTER_BE.get(), pos, state); }

    @Override
    protected void saveAdditional(CompoundTag t) {
        super.saveAdditional(t);
        if (settlement != null) t.putUUID("settlement", settlement);
    }

    @Override
    public void load(CompoundTag t) {
        super.load(t);
        settlement = t.hasUUID("settlement") ? t.getUUID("settlement") : null;
    }
}
