package dev.tgft.appliedintelligence.crafting;

import dev.tgft.appliedintelligence.local.NativeAgents;
import java.util.List;
import net.minecraft.ChatFormatting;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.*;
import net.minecraft.world.*;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.*;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.level.Level;

public final class ReportItem extends Item {
  public ReportItem() {
    super(new Properties());
  }

  @Override
  public void appendHoverText(
      ItemStack stack, TooltipContext ctx, List<Component> lines, TooltipFlag flag) {
    lines.add(
        Component.translatable("gui.appliedintelligence.report_claim_hint")
            .withStyle(ChatFormatting.GRAY));
  }

  @Override
  public void inventoryTick(
      ItemStack stack,
      Level level,
      net.minecraft.world.entity.Entity entity,
      int slot,
      boolean selected) {
    if (!(entity instanceof ServerPlayer owner)
        || !NativeAgents.allowed(owner)
        || slot < 0
        || (slot >= 36 && slot != 40)
        || owner.getInventory().getItem(slot) != stack
        || owner.tickCount % 5 != 0) return;
    // A multi-product stack needs another inventory slot; never eject reports silently.
    if (stack.getCount() > 1 && owner.getInventory().getFreeSlot() < 0) return;
    String recipe =
        stack
            .getOrDefault(DataComponents.CUSTOM_DATA, CustomData.EMPTY)
            .copyTag()
            .getString("recipe");
    ItemStack book = ReportArchive.get((ServerLevel) level).claim(recipe);
    if (book.isEmpty()) return;
    stack.shrink(1);
    if (stack.isEmpty()) owner.getInventory().setItem(slot, book);
    else if (!owner.addItem(book)) owner.drop(book, false);
    owner.getInventory().setChanged();
    owner.containerMenu.broadcastChanges();
  }

  @Override
  public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
    ItemStack held = player.getItemInHand(hand);
    if (level.isClientSide) return InteractionResultHolder.success(held);
    if (!(player instanceof ServerPlayer owner) || !NativeAgents.allowed(owner))
      return InteractionResultHolder.fail(held);
    if (held.getCount() > 1 && owner.getInventory().getFreeSlot() < 0) {
      player.displayClientMessage(
          Component.translatable("gui.appliedintelligence.report_inventory_full"), true);
      return InteractionResultHolder.fail(held);
    }
    String key =
        held.getOrDefault(DataComponents.CUSTOM_DATA, CustomData.EMPTY)
            .copyTag()
            .getString("recipe");
    ItemStack book = ReportArchive.get((ServerLevel) level).claim(key);
    if (book.isEmpty()) {
      player.displayClientMessage(
          Component.translatable("gui.appliedintelligence.report_unavailable"), false);
      return InteractionResultHolder.fail(held);
    }
    held.shrink(1); // Creative players also consume receipts: one successful run, one bound book.
    if (held.isEmpty()) {
      player.setItemInHand(hand, book);
      player.openItemGui(book, hand);
      return InteractionResultHolder.success(book);
    }
    if (!player.addItem(book)) player.drop(book, false);
    return InteractionResultHolder.success(held);
  }
}
