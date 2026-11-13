package dev.tgft.appliedintelligence.client;

import static dev.tgft.appliedintelligence.client.TerminalExtension.*;

import appeng.client.gui.me.crafting.CraftConfirmScreen;
import appeng.client.gui.style.ScreenStyle;
import appeng.menu.me.crafting.CraftConfirmMenu;
import com.google.gson.*;
import dev.tgft.appliedintelligence.Protocol;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;

/** Native quantity/plan/CPU selection flow, with context capacity for Agent products. */
final class AgentCraftConfirmScreen extends CraftConfirmScreen {
  private int contextTokens;
  private long nextPoll, pollSent;
  private boolean polling;

  AgentCraftConfirmScreen(
      CraftConfirmMenu menu, Inventory inventory, Component title, ScreenStyle style) {
    super(
        menu,
        inventory,
        title,
        UiStyles.COPY.fromJson(UiStyles.COPY.toJsonTree(style), ScreenStyle.class));
  }

  @Override
  protected void init() {
    super.init();
    polling = false;
    nextPoll = System.currentTimeMillis() + 250;
    Protocol.ClientReceiver.receiver =
        raw -> {
          try {
            var response = JsonParser.parseString(raw).getAsJsonObject();
            polling = false;
            if (response.has("local"))
              contextTokens = response.getAsJsonObject("local").get("context_tokens").getAsInt();
          } catch (RuntimeException ignored) {
          }
        };
  }

  @Override
  public void containerTick() {
    super.containerTick();
    if (System.currentTimeMillis() >= nextPoll
        && (!polling || System.currentTimeMillis() - pollSent > 30000)) {
      nextPoll = System.currentTimeMillis() + 2000;
      polling = true;
      pollSent = System.currentTimeMillis();
      Protocol.send("local_settings", new JsonObject());
    }
  }

  @Override
  protected void updateBeforeRender() {
    super.updateBeforeRender();
    var plan = menu.getPlan();
    if (plan != null
        && !plan.isSimulation()
        && !menu.hasNoCPU()
        && plan.getEntries().stream().anyMatch(e -> !AgentDisplay.recipe(e.getWhat()).isBlank())) {
      var pos =
          style
              .getWidget("selectCpu")
              .resolve(
                  new net.minecraft.client.renderer.Rect2i(
                      leftPos, topPos, imageWidth, imageHeight));
      for (var child : children())
        if (child instanceof net.minecraft.client.gui.components.Button button
            && button.getX() == pos.getX()
            && button.getY() == pos.getY())
          button.setMessage(
              button.getMessage().copy().append(" · CTX " + AgentDisplay.capacity(contextTokens)));
      setTextContent(
          "cpu_status",
          contextTokens > 0
              ? label(
                  "default_context_capacity",
                  String.format(java.util.Locale.ROOT, "%,d", contextTokens))
              : label("default_context_unknown"));
    }
  }

  @Override
  public void removed() {
    Protocol.ClientReceiver.receiver = ignored -> {};
    super.removed();
  }
}
