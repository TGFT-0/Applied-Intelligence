package dev.tgft.appliedintelligence.local;

import com.google.gson.*;
import java.awt.image.BufferedImage;
import java.io.*;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import javax.imageio.ImageIO;

/** Bounded, metadata-free PNGs. Public identifiers never contain a filesystem path. */
public final class ImageAssets {
  public static final int MAX_IMAGES = 8, MAX_BYTES = 2 * 1024 * 1024, CHUNK = 24000;
  private final Path root;

  public ImageAssets(Path root) {
    this.root = root.toAbsolutePath().normalize();
  }

  private Path directory(String job) {
    return root.resolve(UUID.fromString(job).toString());
  }

  private Path blob(String job, String id) {
    if (!id.matches("[a-f0-9]{64}")) throw new IllegalArgumentException("invalid image");
    return directory(job).resolve(id + ".png");
  }

  public synchronized JsonArray manifest(String job) throws IOException {
    Path file = directory(job).resolve("images.json");
    if (!Files.exists(file)) return new JsonArray();
    if (Files.size(file) > 16384) throw new IOException("manifest too large");
    var images = JsonParser.parseString(Files.readString(file)).getAsJsonArray();
    if (images.size() > MAX_IMAGES) throw new IOException("too many images");
    return images;
  }

  public JsonObject capture(String job, Path workspace, String path) throws IOException {
    Path base = workspace.toRealPath();
    Path file = base.resolve(path).normalize();
    // Reject traversal and symlinks, including parent symlinks; don't read outside this workspace.
    if (!file.startsWith(base)) throw new IOException("outside workspace");
    Path cursor = file;
    while (cursor != null && cursor.startsWith(base)) {
      if (Files.isSymbolicLink(cursor)) throw new IOException("symlink");
      cursor = cursor.getParent();
    }
    if (!file.toRealPath().startsWith(base)
        || !Files.isRegularFile(file)
        || Files.size(file) > MAX_BYTES) throw new IOException("invalid image file");
    return capture(job, Files.readAllBytes(file), file.getFileName().toString());
  }

  public synchronized JsonObject capture(String job, byte[] bytes, String name) throws IOException {
    if (bytes.length == 0 || bytes.length > MAX_BYTES) throw new IOException("image too large");
    BufferedImage decoded;
    try (var input = ImageIO.createImageInputStream(new ByteArrayInputStream(bytes))) {
      var readers = ImageIO.getImageReaders(input);
      if (!readers.hasNext()) throw new IOException("unsupported image");
      var reader = readers.next();
      try {
        String format = reader.getFormatName();
        if (!format.equalsIgnoreCase("png") && !format.equalsIgnoreCase("jpeg"))
          throw new IOException("unsupported image");
        reader.setInput(input, true, true);
        int w = reader.getWidth(0), h = reader.getHeight(0);
        if (w < 1 || h < 1 || w > 4096 || h > 4096 || (long) w * h > 4_194_304)
          throw new IOException("image dimensions");
        decoded = reader.read(0);
      } finally {
        reader.dispose();
      }
    }
    var clean =
        new BufferedImage(decoded.getWidth(), decoded.getHeight(), BufferedImage.TYPE_INT_ARGB);
    var graphics = clean.createGraphics();
    try {
      graphics.drawImage(decoded, 0, 0, null);
    } finally {
      graphics.dispose();
      decoded.flush();
    }
    var output = new ByteArrayOutputStream();
    ImageIO.write(clean, "png", output);
    byte[] png = output.toByteArray();
    clean.flush();
    if (png.length > MAX_BYTES) throw new IOException("image too large");
    String id = digest(png);
    var images = manifest(job);
    for (var image : images)
      if (image.getAsJsonObject().get("id").getAsString().equals(id))
        return image.getAsJsonObject();
    if (images.size() >= MAX_IMAGES) throw new IOException("too many images");
    var entry = new JsonObject();
    entry.addProperty("id", id);
    String safe = name.replace('\\', '/');
    safe = safe.substring(safe.lastIndexOf('/') + 1).replaceAll("[\\p{Cntrl}]", "");
    entry.addProperty(
        "name", safe.isBlank() ? "image.png" : safe.substring(0, Math.min(80, safe.length())));
    entry.addProperty("width", decoded.getWidth());
    entry.addProperty("height", decoded.getHeight());
    entry.addProperty("bytes", png.length);
    var thumbnail = new BufferedImage(16, 16, BufferedImage.TYPE_INT_ARGB);
    var tg = thumbnail.createGraphics();
    try {
      double scale = Math.min(16.0 / decoded.getWidth(), 16.0 / decoded.getHeight());
      int tw = Math.max(1, (int) (decoded.getWidth() * scale)),
          th = Math.max(1, (int) (decoded.getHeight() * scale));
      tg.drawImage(
          ImageIO.read(new ByteArrayInputStream(png)), (16 - tw) / 2, (16 - th) / 2, tw, th, null);
    } finally {
      tg.dispose();
    }
    var thumbBytes = new ByteArrayOutputStream();
    ImageIO.write(thumbnail, "png", thumbBytes);
    thumbnail.flush();
    entry.addProperty("thumbnail", Base64.getEncoder().encodeToString(thumbBytes.toByteArray()));
    Files.createDirectories(directory(job));
    Files.write(blob(job, id), png);
    images.add(entry);
    Path temp = directory(job).resolve("images.tmp");
    Files.writeString(temp, images.toString());
    try {
      Files.move(
          temp,
          directory(job).resolve("images.json"),
          StandardCopyOption.REPLACE_EXISTING,
          StandardCopyOption.ATOMIC_MOVE);
    } catch (AtomicMoveNotSupportedException e) {
      Files.move(temp, directory(job).resolve("images.json"), StandardCopyOption.REPLACE_EXISTING);
    }
    return entry;
  }

