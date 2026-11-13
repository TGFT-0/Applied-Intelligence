package dev.tgft.appliedintelligence.client;

import appeng.client.Point;
import appeng.client.gui.style.ScreenStyle;
import appeng.client.gui.widgets.*;
import appeng.menu.me.crafting.CraftingStatusMenu;
import com.google.gson.*;
import java.util.function.Supplier;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.Rect2i;
import net.minecraft.network.chat.Component;

/** Same native CPU selectors and hit targets; Agent rows show context and real CPU elapsed time. */
final class ContextCpuList extends CPUSelectionList {
  private final CraftingStatusMenu menu;
  private final int textColor;
  private final Scrollbar scroll;
  private final ScreenStyle style;
  private final Supplier<JsonArray> jobs;

  ContextCpuList(
      CraftingStatusMenu menu, Scrollbar scroll, ScreenStyle style, Supplier<JsonArray> jobs) {
    super(menu, scroll, style);
    this.menu = menu;
    this.textColor =
        style.getColor(appeng.client.gui.style.PaletteColor.DEFAULT_TEXT_COLOR).toARGB();
    this.scroll = scroll;
    this.style = style;
    this.jobs = jobs;
  }

  @Override
  public appeng.client.gui.Tooltip getTooltip(int mx, int my) {
    var base = super.getTooltip(mx, my);
    if (base == null) return null;
    int relative = my - getBounds().getY() - 19;
    int index = scroll.getCurrentScroll() + relative / 23;
    if (relative < 0 || index >= menu.cpuList.cpus().size()) return base;
    var cpu = menu.cpuList.cpus().get(index);
    if (cpu.currentJob() == null || AgentDisplay.recipe(cpu.currentJob().what()).isBlank())
      return base;
    var job = AgentDisplay.find(jobs.get(), AgentDisplay.recipe(cpu.currentJob().what()));
    int tokens =
        job != null && job.has("context_window") ? job.get("context_window").getAsInt() : 0;
    var lines = new java.util.ArrayList<>(base.getContent());
    lines.add(
        1,
        tokens > 0
            ? TerminalExtension.label(
                "context_capacity", String.format(java.util.Locale.ROOT, "%,d", tokens))
            : TerminalExtension.label("context_unknown"));
    return new appeng.client.gui.Tooltip(lines);
  }

  @Override
  public void drawBackgroundLayer(GuiGraphics g, Rect2i bounds, Point mouse) {
    style
        .getImage("cpuList")
        .copy()
        .dest(
            bounds.getX() + getBounds().getX(),
            bounds.getY() + getBounds().getY(),
            getBounds().getWidth(),
            getBounds().getHeight())
        .blit(g);
    int first = scroll.getCurrentScroll();
    var cpus = menu.cpuList.cpus();
    int x = bounds.getX() + getBounds().getX() + 8, y = bounds.getY() + getBounds().getY() + 19;
    var font = Minecraft.getInstance().font;
    for (int i = first; i < Math.min(cpus.size(), first + 6); i++, y += 23) {
      var cpu = cpus.get(i);
      if (cpu.currentJob() == null || AgentDisplay.recipe(cpu.currentJob().what()).isBlank()) {
        // Keep native rendering for ordinary CPUs without drawing Agent item icons underneath.
        g.enableScissor(x, y, x + 68, y + 22);
        super.drawBackgroundLayer(g, bounds, mouse);
        g.disableScissor();
        continue;
      }
      var job = AgentDisplay.find(jobs.get(), AgentDisplay.recipe(cpu.currentJob().what()));
      int tokens =
          job != null && job.has("context_window") ? job.get("context_window").getAsInt() : 0;
      style
          .getImage(
              cpu.serial() == menu.getSelectedCpuSerial()
                  ? "cpuListButtonSelected"
                  : "cpuListButton")
          .copy()
          .dest(x, y)
          .blit(g);
      Component name =
          cpu.name() != null
              ? cpu.name()
              : appeng.core.localization.GuiText.CPUs.text().append(" #" + cpu.serial());
      if (job != null && job.has("waiting_input") && job.get("waiting_input").getAsBoolean())
        name = TerminalExtension.label("question_waiting").copy().append(" #" + cpu.serial());
      g.pose().pushPose();
      g.pose().translate(x + 3, y + 2, 0);
      g.pose().scale(.666f, .666f, 1);
      g.drawString(font, font.plainSubstrByWidth(name.getString(), 90), 0, 0, textColor, false);
      g.pose().popPose();
      String context = "CTX " + AgentDisplay.capacity(tokens),
          time = AgentDisplay.time(cpu.elapsedTimeNanos() / 1_000_000_000L);
      g.pose().pushPose();
      g.pose().translate(x + 2, y + 12, 0);
      g.pose().scale(.666f, .666f, 1);
      g.drawString(font, context, 0, 0, textColor, false);
      g.drawString(font, time, 94 - font.width(time), 0, textColor, false);
      g.pose().popPose();
      int progress = (int) (Math.max(0, Math.min(1, cpu.progress())) * 65);
      g.fill(x + 1, y + 20, x + 1 + progress, y + 21, 0xff7da9d2);
    }
  }
}
