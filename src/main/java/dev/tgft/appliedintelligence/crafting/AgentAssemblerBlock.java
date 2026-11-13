package dev.tgft.appliedintelligence.crafting;

import com.mojang.serialization.MapCodec;
import dev.tgft.appliedintelligence.AppliedIntelligence;
import net.minecraft.core.BlockPos;
import net.minecraft.world.*;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.*;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.entity.*;
import net.minecraft.world.level.block.state.*;
import net.minecraft.world.phys.BlockHitResult;

public final class AgentAssemblerBlock extends BaseEntityBlock {
  public static final MapCodec<AgentAssemblerBlock> CODEC = simpleCodec(AgentAssemblerBlock::new);

  public AgentAssemblerBlock(Properties properties) {
    super(properties);
  }

  @Override
  protected MapCodec<? extends BaseEntityBlock> codec() {
    return CODEC;
  }

  @Override
  protected RenderShape getRenderShape(BlockState state) {
    return RenderShape.MODEL;
  }

  @Override
  public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
    return new AgentAssembler(pos, state);
  }

  @Override
  public <T extends BlockEntity> BlockEntityTicker<T> getTicker(
      Level level, BlockState state, BlockEntityType<T> type) {
    return level.isClientSide
        ? null
        : createTickerHelper(
            type, AppliedIntelligence.ASSEMBLER_ENTITY.get(), (l, p, s, be) -> be.tick());
  }

  @Override
  protected InteractionResult useWithoutItem(
      BlockState state, Level level, BlockPos pos, Player player, BlockHitResult hit) {
    if (!level.isClientSide && level.getBlockEntity(pos) instanceof AgentAssembler machine)
      machine.interact(player);
    return InteractionResult.sidedSuccess(level.isClientSide);
  }

  @Override
  protected void onRemove(
      BlockState state, Level level, BlockPos pos, BlockState next, boolean moving) {
    if (!state.is(next.getBlock())
        && level.getBlockEntity(pos) instanceof AgentAssembler machine
        && !level.isClientSide) machine.dropContents();
    super.onRemove(state, level, pos, next, moving);
  }
}
