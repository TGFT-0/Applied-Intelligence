package dev.tgft.appliedintelligence.local;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.*;
import java.awt.image.BufferedImage;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

class ImageAssetsTest {
  @TempDir Path root;
  final String job = UUID.randomUUID().toString();

  static byte[] png(int width, int height, int color) throws IOException {
    var image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
    var g = image.createGraphics();
    g.setColor(new java.awt.Color(color));
    g.fillRect(0, 0, width, height);
    g.dispose();
    var bytes = new ByteArrayOutputStream();
    ImageIO.write(image, "png", bytes);
    return bytes.toByteArray();
  }

  @Test
  void transfersMultipleChunksAndAcceptsJpeg() throws Exception {
    var random = new Random(42);
    var image = new BufferedImage(320, 180, BufferedImage.TYPE_INT_RGB);
    for (int y = 0; y < 180; y++)
      for (int x = 0; x < 320; x++) image.setRGB(x, y, random.nextInt());
    var jpeg = new ByteArrayOutputStream();
    ImageIO.write(image, "jpeg", jpeg);
    var store = new ImageAssets(root);
    var entry = store.capture(job, jpeg.toByteArray(), "photo.jpg");
    assertTrue(entry.get("bytes").getAsInt() > ImageAssets.CHUNK);
    var received = new ByteArrayOutputStream();
    while (received.size() < entry.get("bytes").getAsInt()) {
      var query = new JsonObject();
      query.addProperty("id", job);
      query.addProperty("image", entry.get("id").getAsString());
      query.addProperty("offset", received.size());
      var chunk = store.chunk(query);
      assertTrue(chunk.toString().length() < 60000);
      assertEquals(received.size(), chunk.get("offset").getAsInt());
      received.writeBytes(Base64.getDecoder().decode(chunk.get("data").getAsString()));
    }
    assertEquals(entry.get("id").getAsString(), ImageAssets.digest(received.toByteArray()));
  }

  @Test
  void persistsDeduplicatesAndTransfersOnlyRegisteredContent() throws Exception {
    var store = new ImageAssets(root.resolve("store"));
    var entry = store.capture(job, png(64, 32, 0x334455), "/private/folder/终端.png");
    assertEquals("终端.png", entry.get("name").getAsString());
    assertEquals(entry, store.capture(job, png(64, 32, 0x334455), "different.png"));
    assertEquals(1, new ImageAssets(root.resolve("store")).manifest(job).size());
    var query = new JsonObject();
    query.addProperty("id", job);
    query.addProperty("image", entry.get("id").getAsString());
    query.addProperty("offset", 0);
    byte[] result = Base64.getDecoder().decode(store.chunk(query).get("data").getAsString());
    assertEquals(entry.get("id").getAsString(), ImageAssets.digest(result));
    var copied = new ImageAssets(root.resolve("world"));
    assertEquals(entry.get("id"), copied.capture(job, result, "终端.png").get("id"));
    query.addProperty("offset", 1);
    assertThrows(IOException.class, () -> store.chunk(query));
    query.addProperty("offset", 0);
    query.addProperty("image", "a".repeat(64));
    assertThrows(IOException.class, () -> store.chunk(query));
    query.addProperty("image", "../../private");
    assertThrows(IOException.class, () -> store.chunk(query));
  }

  @Test
  void rollingHistoryCleanupDoesNotAffectAnotherArchive() throws Exception {
    var host = new ImageAssets(root.resolve("host"));
    var world = new ImageAssets(root.resolve("world"));
    var entry = host.capture(job, png(16, 16, 0x123456), "image.png");
    world.capture(job, png(16, 16, 0x123456), "image.png");
    host.discard(job);
    assertTrue(host.manifest(job).isEmpty());
    assertEquals(1, world.manifest(job).size());
  }

  @Test
  void rejectsNativeDecoderAllocationBombBeforeDecoding() throws Exception {
    byte[] bytes = png(8, 8, 0);
    assertArrayEquals(new int[] {8, 8}, ImageAssets.pngDimensions(bytes));
    java.nio.ByteBuffer.wrap(bytes).putInt(16, Integer.MAX_VALUE);
    assertThrows(IOException.class, () -> ImageAssets.pngDimensions(bytes));
  }

  @Test
  void rejectsCorruptOversizedAndPixelBombs() throws Exception {
    var store = new ImageAssets(root);
    assertThrows(IOException.class, () -> store.capture(job, new byte[16], "broken.png"));
    assertThrows(
        IOException.class,
        () -> store.capture(job, new byte[ImageAssets.MAX_BYTES + 1], "big.png"));
    assertThrows(IOException.class, () -> store.capture(job, png(4096, 1025, 0), "pixels.png"));
    assertTrue(store.manifest(job).isEmpty());
  }

  @Test
  void confinesFilesToWorkspaceWithoutFollowingSymlinks() throws Exception {
    var store = new ImageAssets(root.resolve("store"));
    Path workspace = Files.createDirectory(root.resolve("workspace"));
    Path secret = root.resolve("private.png");
    Files.write(secret, png(8, 8, 0));
    assertThrows(IOException.class, () -> store.capture(job, workspace, "../private.png"));
    Files.createSymbolicLink(workspace.resolve("link.png"), secret);
    assertThrows(IOException.class, () -> store.capture(job, workspace, "link.png"));
    Files.createSymbolicLink(workspace.resolve("parent"), root);
    assertThrows(IOException.class, () -> store.capture(job, workspace, "parent/private.png"));
    Files.write(workspace.resolve("图 片.png"), png(8, 8, 1));
    assertEquals("图 片.png", store.capture(job, workspace, "图 片.png").get("name").getAsString());
  }

  @Test
  void boundsCollectionAndRejectsUnknownJobPaths() throws Exception {
    var store = new ImageAssets(root);
    for (int i = 0; i < ImageAssets.MAX_IMAGES; i++) store.capture(job, png(8, 8, i), "image.png");
    assertThrows(IOException.class, () -> store.capture(job, png(8, 8, 900), "ninth.png"));
    assertEquals(8, store.manifest(job).size());
    assertThrows(IllegalArgumentException.class, () -> store.manifest("../other"));
  }
}
