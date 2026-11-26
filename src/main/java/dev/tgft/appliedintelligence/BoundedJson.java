package dev.tgft.appliedintelligence;

import com.google.gson.*;
import com.google.gson.stream.*;
import java.io.IOException;
import java.io.StringReader;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;

public final class BoundedJson {
  private BoundedJson() {}

  static final class SizeLimit extends IllegalArgumentException {}

  public static JsonObject object(String raw, int maxBytes, int maxDepth) {
    return parse(raw, maxBytes, maxDepth).getAsJsonObject();
  }

  public static JsonArray array(String raw, int maxBytes, int maxDepth) {
    return parse(raw, maxBytes, maxDepth).getAsJsonArray();
  }

  private static JsonElement parse(String raw, int maxBytes, int maxDepth) {
    if (raw == null || raw.length() > maxBytes
        || raw.getBytes(StandardCharsets.UTF_8).length > maxBytes)
      throw new SizeLimit();
    try (JsonReader reader = new JsonReader(new StringReader(raw))) {
      reader.setLenient(false);
      JsonElement value = readElement(reader, 1, maxDepth);
      if (reader.peek() != JsonToken.END_DOCUMENT)
        throw new IllegalArgumentException("json_trailing");
      return value;
    } catch (IOException e) {
      throw new IllegalArgumentException("invalid_json", e);
    }
  }

  private static JsonObject readObject(JsonReader reader, int depth, int maxDepth)
      throws IOException {
    if (depth > maxDepth) throw new IllegalArgumentException("json_depth");
    JsonObject object = new JsonObject();
    reader.beginObject();
    while (reader.hasNext()) {
      String key = reader.nextName();
      if (object.has(key)) throw new IllegalArgumentException("duplicate_key");
      object.add(key, readElement(reader, depth + 1, maxDepth));
    }
    reader.endObject();
    return object;
  }

  private static JsonArray readArray(JsonReader reader, int depth, int maxDepth)
      throws IOException {
    if (depth > maxDepth) throw new IllegalArgumentException("json_depth");
    JsonArray array = new JsonArray();
    reader.beginArray();
    while (reader.hasNext()) {
      array.add(readElement(reader, depth + 1, maxDepth));
    }
    reader.endArray();
    return array;
  }

  private static JsonElement readElement(JsonReader reader, int depth, int maxDepth)
      throws IOException {
    return switch (reader.peek()) {
      case BEGIN_OBJECT -> readObject(reader, depth, maxDepth);
      case BEGIN_ARRAY -> readArray(reader, depth, maxDepth);
      case STRING -> new JsonPrimitive(reader.nextString());
      case NUMBER -> {
        String num = reader.nextString();
        if (num.length() > 64) throw new IllegalArgumentException("json_number");
        BigDecimal bd = new BigDecimal(num);
        if ((bd.scale() < -100 || bd.scale() > 100) || bd.precision() > 40)
          throw new IllegalArgumentException("json_number");
        yield new JsonPrimitive(bd);
      }
      case BOOLEAN -> new JsonPrimitive(reader.nextBoolean());
      case NULL -> {
        reader.nextNull();
        yield JsonNull.INSTANCE;
      }
      default -> throw new IllegalArgumentException("invalid_json");
    };
  }

}
