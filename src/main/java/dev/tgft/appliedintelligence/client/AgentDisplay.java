package dev.tgft.appliedintelligence.client;

import appeng.api.stacks.*;
import com.google.gson.*;
import dev.tgft.appliedintelligence.AppliedIntelligence;
import java.util.*;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.item.component.CustomData;

final class AgentDisplay {
  static String recipe(AEKey key) {
    return key instanceof AEItemKey item && item.is(AppliedIntelligence.REPORT.get())
        ? item.toStack()
            .getOrDefault(DataComponents.CUSTOM_DATA, CustomData.EMPTY)
            .copyTag()
            .getString("recipe")
        : "";
  }

  static String time(long seconds) {
    long n = Math.max(0, seconds);
    return String.format(Locale.ROOT, "%02d:%02d:%02d", n / 3600, (n / 60) % 60, n % 60);
  }

  static String capacity(int tokens) {
    if (tokens <= 0) return "?";
    return tokens >= 1000
        ? String.format(Locale.ROOT, "%.0fK", tokens / 1000.0)
        : Integer.toString(tokens);
  }

  static JsonObject find(JsonArray jobs, String recipe) {
    if (recipe.isBlank()) return null;
    JsonObject best = null;
    int bestPriority = 99;
    for (var element : jobs) {
      var job = element.getAsJsonObject();
      if (!job.has("recipe")
          || job.get("recipe").isJsonNull()
          || !recipe.equals(job.get("recipe").getAsString())) continue;
      String state = job.get("state").getAsString();
      int priority = state.equals("running") ? 0 : state.equals("queued") ? 1 : 2;
      if (priority < bestPriority) {
        best = job;
        bestPriority = priority;
      }
    }
    if (best != null) return best;
    return null;
  }
}
