package dev.tgft.appliedintelligence.crafting;

import com.google.gson.JsonObject;
import java.util.*;
import net.minecraft.ChatFormatting;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.*;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.network.Filterable;
import net.minecraft.world.item.*;
import net.minecraft.world.item.component.WrittenBookContent;
import net.minecraft.world.level.saveddata.SavedData;

/** Pending products survive the runner's rolling history. Each successful run can be bound once. */
public final class ReportArchive extends SavedData {
  private final Map<String, CompoundTag> entries = new LinkedHashMap<>();

  public static ReportArchive get(ServerLevel level) {
    return level
        .getServer()
        .overworld()
        .getDataStorage()
        .computeIfAbsent(
            new Factory<>(ReportArchive::new, ReportArchive::load), "appliedintelligence_reports");
  }

  private static ReportArchive load(CompoundTag root, HolderLookup.Provider lookup) {
    ReportArchive archive = new ReportArchive();
    for (var value : root.getList("reports", Tag.TAG_COMPOUND)) {
      var entry = (CompoundTag) value;
      archive.entries.put(entry.getString("id"), entry.copy());
    }
    return archive;
  }

  public void store(TaskSpec spec, JsonObject job) {
    String id = job.get("id").getAsString();
    if (!job.get("state").getAsString().equals("completed"))
      throw new IllegalArgumentException("not completed");
    if (entries.containsKey(id)) return;
    CompoundTag entry = new CompoundTag();
    entry.putString("id", id);
    entry.putString("recipe", spec.key());
    entry.putString("name", spec.name());
    entry.putString("result", job.get("result").getAsString());
    entry.putString(
        "author",
        dev.tgft.appliedintelligence.local.AgentBackend.author(
            job.has("agent") ? job.get("agent").getAsString() : "codex-local"));
    if (job.has("images")) entry.putString("images", job.get("images").toString());
    entries.put(id, entry);
    setDirty();
  }

  public ItemStack claim(String key) {
    for (var entry : entries.values()) {
      if (entry.getBoolean("claimed") || !entry.getString("recipe").equals(key)) continue;
      String name = entry.getString("name");
      String author = entry.contains("author") ? entry.getString("author") : "Codex";
      String text = name + "\n" + author + "\n\n" + entry.getString("result");
      var pages =
          new ArrayList<>(
              ReportPages.split(text).stream()
                  .map(s -> Filterable.<Component>passThrough(Component.literal(s)))
                  .toList());
      if (entry.contains("images")) {
        var images =
            com.google.gson.JsonParser.parseString(entry.getString("images")).getAsJsonArray();
        // One short link per page avoids book pagination splitting a clickable title.
        for (int i = 0; i < images.size(); i++) {
          final int imageIndex = i;
          String imageName = images.get(i).getAsJsonObject().get("name").getAsString();
          var link =
              Component.translatable("gui.appliedintelligence.image_book_link", imageName)
                  .withStyle(
                      style ->
                          style
                              .withColor(ChatFormatting.DARK_AQUA)
                              .withUnderlined(true)
                              .withClickEvent(
                                  new ClickEvent(
                                      ClickEvent.Action.RUN_COMMAND,
                                      "/ai-images " + entry.getString("id") + " " + imageIndex)));
          pages.add(
              Filterable.passThrough(
                  Component.translatable("gui.appliedintelligence.image_appendix")
                      .append("\n\n")
                      .append(link)));
        }
      }
      ItemStack book = new ItemStack(Items.WRITTEN_BOOK);
      String title = name.substring(0, Math.min(32, name.length()));
      if (!title.isEmpty() && Character.isHighSurrogate(title.charAt(title.length() - 1)))
        title = title.substring(0, title.length() - 1);
      book.set(
          DataComponents.WRITTEN_BOOK_CONTENT,
          new WrittenBookContent(Filterable.passThrough(title), author, 0, pages, true));
      book.set(DataComponents.CUSTOM_NAME, Component.literal(name));
      CompoundTag identity = new CompoundTag();
      identity.putString("execution", entry.getString("id"));
      book.set(
          DataComponents.CUSTOM_DATA, net.minecraft.world.item.component.CustomData.of(identity));
      entry.putBoolean("claimed", true);
      entry.remove("result");
      setDirty();
      return book;
    }
    return ItemStack.EMPTY;
  }

  public boolean containsImage(String execution, String image) {
    var entry = entries.get(execution);
    if (entry == null || !entry.contains("images")) return false;
    return com.google.gson.JsonParser.parseString(entry.getString("images"))
        .getAsJsonArray()
        .asList()
        .stream()
        .anyMatch(e -> e.getAsJsonObject().get("id").getAsString().equals(image));
  }

  public com.google.gson.JsonArray images(String execution) {
    var entry = entries.get(execution);
    return entry == null || !entry.contains("images")
        ? new com.google.gson.JsonArray()
        : com.google.gson.JsonParser.parseString(entry.getString("images")).getAsJsonArray();
  }

  @Override
  public CompoundTag save(CompoundTag root, HolderLookup.Provider lookup) {
    ListTag list = new ListTag();
    entries.values().forEach(e -> list.add(e.copy()));
    root.put("reports", list);
    return root;
  }
}
