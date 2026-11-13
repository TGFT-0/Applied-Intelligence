package dev.tgft.appliedintelligence;

import com.google.gson.JsonParser;
import java.util.List;
import net.minecraft.ChatFormatting;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.*;
import net.minecraft.world.item.component.CustomData;

/** The named physical item is the sole Skill source; task patterns carry immutable snapshots. */
final class CardItem extends Item {
  private final boolean task;

  CardItem(boolean task) {
    super(new Item.Properties().stacksTo(1));
    this.task = task;
  }

  @Override
  public void appendHoverText(
      ItemStack stack, TooltipContext context, List<Component> lines, TooltipFlag flag) {
    super.appendHoverText(stack, context, lines, flag);
    lines.add(
        Component.translatable(
                task
                    ? "item.appliedintelligence.task_pattern"
                    : "item.appliedintelligence.skill_card")
            .withStyle(ChatFormatting.GRAY));
    var tag = stack.getOrDefault(DataComponents.CUSTOM_DATA, CustomData.EMPTY).copyTag();
    String body = tag.getString(task ? "prompt" : "skill").replaceAll("\\s+", " ");
    if (!body.isBlank())
      lines.add(
          Component.literal(
                  body.substring(
                      0,
                      body.offsetByCodePoints(
                          0, Math.min(40, body.codePointCount(0, body.length())))))
              .withStyle(ChatFormatting.GRAY));
    if (task)
      lines.add(
          Component.translatable("gui.appliedintelligence.craft_recipe_hint")
              .withStyle(ChatFormatting.DARK_GRAY));
    if (task)
      try {
        int count = JsonParser.parseString(tag.getString("skills")).getAsJsonArray().size();
        lines.add(
            Component.translatable("gui.appliedintelligence.card_snapshot_count", count)
                .withStyle(ChatFormatting.GRAY));
      } catch (RuntimeException ignored) {
      }
  }
}
