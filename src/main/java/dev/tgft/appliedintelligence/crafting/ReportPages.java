package dev.tgft.appliedintelligence.crafting;

import java.util.*;

/** Conservative book layout without loading client fonts on the server. */
public final class ReportPages {
  private ReportPages() {}

  public static List<String> split(String text) {
    List<String> pages = new ArrayList<>();
    StringBuilder page = new StringBuilder();
    int lineWidth = 0, lines = 1;
    for (int offset = 0; offset < text.length(); ) {
      int cp = text.codePointAt(offset);
      offset += Character.charCount(cp);
      if (cp == '\r') continue;
      int width = cp >= 0x2e80 ? 2 : 1;
      if (cp == '\n' || lineWidth + width > 18) {
        if (lines >= 12) {
          pages.add(page.toString());
          page.setLength(0);
          lines = 1;
        } else {
          page.append('\n');
          lines++;
        }
        lineWidth = 0;
        if (cp == '\n') continue;
      }
      page.appendCodePoint(cp);
      lineWidth += width;
    }
    if (!page.isEmpty() || pages.isEmpty()) pages.add(page.toString());
    return List.copyOf(pages);
  }
}
