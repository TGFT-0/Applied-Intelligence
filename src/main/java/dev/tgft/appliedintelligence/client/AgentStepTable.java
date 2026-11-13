package dev.tgft.appliedintelligence.client;

import static dev.tgft.appliedintelligence.client.TerminalExtension.*;

import appeng.api.stacks.AEItemKey;
import appeng.client.gui.AEBaseScreen;
import appeng.client.gui.me.crafting.CraftingStatusTableRenderer;
import appeng.menu.me.crafting.CraftingStatusEntry;
import java.util.*;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.component.CustomData;

final class AgentStepTable extends CraftingStatusTableRenderer {
  AgentStepTable(AEBaseScreen<?> screen) {
    super(screen, 9, 19);
  }

  private CompoundTag step(CraftingStatusEntry entry) {
    if (entry.getWhat() instanceof AEItemKey item) {
      var tag = item.toStack().getOrDefault(DataComponents.CUSTOM_DATA, CustomData.EMPTY).copyTag();
      if (tag.getBoolean("ai_step")) return tag;
    }
    return null;
  }

  @Override
  protected List<Component> getEntryDescription(CraftingStatusEntry entry) {
    if (entry.getSerial() == -1001 && entry.getWhat() instanceof AEItemKey item)
      return List.of(
          label(
              "image_collection",
              item.toStack()
                  .getOrDefault(DataComponents.CUSTOM_DATA, CustomData.EMPTY)
                  .copyTag()
                  .getInt("count")),
          label("image_click"));
    if (entry.getSerial() == -1000)
      return List.of(label("question_waiting"), label("question_click"));
    var tag = step(entry);
    if (tag == null) return super.getEntryDescription(entry);
    var font = net.minecraft.client.Minecraft.getInstance().font;
    return List.of(
        Component.literal(font.plainSubstrByWidth(tag.getString("title"), 86)),
        label("step.state." + tag.getString("state")));
  }

  @Override
  protected List<Component> getEntryTooltip(CraftingStatusEntry entry) {
    if (entry.getSerial() == -1001 && entry.getWhat() instanceof AEItemKey item)
      return List.of(
          label(
              "image_collection",
              item.toStack()
                  .getOrDefault(DataComponents.CUSTOM_DATA, CustomData.EMPTY)
                  .copyTag()
                  .getInt("count")),
          label("image_click"));
    if (entry.getSerial() == -1000)
      return List.of(label("question_waiting"), label("question_click"));
    var tag = step(entry);
    if (tag == null) return super.getEntryTooltip(entry);
    var lines = new ArrayList<Component>();
    lines.add(Component.literal(tag.getString("title")));
    lines.add(label("step.state." + tag.getString("state")));
    lines.add(label(tag.getBoolean("planned") ? "step.plan_source" : "step.action_source"));
    if (tag.getBoolean("truncated")) lines.add(label("step.truncated"));
    return lines;
  }
}
