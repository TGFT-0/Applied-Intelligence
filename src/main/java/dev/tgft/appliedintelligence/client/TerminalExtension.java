package dev.tgft.appliedintelligence.client;

import appeng.client.gui.Icon;
import appeng.client.gui.me.crafting.CraftingStatusScreen;
import appeng.client.gui.me.items.PatternEncodingTermScreen;
import appeng.client.gui.widgets.IconButton;
import appeng.client.gui.widgets.TabButton;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.network.chat.Component;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ScreenEvent;

@EventBusSubscriber(modid = "appliedintelligence", value = Dist.CLIENT)
public final class TerminalExtension {
  @SubscribeEvent
  public static void opening(ScreenEvent.Opening event) {
    // Keep AE2's menu, packets, CPU selection, cancel and suspend handling intact.
    if (event.getNewScreen() != null
        && event.getNewScreen().getClass() == CraftingStatusScreen.class) {
      var screen = (CraftingStatusScreen) event.getNewScreen();
      event.setNewScreen(
          new AgentCraftingStatusScreen(
              screen.getMenu(),
              screen.getMenu().getPlayerInventory(),
              screen.getTitle(),
              screen.getStyle()));
    } else if (event.getNewScreen() != null
        && event.getNewScreen().getClass()
            == appeng.client.gui.me.crafting.CraftConfirmScreen.class) {
      var screen = (appeng.client.gui.me.crafting.CraftConfirmScreen) event.getNewScreen();
      event.setNewScreen(
          new AgentCraftConfirmScreen(
              screen.getMenu(),
              screen.getMenu().getPlayerInventory(),
              screen.getTitle(),
              screen.getStyle()));
    } else if (event.getNewScreen() != null
        && event.getNewScreen().getClass() == PatternEncodingTermScreen.class) {
      var screen = (PatternEncodingTermScreen<?>) event.getNewScreen();
      event.setNewScreen(new EncodingScreen(screen));
    }
  }

  private static final class EncodingScreen
      extends PatternEncodingTermScreen<appeng.menu.me.items.PatternEncodingTermMenu> {
    EncodingScreen(PatternEncodingTermScreen<?> original) {
      super(
          original.getMenu(),
          original.getMenu().getPlayerInventory(),
          original.getTitle(),
          original.getStyle());
    }

    @Override
    public void init() {
      super.init();
      attachEditors(this);
    }

    void registerEditor(TabButton button) {
      addRenderableWidget(button);
    }
  }

  private static void attachEditors(EncodingScreen screen) {
    // Ordinary terminals retain exactly their native controls. Encoding tabs follow the
    // actual vanilla tab positions, including resource-pack and terminal-size changes.
    if (!(screen instanceof PatternEncodingTermScreen<?>)) return;
    var anchor =
        screen.children().stream()
            .filter(child -> child instanceof TabButton)
            .map(child -> (TabButton) child)
            .filter(
                button ->
                    button.getX() > screen.getGuiLeft() + 100
                        && button.getY() > screen.getGuiTop() + 30)
            .max(java.util.Comparator.comparingInt(TabButton::getY))
            .orElse(null);
    if (anchor == null) return;
    int x = anchor.getX(), y = anchor.getY() + 21;
    screen.registerEditor(
        tab(
            Icon.ACCESS_WRITE,
            "skill_editor",
            x,
            y,
            false,
            () -> screen.switchToScreen(new CardEditorScreen(screen, false))));
    screen.registerEditor(
        tab(
            Icon.WHITE_ARROW_DOWN,
            "task_editor",
            x,
            y + 21,
            false,
            () -> screen.switchToScreen(new CardEditorScreen(screen, true))));
  }

  @SubscribeEvent
  public static void bookImageClick(ScreenEvent.MouseButtonPressed.Pre event) {
    if (event.getButton() != 0
        || !(event.getScreen()
            instanceof net.minecraft.client.gui.screens.inventory.BookViewScreen book)) return;
    var style = book.getClickedComponentStyleAt(event.getMouseX(), event.getMouseY());
    var click = style == null ? null : style.getClickEvent();
    if (click == null
        || click.getAction() != net.minecraft.network.chat.ClickEvent.Action.RUN_COMMAND) return;
    var match =
        java.util.regex.Pattern.compile("^/ai-images ([a-f0-9-]{36}) ([0-7])$")
            .matcher(click.getValue());
    if (!match.matches()) return;
    var mc = Minecraft.getInstance();
    if (mc.player == null) return;
    String execution = match.group(1);
    try {
      java.util.UUID.fromString(execution);
    } catch (IllegalArgumentException e) {
      return;
    }
    boolean held =
        java.util.stream.Stream.of(mc.player.getMainHandItem(), mc.player.getOffhandItem())
            .anyMatch(
                stack ->
                    stack.is(net.minecraft.world.item.Items.WRITTEN_BOOK)
                        && execution.equals(
                            stack
                                .getOrDefault(
                                    net.minecraft.core.component.DataComponents.CUSTOM_DATA,
                                    net.minecraft.world.item.component.CustomData.EMPTY)
                                .copyTag()
                                .getString("execution")));
    if (!held) return;
    // Handle our own book link locally; vanilla unsigned book commands bypass client commands.
    event.setCanceled(true);
    ImageViewerScreen.open(book, execution, null, Integer.parseInt(match.group(2)));
  }

  @SubscribeEvent
  public static void tooltip(net.neoforged.neoforge.event.entity.player.ItemTooltipEvent event) {
    if (Minecraft.getInstance().screen instanceof AgentCraftingStatusScreen screen)
      screen.appendAgentTooltip(event.getItemStack(), event.getToolTip());
  }

  static Component label(String key, Object... args) {
    return Component.translatable("gui.appliedintelligence." + key, args);
  }

  static Component jobState(String state) {
    return switch (state) {
      case "queued", "running", "completed", "failed", "interrupted", "cancelled" ->
          label("state." + state);
      default -> label("state.unknown");
    };
  }

  static Component jobEvent(String event) {
    return switch (event) {
      case "local.started",
          "bridge.started",
          "thread.started",
          "turn.started",
          "item.started",
          "item.completed",
          "turn.completed",
          "error",
          "turn.failed" ->
          label("event." + event);
      default -> label("event.update");
    };
  }

  static TabButton tab(Icon icon, String key, int x, int y, boolean selected, Runnable action) {
    var button = new TabButton(icon, label(key), b -> action.run());
    button.setStyle(TabButton.Style.HORIZONTAL);
    button.setSelected(selected);
    button.setPosition(x, y);
    // AE2 owns the tooltip. Never also attach Minecraft's Tooltip to an ITooltip in an AE2 screen.
    return button;
  }

  static IconButton iconButton(Icon icon, String key, Runnable action) {
    return iconButton(icon, key, action, false);
  }

  static IconButton iconButton(Icon icon, String key, Runnable action, boolean ae2Screen) {
    var button =
        new IconButton(b -> action.run()) {
          @Override
          protected Icon getIcon() {
            return icon;
          }
        };
    button.setMessage(label(key));
    if (!ae2Screen) button.setTooltip(Tooltip.create(label(key)));
    return button;
  }
}
