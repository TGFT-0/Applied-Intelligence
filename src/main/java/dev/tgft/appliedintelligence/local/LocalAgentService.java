package dev.tgft.appliedintelligence.local;

import com.google.gson.*;
import java.io.*;
import java.nio.file.*;
import java.util.*;

/** Local execution and durable world images. No connection, host, pairing, or transport code. */
public final class LocalAgentService implements AutoCloseable {
  private final LocalCodex local;
  private final ImageAssets images;
  private final Set<String> withdrawnJobs = new HashSet<>();
  private final Map<String, String> withdrawnRecipes = new HashMap<>();
  private final double opened = System.currentTimeMillis() / 1000.0;

  public LocalAgentService(LocalCodex local, Path legacyRoutes) throws IOException {
    this.local = local;
    images = new ImageAssets(legacyRoutes.toAbsolutePath().getParent().resolve("report-images"));
    // Retain only the old target guard. Never load connection credentials or replay an outbox.
    if (Files.exists(legacyRoutes)) {
      try {
        if (Files.size(legacyRoutes) > 8 * 1024 * 1024)
          throw new IOException("Routing history too large");
        var saved = JsonParser.parseString(Files.readString(legacyRoutes)).getAsJsonObject();
        var tickets = saved.getAsJsonObject("tickets");
        if (tickets == null) throw new IOException("Invalid routing history");
        for (var entry : tickets.entrySet()) {
          String id = UUID.fromString(entry.getKey()).toString();
          var ticket = entry.getValue().getAsJsonObject();
          if (!ticket.has("target") || !ticket.get("target").getAsString().equals("local")) {
            withdrawnJobs.add(id);
            if (ticket.has("request") && ticket.get("request").isJsonObject()) {
              var request = ticket.getAsJsonObject("request");
              if (request.has("recipe") && request.get("recipe").isJsonPrimitive())
                withdrawnRecipes.put(id, request.get("recipe").getAsString());
            }
          }
        }
      } catch (RuntimeException e) {
        throw new IOException("Invalid routing history", e);
      }
    }
  }

  private static JsonObject error(String key, boolean retry) {
    var value = new JsonObject();
    value.addProperty("error_key", key);
    value.addProperty("retryable", retry);
    return value;
  }

  public synchronized JsonObject dispatch(String action, JsonObject data) {
    try {
      if (action.startsWith("remote_") || action.startsWith("host_"))
        return error("remote_disabled", false);
      if (action.equals("image_chunk")) {
        // Already archived local and former remote books remain readable without their old host.
        try {
          return images.chunk(data);
        } catch (IOException ignored) {
        }
      }
      if (data.has("target") && !data.get("target").getAsString().equals("local")
          || data.has("id") && withdrawnJobs.contains(data.get("id").getAsString())
          || data.has("job") && withdrawnJobs.contains(data.get("job").getAsString())) {
        var rejected = error("remote_disabled", false);
        // Acknowledgment clears only the withdrawn CPU order; no old host is contacted.
        if (action.equals("cancel_job")
            && data.has("id")
            && withdrawnJobs.contains(data.get("id").getAsString()))
          rejected.add("cancel_ack", data.get("id"));
        return rejected;
      }
      var query = data.deepCopy();
      if (action.equals("status_jobs")) query.addProperty("progress_only", true);
      var response = local.dispatch(action, query);
      if (action.equals("job") && response.has("job")) archive(response.getAsJsonObject("job"));
      if (action.equals("cancel_job") && !response.has("error_key"))
        response.add("cancel_ack", data.get("id"));
      if (action.equals("status_jobs")) {
        response.addProperty("agent_disconnected", false);
        // Only CPUs that predate this world opening can refer to a withdrawn remote execution.
        if (data.has("recipe")
            && data.has("cpu_started")
            && data.get("cpu_started").getAsDouble() < opened - 5) {
          String recipe = data.get("recipe").getAsString();
          var matching =
              withdrawnRecipes.entrySet().stream()
                  .filter(e -> e.getValue().equals(recipe))
                  .toList();
          if (matching.size() == 1) {
            var legacy = new JsonObject();
            legacy.addProperty("id", matching.getFirst().getKey());
            legacy.addProperty("recipe", recipe);
            legacy.addProperty("state", "interrupted");
            legacy.addProperty("updated", opened);
            legacy.addProperty("created", 0);
            legacy.addProperty("result_key", "remote_disabled");
            legacy.add("progress", new Gson().toJsonTree(new JobProgress()));
            var capabilities = new JsonObject();
            capabilities.addProperty("steering", false);
            capabilities.addProperty("questions", false);
            legacy.add("capabilities", capabilities);
            response.add("selected_job", legacy);
          }
        }
        if (data.has("recipe")) response.add("requested_recipe", data.get("recipe"));
      }
      return response;
    } catch (LocalCodex.Failure e) {
      return error(e.key, false);
    } catch (IOException e) {
      return error("image_unavailable", true);
    } catch (RuntimeException e) {
      return error("invalid_request", false);
    }
  }

  private void archive(JsonObject job) throws IOException {
    if (!job.get("state").getAsString().equals("completed") || !job.has("images")) return;
    String id = job.get("id").getAsString();
    var manifest = job.getAsJsonArray("images");
    if (manifest.size() > ImageAssets.MAX_IMAGES) throw new IOException("Too many images");
    for (var element : manifest) {
      var entry = element.getAsJsonObject();
      String image = entry.get("id").getAsString();
      if (images.manifest(id).asList().stream()
          .anyMatch(e -> e.getAsJsonObject().get("id").getAsString().equals(image))) continue;
      int total = entry.get("bytes").getAsInt();
      if (total < 1 || total > ImageAssets.MAX_BYTES) throw new IOException("Invalid image size");
      var output = new ByteArrayOutputStream(total);
      while (output.size() < total) {
        var query = new JsonObject();
        query.addProperty("id", id);
        query.addProperty("image", image);
        query.addProperty("offset", output.size());
        var part = local.dispatch("image_chunk", query);
        if (part.has("error_key")
            || part.get("offset").getAsInt() != output.size()
            || part.get("total").getAsInt() != total
            || !id.equals(part.get("id").getAsString())
            || !image.equals(part.get("image").getAsString()))
          throw new IOException("Invalid image chunk");
        byte[] bytes = Base64.getDecoder().decode(part.get("data").getAsString());
        if (bytes.length != Math.min(ImageAssets.CHUNK, total - output.size()))
          throw new IOException("Invalid chunk length");
        output.writeBytes(bytes);
      }
      if (!ImageAssets.digest(output.toByteArray()).equals(image))
        throw new IOException("Invalid image digest");
      var saved = images.capture(id, output.toByteArray(), entry.get("name").getAsString());
      if (!saved.get("id").getAsString().equals(image)) throw new IOException("Image changed");
    }
  }

  @Override
  public synchronized void close() {
    local.close();
  }
}
