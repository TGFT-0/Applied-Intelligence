package dev.tgft.appliedintelligence.crafting;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class ReportPagesTest {
  @Test
  void longUnicodeReportsKeepEveryCodePointAndFitBookLines() {
    String text = "中英文报告🙂ABC ".repeat(1600);
    var pages = ReportPages.split(text);
    assertTrue(pages.size() > 1);
    assertEquals(text, String.join("", pages).replace("\n", ""));
    for (var page : pages) {
      assertTrue(page.split("\n", -1).length <= 12);
      for (var line : page.split("\n", -1))
        assertTrue(line.codePoints().map(cp -> cp >= 0x2e80 ? 2 : 1).sum() <= 18);
      assertFalse(!page.isEmpty() && Character.isHighSurrogate(page.charAt(page.length() - 1)));
    }
  }

  @Test
  void emptyAndBlankLinesAreReadable() {
    assertEquals(java.util.List.of(""), ReportPages.split(""));
    assertEquals("a\n\nb", ReportPages.split("a\r\n\nb").getFirst());
  }
}
