package dev.tgft.appliedintelligence.client;

import appeng.client.gui.style.Blitter;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.MultiLineEditBox;
import net.minecraft.network.chat.Component;

/** Minecraft's wrapped editor with AE2's resource-pack-aware, pixel-aligned field border. */
final class NativeMultilineField extends MultiLineEditBox {
  private final Blitter texture = Blitter.texture("guis/text_field.png", 128, 128);

  NativeMultilineField(Font font, int x, int y, int width, int height, Component title) {
    super(font, x, y, width, height, Component.empty(), title);
    setCharacterLimit(4000);
  }

  @Override
  protected int innerPadding() {
    return 3;
  }

  @Override
  protected void renderBackground(GuiGraphics g) {
    int sy = !active ? 12 : isFocused() ? 24 : 0;
    int[] sourceX = {0, 1, 127}, sourceY = {sy, sy + 1, sy + 11};
    int[] widths = {1, getWidth() - 2, 1}, heights = {1, getHeight() - 2, 1};
    int y = getY();
    for (int row = 0; row < 3; row++) {
      int x = getX();
      for (int col = 0; col < 3; col++) {
        texture
            .copy()
            .src(sourceX[col], sourceY[row], col == 1 ? 126 : 1, row == 1 ? 10 : 1)
            .dest(x, y, widths[col], heights[row])
            .blit(g);
        x += widths[col];
      }
      y += heights[row];
    }
  }

  @Override
  protected void renderDecorations(GuiGraphics g) {}

  @Override
  protected boolean scrollbarVisible() {
    return false;
  }

  @Override
  public boolean keyPressed(int key, int scan, int modifiers) {
    return active
        && (super.keyPressed(key, scan, modifiers) || isFocused() && key != 256 && key != 258);
  }

  @Override
  public boolean charTyped(char ch, int modifiers) {
    return active && super.charTyped(ch, modifiers);
  }
}
