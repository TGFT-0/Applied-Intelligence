package dev.tgft.appliedintelligence.client;

import appeng.client.gui.style.Blitter;
import appeng.client.gui.style.Color;
import appeng.client.gui.style.ScreenStyle;
import com.google.gson.Gson;
import net.minecraft.client.renderer.Rect2i;

/** Deep-copy live AE2 styles without mutating StyleManager's resource-pack cache. */
final class UiStyles {
  private static final Gson RAW = new Gson();
  // AE2's JSON adapters for these leaf types are deserialize-only resource-schema
  // adapters. A live object snapshot must use matching reflective read/write adapters.
  static final Gson COPY =
      ScreenStyle.GSON
          .newBuilder()
          .registerTypeAdapter(Blitter.class, RAW.getAdapter(Blitter.class))
          .registerTypeAdapter(Color.class, RAW.getAdapter(Color.class))
          .registerTypeAdapter(Rect2i.class, RAW.getAdapter(Rect2i.class))
          .create();
}