  public synchronized void discard(String job) throws IOException {
    Path directory = directory(job);
    if (!Files.exists(directory)) return;
    try (var files = Files.list(directory)) {
      for (var file : files.toList()) Files.deleteIfExists(file);
    }
    Files.deleteIfExists(directory);
  }

  /** Check before the native decoder allocates a texture-sized buffer. */
  public static int[] pngDimensions(byte[] bytes) throws IOException {
    if (bytes.length < 24
        || bytes.length > MAX_BYTES
        || !Arrays.equals(
            Arrays.copyOf(bytes, 8), new byte[] {(byte) 137, 80, 78, 71, 13, 10, 26, 10})
        || bytes[12] != 'I'
        || bytes[13] != 'H'
        || bytes[14] != 'D'
        || bytes[15] != 'R') throw new IOException("invalid png");
    var header = java.nio.ByteBuffer.wrap(bytes);
    int w = header.getInt(16), h = header.getInt(20);
    if (w < 1 || h < 1 || w > 4096 || h > 4096 || (long) w * h > 4_194_304)
      throw new IOException("image dimensions");
    return new int[] {w, h};
  }

  public static String digest(byte[] bytes) {
    try {
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

  public synchronized JsonObject chunk(JsonObject query) throws IOException {
    String job = query.get("id").getAsString(), id = query.get("image").getAsString();
    int offset = query.get("offset").getAsInt();
    boolean allowed = false;
    for (var entry : manifest(job))
      if (entry.getAsJsonObject().get("id").getAsString().equals(id)) allowed = true;
    if (!allowed) throw new IOException("image not found");
    Path file = blob(job, id);
    long length = Files.size(file);
    if (length < 1 || length > MAX_BYTES || offset < 0 || offset >= length || offset % CHUNK != 0)
      throw new IOException("invalid chunk");
    byte[] bytes;
    try (var input = new RandomAccessFile(file.toFile(), "r")) {
      input.seek(offset);
      bytes = new byte[(int) Math.min(CHUNK, length - offset)];
      input.readFully(bytes);
    }
    var response = new JsonObject();
    response.addProperty("image", id);
    response.addProperty("id", job);
    response.addProperty("offset", offset);
    response.addProperty("total", length);
    response.addProperty("data", Base64.getEncoder().encodeToString(bytes));
    return response;
  }
}
