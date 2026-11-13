package dev.tgft.appliedintelligence.client;

import static dev.tgft.appliedintelligence.client.TerminalExtension.*;

import appeng.client.gui.AEBaseScreen;
import appeng.client.gui.Icon;
import appeng.menu.me.common.MEStorageMenu;
import com.google.gson.*;
import dev.tgft.appliedintelligence.AppliedIntelligence;
import dev.tgft.appliedintelligence.Protocol;
import java.util.LinkedHashSet;
import java.util.Set;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.component.CustomData;

final class CardEditorScreen extends InventorySubScreen {
  private final boolean task;
  private NativeTextField name, body;
  private String nameDraft = "", bodyDraft = "";
  private int target = -1, savedSnapshotCount;
  private boolean skillsChanged;
  private final Set<Integer> skills = new LinkedHashSet<>();

  CardEditorScreen(AEBaseScreen<? extends MEStorageMenu> parent, boolean task) {
    super(parent, task ? "task_editor" : "skill_editor");
    this.task = task;
  }

  @Override
  protected void init() {
    super.init();
    sidebar(!task, task, false);
    Protocol.ClientReceiver.receiver = this::receive;
    name = new NativeTextField(font, leftPos + 10, topPos + 32, 154, label("card_name"));
    name.setMaxLength(64);
    name.setValue(nameDraft);
    name.setResponder(value -> nameDraft = value);
    addRenderableWidget(name);
    body =
        new NativeTextField(
            font, leftPos + 10, topPos + 62, 154, label(task ? "task_body" : "skill_body"));
    body.setMaxLength(8000);
    body.setValue(bodyDraft);
    body.setResponder(value -> bodyDraft = value);
    addRenderableWidget(body);
    // Reuse the actual blank-pattern input slot from AE2's encoding menu.
    var encode = iconButton(Icon.WHITE_ARROW_DOWN, "encode_named_card", () -> save(false), true);
    encode.setPosition(leftPos + 146, topPos + 110);
    addRenderableWidget(encode);
    var clear =
        iconButton(
            Icon.CLEAR,
            "new_card",
            () -> {
              target = -1;
              skills.clear();
              skillsChanged = false;
              savedSnapshotCount = 0;
              name.setValue("");
              body.setValue("");
              status = label("card_editor_hint");
            },
            true);
    clear.setPosition(leftPos + 123, topPos + 110);
    addRenderableWidget(clear);
    if (!task) {
      var copy = iconButton(Icon.COPY_MODE_ON, "copy_skill", () -> save(true), true);
      copy.setPosition(leftPos + 102, topPos + 110);
      addRenderableWidget(copy);
    }
    status = label("card_editor_hint");
  }

  private void save(boolean copy) {
    if (name.getValue().isBlank()) {
      status = label("card_name_required");
      return;
    }
    if (copy && target < 0) {
      status = label("select_skill");
      return;
    }
    JsonObject data = new JsonObject();
    data.addProperty("name", name.getValue());
    data.addProperty("text", body.getValue());
    if (target >= 0) data.addProperty("slot", target);
    JsonArray selected = new JsonArray();
    for (int slot : skills) selected.add(slot);
    data.add("slots", selected);
    if (task && target >= 0 && !skillsChanged) data.addProperty("keep_skills", true);
    Protocol.send(task ? "encode" : copy ? "copy" : "skill", data);
  }

  @Override
  protected void slotClicked(Slot slot, int slotId, int button, ClickType type) {
    if (slot != null
        && button == 0
        && type == ClickType.PICKUP
        && menu.getCarried().isEmpty()
        && slot.container == menu.getPlayerInventory()) {
      var stack = slot.getItem();
      if (stack.is(task ? AppliedIntelligence.TASK.get() : AppliedIntelligence.SKILL.get())) {
        target = slot.getSlotIndex();
        var tag = stack.getOrDefault(DataComponents.CUSTOM_DATA, CustomData.EMPTY).copyTag();
        name.setValue(tag.getString("name"));
        body.setValue(tag.getString(task ? "prompt" : "skill"));
        if (task) {
          skills.clear();
          skillsChanged = false;
          try {
            savedSnapshotCount =
                JsonParser.parseString(tag.getString("skills")).getAsJsonArray().size();
          } catch (RuntimeException ignored) {
            savedSnapshotCount = 0;
          }
        }
        status = label("editing_physical_card", stack.getHoverName());
        return;
      }
      if (task && stack.is(AppliedIntelligence.SKILL.get())) {
        int index = slot.getSlotIndex();
        if (!skills.remove(index)) {
          if (skills.size() >= 9) {
            status = label("too_many_skills");
            return;
          }
          skills.add(index);
        }
        skillsChanged = true;
        status = label("physical_skills_selected", skills.size());
        return;
      }
    }
    super.slotClicked(slot, slotId, button, type);
  }

  @Override
  boolean selected(Slot slot) {
    return slot.getSlotIndex() == target || skills.contains(slot.getSlotIndex());
  }

  private void receive(String raw) {
    try {
      JsonObject value = JsonParser.parseString(raw).getAsJsonObject();
      if (value.has("error_key")) status = label(value.get("error_key").getAsString());
      if (value.has("message_key")) status = label("saved_physical_card");
    } catch (RuntimeException e) {
      status = label("invalid_server_response");
    }
  }

  @Override
  public void drawBG(GuiGraphics g, int x, int y, int mx, int my, float delta) {
    super.drawBG(g, x, y, mx, my, delta);
    Icon.SLOT_BACKGROUND.getBlitter().dest(x + 145, y + 87).blit(g);
  }

  @Override
  public void drawFG(GuiGraphics g, int x, int y, int mx, int my) {
    super.drawFG(g, x, y, mx, my);
    g.drawString(font, label("card_name"), 8, 19, INK, false);
    g.drawString(font, label(task ? "task_body" : "skill_body"), 8, 49, INK, false);

    var hint =
        task
            ? label(
                target >= 0 && !skillsChanged ? "preserved_snapshots" : "physical_skills_selected",
                target >= 0 && !skillsChanged ? savedSnapshotCount : skills.size())
            : label("select_physical_skill");
    var lines = font.split(hint, 118);
    for (int i = 0; i < Math.min(2, lines.size()); i++)
      g.drawString(font, lines.get(i), 8, 83 + i * 10, INK, false);
  }
}
