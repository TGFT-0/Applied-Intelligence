package dev.tgft.appliedintelligence.client;

import appeng.client.gui.style.Blitter;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.network.chat.Component;

/** Resizable text field using AE2's resource-pack-aware text field texture. */
final class NativeTextField extends EditBox {
  private boolean writable = true;
  private final Blitter texture = Blitter.texture("guis/text_field.png", 128, 128);

  NativeTextField(Font font, int x, int y, int width, Component title) {
    super(font, x, y, width, 10, title);
    setBordered(false);
    setTextColor(0xfff2f2f2);
  }

  @Override
  public void setEditable(boolean editable) {
    super.setEditable(editable);
    writable = editable;
  }

  @Override
  public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
    return super.keyPressed(keyCode, scanCode, modifiers)
        || (isFocused() && canConsumeInput() && keyCode != 256 && keyCode != 258);
  }

  @Override
  public void renderWidget(GuiGraphics g, int mx, int my, float delta) {
    int sy = !writable ? 12 : isFocused() ? 24 : 0;
    texture.copy().src(0, sy, 1, 12).dest(getX() - 2, getY() - 2).blit(g);
    texture.copy().src(1, sy, 126, 12).dest(getX() - 1, getY() - 2, getWidth() + 4, 12).blit(g);
    texture.copy().src(127, sy, 1, 12).dest(getX() + getWidth() + 3, getY() - 2).blit(g);
    super.renderWidget(g, mx, my, delta);
  }
}
