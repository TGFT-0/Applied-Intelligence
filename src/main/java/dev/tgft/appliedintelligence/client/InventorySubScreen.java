package dev.tgft.appliedintelligence.client;

import static dev.tgft.appliedintelligence.client.TerminalExtension.*;

import appeng.client.gui.AEBaseScreen;
import appeng.client.gui.Icon;
import appeng.client.gui.me.items.PatternEncodingTermScreen;
import appeng.client.gui.style.ScreenStyle;
import appeng.menu.SlotSemantics;
import appeng.menu.me.common.MEStorageMenu;
import com.google.gson.*;
import dev.tgft.appliedintelligence.Protocol;
import java.util.List;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.world.inventory.Slot;

/**
 * Real, synchronized player inventory slots, not duplicate card widgets or a virtual Skill store.
 */
abstract class InventorySubScreen extends AEBaseScreen<MEStorageMenu> {
  static final int WIDTH = 176, HEIGHT = 238, INK = 0xff413f54, MUTED = 0xff878fa5;
  final AEBaseScreen<? extends MEStorageMenu> parent;
  Component status = label("physical_cards_hint");

  InventorySubScreen(AEBaseScreen<? extends MEStorageMenu> parent, String title) {
    super(
        parent.getMenu(),
        parent.getMenu().getPlayerInventory(),
        label(title),
        inventoryStyle(parent, title));
    this.parent = parent;
  }

  private static ScreenStyle inventoryStyle(AEBaseScreen<?> parent, String title) {
    JsonObject json = UiStyles.COPY.toJsonTree(parent.getStyle()).getAsJsonObject();
    json.remove("background");
    json.remove("terminalStyle");
    json.remove("helpTopic");
    JsonObject generated = new JsonObject();
    generated.addProperty("width", WIDTH);
    generated.addProperty("height", HEIGHT);
    json.add("generatedBackground", generated);
    JsonObject slots = new JsonObject();
    JsonObject inventory = new JsonObject();
    inventory.addProperty("left", 8);
    inventory.addProperty("top", 156);
    inventory.addProperty("grid", "BREAK_AFTER_9COLS");
    JsonObject hotbar = new JsonObject();
    hotbar.addProperty("left", 8);
    hotbar.addProperty("top", 214);
    hotbar.addProperty("grid", "HORIZONTAL");
    slots.add("PLAYER_INVENTORY", inventory);
    slots.add("PLAYER_HOTBAR", hotbar);
    if (title.equals("skill_editor") || title.equals("task_editor")) {
      JsonObject blank = new JsonObject();
      blank.addProperty("left", 146);
      blank.addProperty("top", 88);
      slots.add("BLANK_PATTERN", blank);
    }
    json.add("slots", slots);
    json.add("text", new JsonObject());
    JsonObject widgets = new JsonObject(), toolbar = new JsonObject();
    toolbar.addProperty("left", 0);
    toolbar.addProperty("top", 4);
    widgets.add("verticalToolbar", toolbar);
    json.add("widgets", widgets);
    return UiStyles.COPY.fromJson(json, ScreenStyle.class);
  }

  @Override
  protected void init() {
    super.init();
    addRenderableWidget(
        tab(Icon.BACK, "back", leftPos + WIDTH - 2, topPos + 1, false, this::onClose));
  }

  final List<Slot> inventorySlots() {
    var all = new java.util.ArrayList<Slot>(menu.getSlots(SlotSemantics.PLAYER_INVENTORY));
    all.addAll(menu.getSlots(SlotSemantics.PLAYER_HOTBAR));
    return all;
  }

  final void sidebar(boolean skillSelected, boolean taskSelected, boolean submitSelected) {
    if (parent instanceof PatternEncodingTermScreen<?>) {
      addRenderableWidget(
          tab(
              Icon.ACCESS_WRITE,
              "skill_editor",
              leftPos + WIDTH - 2,
              topPos + 23,
              skillSelected,
              () -> switchToScreen(new CardEditorScreen(parent, false))));
      addRenderableWidget(
          tab(
              Icon.WHITE_ARROW_DOWN,
              "task_editor",
              leftPos + WIDTH - 2,
              topPos + 44,
              taskSelected,
              () -> switchToScreen(new CardEditorScreen(parent, true))));
    }
    addRenderableWidget(
        tab(
            Icon.COG,
            "local_settings",
            leftPos + WIDTH - 2,
            topPos + 65,
            false,
            () -> {
              Protocol.ClientReceiver.receiver = ignored -> {};
              minecraft.screen = null;
              minecraft.setScreen(new LocalSettingsScreen(this));
            }));
  }

  @Override
  public void drawBG(GuiGraphics g, int x, int y, int mx, int my, float delta) {
    super.drawBG(g, x, y, mx, my, delta);
    for (Slot slot : inventorySlots()) {
      Icon.SLOT_BACKGROUND.getBlitter().dest(x + slot.x - 1, y + slot.y - 1).blit(g);
      if (selected(slot))
        g.fill(x + slot.x, y + slot.y, x + slot.x + 16, y + slot.y + 16, 0xfface9ff);
    }
  }

  boolean selected(Slot slot) {
    return false;
  }

  @Override
  public void drawFG(GuiGraphics g, int x, int y, int mx, int my) {
    g.drawString(font, title, 8, 6, INK, false);
    g.drawString(font, label("physical_inventory"), 8, 144, INK, false);
    g.drawString(font, font.plainSubstrByWidth(status.getString(), 158), 8, 132, MUTED, false);
  }

  @Override
  public void render(GuiGraphics g, int mx, int my, float delta) {
    super.render(g, mx, my, delta);
    if (mx >= leftPos + 8 && mx < leftPos + 168 && my >= topPos + 129 && my < topPos + 142)
      drawTooltip(g, mx, my, List.of(status));
  }

  @Override
  public void onClose() {
    Protocol.ClientReceiver.receiver = ignored -> {};
    if (minecraft.player != null && minecraft.player.containerMenu == menu) switchToScreen(parent);
    else minecraft.setScreen(null);
  }

  @Override
  public void removed() {
    Protocol.ClientReceiver.receiver = ignored -> {};
    super.removed();
  }
}
