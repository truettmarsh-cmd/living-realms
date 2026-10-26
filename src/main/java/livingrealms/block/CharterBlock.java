package livingrealms.block;

import livingrealms.Registry;
import livingrealms.building.BuildingCatalog;
import livingrealms.net.Net;
import livingrealms.settlement.*;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.DirectionProperty;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;

import javax.annotation.Nullable;

/** Placing the charter founds a settlement; using it opens the City Manager. */
public class CharterBlock extends BaseEntityBlock {
    public static final DirectionProperty FACING = HorizontalDirectionalBlock.FACING;
    static final VoxelShape SHAPE = Block.box(1, 0, 1, 15, 14, 15);

    public CharterBlock(Properties p) {
        super(p);
        registerDefaultState(stateDefinition.any().setValue(FACING, Direction.NORTH));
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> b) { b.add(FACING); }

    @Override
    public BlockState getStateForPlacement(BlockPlaceContext ctx) {
        return defaultBlockState().setValue(FACING, ctx.getHorizontalDirection().getOpposite());
    }

    @Override
    public VoxelShape getShape(BlockState s, BlockGetter l, BlockPos p, CollisionContext c) { return SHAPE; }

    @Override
    public RenderShape getRenderShape(BlockState s) { return RenderShape.MODEL; }

    @Nullable
    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) { return new CharterBlockEntity(pos, state); }

    @Override
    public void setPlacedBy(Level level, BlockPos pos, BlockState state, @Nullable LivingEntity placer, ItemStack stack) {
        if (!(level instanceof ServerLevel sl) || !(placer instanceof ServerPlayer player)) return;
        SettlementManager m = SettlementManager.get(sl.getServer());
        Settlement here = m.at(sl.dimension(), pos);
        if (here != null) {
            if (player.getUUID().equals(here.owner)) {
                link(sl, pos, here);
                player.displayClientMessage(Component.literal("A new seat of government for " + here.name).withStyle(ChatFormatting.GOLD), true);
                return;
            }
            refuse(sl, pos, player, stack, "This land belongs to " + here.name);
            return;
        }
        if (!m.roomFor(sl.dimension(), pos)) {
            Settlement near = m.nearest(sl.dimension(), pos, 100000);
            refuse(sl, pos, player, stack, "Too close to " + (near == null ? "another town" : near.name));
            return;
        }
        Settlement s = new Settlement();
        s.name = Names.town(sl.random);
        s.dim = sl.dimension();
        s.center = pos;
        s.owner = player.getUUID();
        s.ownerName = player.getGameProfile().getName();
        s.style = sl.getBiome(pos).unwrapKey().map(k -> k.location().toString()).orElse("minecraft:plains");
        s.founded = sl.getGameTime();
        s.population = 4;
        s.baseHousing = 6; // the founders' tents, until there are houses
        // the charter comes with what the first Town Hall needs, and food for the first days
        var hall = BuildingCatalog.blueprint(BuildingCatalog.get("livingrealms:town_hall").blueprintKey(1, s.style));
        if (hall != null) hall.bill().forEach((it, n) -> s.stock.force(it, n));
        s.stock.force(Items.BREAD, 48);
        s.stock.force(Items.EMERALD, 12);
        s.stock.force(Items.TORCH, 16);
        s.log(s.name + " was founded by " + s.ownerName);
        m.add(s);
        Simulation.recompute(s);
        link(sl, pos, s);
        if (!player.getInventory().contains(new ItemStack(Registry.PLANNER.get()))) player.getInventory().add(new ItemStack(Registry.PLANNER.get()));
        player.displayClientMessage(Component.literal("You founded " + s.name + "! Use the Architect's Plans to place the Town Hall.").withStyle(ChatFormatting.GOLD), false);
        Net.openManager(player, s);
    }

    static void link(ServerLevel level, BlockPos pos, Settlement s) {
        if (level.getBlockEntity(pos) instanceof CharterBlockEntity be) be.settlement = s.id;
    }

    static void refuse(ServerLevel level, BlockPos pos, ServerPlayer player, ItemStack stack, String why) {
        level.removeBlock(pos, false);
        if (!player.isCreative()) player.getInventory().add(new ItemStack(stack.getItem()));
        player.displayClientMessage(Component.literal(why).withStyle(ChatFormatting.RED), true);
    }

    @Override
    public InteractionResult use(BlockState state, Level level, BlockPos pos, Player player, InteractionHand hand, BlockHitResult hit) {
        if (level instanceof ServerLevel sl && player instanceof ServerPlayer sp) {
            SettlementManager m = SettlementManager.get(sl.getServer());
            Settlement s = level.getBlockEntity(pos) instanceof CharterBlockEntity be && be.settlement != null ? m.get(be.settlement) : null;
            if (s == null) s = m.at(sl.dimension(), pos);
            if (s != null) Net.openManager(sp, s);
        }
        return InteractionResult.sidedSuccess(level.isClientSide);
    }
}
