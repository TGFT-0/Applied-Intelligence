package dev.tgft.appliedintelligence.client;

import static dev.tgft.appliedintelligence.client.TerminalExtension.label;

import appeng.client.Point;
import appeng.client.gui.ICompositeWidget;
import appeng.client.gui.Tooltip;
import appeng.client.gui.style.PaletteColor;
import appeng.client.gui.style.ScreenStyle;
import com.google.gson.*;
import dev.tgft.appliedintelligence.local.JobProgress;
import java.util.*;
import java.util.function.*;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.Rect2i;
import net.minecraft.network.chat.Component;

/** A native footer readout, with one native tooltip and no button or auxiliary small print. */
final class TokenUsageWidget implements ICompositeWidget {
  private static final Gson JSON = new Gson();
  private final Supplier<JsonObject> job;
  private final BooleanSupplier visible, previous;
  private final int color;
  private Rect2i bounds = new Rect2i(0, 0, 0, 0);

  TokenUsageWidget(
      ScreenStyle style,
      Supplier<JsonObject> job,
      BooleanSupplier visible,
      BooleanSupplier previous) {
    this.job = job;
    this.visible = visible;
    this.previous = previous;
    color = style.getColor(PaletteColor.DEFAULT_TEXT_COLOR).toARGB();
  }

  public void setPosition(Point point) {
    bounds = new Rect2i(point.getX(), point.getY(), bounds.getWidth(), bounds.getHeight());
  }

  public void setSize(int width, int height) {
    bounds = new Rect2i(bounds.getX(), bounds.getY(), width, height);
  }

  public Rect2i getBounds() {
    return bounds;
  }

  public boolean isVisible() {
    return visible.getAsBoolean();
  }

  private JobProgress.Usage usage() {
    var value = job.get();
    if (value == null) return null;
    if (value.has("progress")) value = value.getAsJsonObject("progress");
    try {
      return value.has("usage") ? JSON.fromJson(value.get("usage"), JobProgress.Usage.class) : null;
    } catch (RuntimeException e) {
      return null;
    }
  }

  private static String exact(Long count) {
    return count == null ? "—" : String.format(Locale.ROOT, "%,d", count);
  }

  private static String compact(long count) {
    if (count < 1000) return Long.toString(count);
    String suffix = count >= 1_000_000_000L ? "B" : count >= 1_000_000 ? "M" : "K";
    double divisor =
        count >= 1_000_000_000L ? 1_000_000_000.0 : count >= 1_000_000 ? 1_000_000.0 : 1000.0;
    return String.format(Locale.ROOT, "%.1f%s", count / divisor, suffix);
  }

  @Override
  public void drawForegroundLayer(GuiGraphics g, Rect2i screenBounds, Point mouse) {
    var font = Minecraft.getInstance().font;
    var usage = usage();
    int x = screenBounds.getX() + bounds.getX(), y = screenBounds.getY() + bounds.getY();
    g.drawString(
        font,
        label(previous.getAsBoolean() ? "token_last_label" : "token_label"),
        x,
        y + 1,
        color,
        false);
    String total = usage == null ? "—" : compact(usage.total());
    g.drawString(font, font.plainSubstrByWidth(total, bounds.getWidth()), x, y + 11, color, false);
  }

  @Override
  public Tooltip getTooltip(int x, int y) {
    var usage = usage();
    var lines = new ArrayList<Component>();
    lines.add(label(previous.getAsBoolean() ? "token_last_title" : "token_title"));
    lines.add(label("token_input", exact(usage == null ? null : usage.inputTokens())));
    lines.add(label("token_cached", exact(usage == null ? null : usage.cachedInputTokens())));
    lines.add(label("token_output", exact(usage == null ? null : usage.outputTokens())));
    lines.add(
        label("token_total", exact(usage == null ? null : usage.total()))
            .copy()
            .withStyle(ChatFormatting.WHITE));
    return new Tooltip(lines);
  }
}
