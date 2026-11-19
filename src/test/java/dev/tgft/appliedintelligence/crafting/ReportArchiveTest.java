package dev.tgft.appliedintelligence.crafting;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.*;
import java.util.*;
import net.minecraft.nbt.CompoundTag;
import org.junit.jupiter.api.*;

class ReportArchiveTest {
  @Test
  void fullArchiveWaitsForClaimAndKeepsOldBookImagesAfterReload() {
    var archive = new ReportArchive();
    var spec = new TaskSpec("Report", "Fixture", List.of());
    var record = new JsonObject();
    record.addProperty("state", "completed");
    record.addProperty("result", "Fixture output");
    var image = new JsonObject();
    image.addProperty("id", "fixture-image");
    image.addProperty("name", "image.png");
    var images = new JsonArray();
    images.add(image);
    record.add("images", images);
    String first = UUID.randomUUID().toString();
    for (int i = 0; i < 256; i++) {
      record.addProperty("id", i == 0 ? first : UUID.randomUUID().toString());
      assertTrue(archive.store(spec, record));
    }
    String next = UUID.randomUUID().toString();
    record.addProperty("id", next);
    assertFalse(archive.store(spec, record));
    archive.markClaimed(first);
    assertTrue(archive.store(spec, record));
    assertTrue(archive.containsImage(first, "fixture-image"));
    assertEquals(1, archive.images(first).size());
    assertTrue(archive.store(spec, record));
    var saved = archive.save(new CompoundTag(), null);
    var loaded = ReportArchive.load(saved, null);
    assertTrue(loaded.containsImage(first, "fixture-image"));
    assertTrue(loaded.store(spec, record));
    record.addProperty("id", UUID.randomUUID().toString());
    assertFalse(loaded.store(spec, record));
    loaded.markClaimed(next);
    assertTrue(loaded.store(spec, record));
    record.addProperty("id", UUID.randomUUID() + " /say evil");
    assertThrows(IllegalArgumentException.class, () -> loaded.store(spec, record));
  }
}
