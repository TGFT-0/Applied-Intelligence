package dev.tgft.appliedintelligence.crafting;

import java.util.List;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.*;
import net.minecraft.world.level.block.Block;

public final class AgentAssemblerItem extends BlockItem {
  public AgentAssemblerItem(Block block) {
    super(block, new Item.Properties());
  }

  @Override
  public void appendHoverText(
      ItemStack stack, TooltipContext ctx, List<Component> lines, TooltipFlag flag) {
    super.appendHoverText(stack, ctx, lines, flag);
    lines.add(
        Component.translatable("gui.appliedintelligence.assembler_item_hint")
            .withStyle(ChatFormatting.GRAY));
  }
}
