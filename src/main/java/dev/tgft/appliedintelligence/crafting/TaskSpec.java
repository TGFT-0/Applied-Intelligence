package dev.tgft.appliedintelligence.crafting;

import com.google.gson.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;

/** Immutable recipe identity: renaming or changing a Skill snapshot creates a different product. */
public record TaskSpec(String name, String prompt, List<String> skills) {
  private static final Gson JSON = new GsonBuilder().disableHtmlEscaping().create();

  public TaskSpec {
    if (name == null
        || name.isBlank()
        || name.length() > 64
        || name.chars().anyMatch(Character::isISOControl))
      throw new IllegalArgumentException("name");
    if (prompt == null
        || prompt.isBlank()
        || prompt.length() > 8000
        || skills == null
        || skills.size() > 9) throw new IllegalArgumentException("task");
    skills = List.copyOf(skills);
    if (skills.stream().anyMatch(s -> s == null || s.length() > 8000))
      throw new IllegalArgumentException("skills");
    JsonObject data = new JsonObject();
    data.addProperty("prompt", prompt);
    data.add("skills", JSON.toJsonTree(skills));
    if (JSON.toJson(data).getBytes(StandardCharsets.UTF_8).length > 24000)
      throw new IllegalArgumentException("size");
  }

  public JsonObject request(String id) {
    JsonObject data = new JsonObject();
    data.addProperty("id", id);
    data.addProperty("agent", "auto");
    data.addProperty("title", name);
    data.addProperty("recipe", key());
    data.addProperty("retain", true);
    data.addProperty("prompt", prompt);
    data.add("skills", JSON.toJsonTree(skills));
    return data;
  }

  public String key() {
    try {
      JsonObject identity = new JsonObject();
      identity.addProperty("name", name);
      identity.addProperty("prompt", prompt);
      identity.add("skills", JSON.toJsonTree(skills));
      return HexFormat.of()
          .formatHex(
              MessageDigest.getInstance("SHA-256")
                  .digest(JSON.toJson(identity).getBytes(StandardCharsets.UTF_8)));
    } catch (java.security.NoSuchAlgorithmException e) {
      throw new AssertionError(e);
    }
  }
}
