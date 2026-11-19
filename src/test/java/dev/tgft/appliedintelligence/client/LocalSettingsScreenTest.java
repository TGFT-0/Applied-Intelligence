package dev.tgft.appliedintelligence.client;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class LocalSettingsScreenTest {
  @Test
  void safeDialogDefaultStripsUntrustedShellMetacharactersAndControlBytes() {
    assertEquals("", LocalSettingsScreen.safeDialogDefault(null));
    assertEquals("", LocalSettingsScreen.safeDialogDefault("   "));
    assertEquals("", LocalSettingsScreen.safeDialogDefault("/tmp/$(id)"));
    assertEquals("", LocalSettingsScreen.safeDialogDefault("/tmp/`id`"));
    assertEquals("", LocalSettingsScreen.safeDialogDefault("/tmp/a;rm -rf"));
    assertEquals("", LocalSettingsScreen.safeDialogDefault("/tmp/a'b"));
    assertEquals("", LocalSettingsScreen.safeDialogDefault("/tmp/a\"b"));
    assertEquals("", LocalSettingsScreen.safeDialogDefault("/tmp/a\nb"));
    assertEquals("", LocalSettingsScreen.safeDialogDefault("/" + "a".repeat(1024)));
    assertEquals(
        "/Users/player/workspace",
        LocalSettingsScreen.safeDialogDefault("/Users/player/workspace"));
  }
}
