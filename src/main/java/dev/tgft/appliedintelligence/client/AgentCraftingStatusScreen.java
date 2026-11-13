package dev.tgft.appliedintelligence.client;

import static dev.tgft.appliedintelligence.client.TerminalExtension.*;

import appeng.api.stacks.*;
import appeng.client.Point;
import appeng.client.gui.StackWithBounds;
import appeng.client.gui.me.crafting.CraftingStatusScreen;
import appeng.client.gui.style.PaletteColor;
import appeng.client.gui.style.ScreenStyle;
import appeng.client.gui.widgets.AE2Button;
import appeng.client.gui.widgets.Scrollbar;
import appeng.core.localization.GuiText;
import appeng.menu.me.crafting.*;
import com.google.gson.*;
import dev.tgft.appliedintelligence.AppliedIntelligence;
import dev.tgft.appliedintelligence.Protocol;
import java.util.*;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.*;
import net.minecraft.world.item.component.CustomData;

/** Native CPUs and material rows, with public Agent steps in the same table. */
public final class AgentCraftingStatusScreen extends CraftingStatusScreen {
  private final Map<String, SteeringDraft> steeringDrafts = new HashMap<>();
  private NativeMultilineField steeringInput;
  private AE2Button steeringSend;
  private SteeringDraft steeringDraft;
  private String steeringJob = "", steeringTurn = "";
  private boolean composerVisible, syncingInput, steeringAmbiguous;
  private final Map<String, AgentQuestionScreen> questionScreens = new HashMap<>();
  private final Map<String, net.minecraft.resources.ResourceLocation> imageThumbnails =
      new HashMap<>();
  private final Map<Integer, RecentUsage> recentUsage = new HashMap<>();

  private static final class RecentUsage {
    String recipe;
    long elapsed;
    double started;
    JsonObject job;

    RecentUsage(String recipe) {
      this.recipe = recipe;
    }
  }

  private final Map<Long, CraftingStatusEntry> entries = new LinkedHashMap<>();
  private final Scrollbar tableScroll, listScroll;
  private final AgentStepTable table;
  private JsonArray jobs = new JsonArray();
  private JsonObject detail;
  private long nextPoll, pollSent;
  private boolean polling;
  private boolean disconnected;
  private String cancelPending = "";
  private int cancelCpu;
  private String cancelRecipe = "";
  private long cancelElapsed;

  AgentCraftingStatusScreen(
      CraftingStatusMenu menu, Inventory inventory, Component title, ScreenStyle style) {
    super(menu, inventory, title, copyStyle(style));
    table = new AgentStepTable(this);
    tableScroll = widgets.addScrollBar("aiTableScroll", Scrollbar.BIG);
    listScroll = widgets.addScrollBar("aiCpuScroll", Scrollbar.BIG);
    listScroll.setCaptureMouseWheel(false);
    tableScroll.setCaptureMouseWheel(false);
    widgets.add("aiCpuList", new ContextCpuList(menu, listScroll, this.style, () -> jobs));
    widgets.add(
        "aiTokenUsage",
        new TokenUsageWidget(
            this.style, this::usageJob, this::hasUsageTarget, () -> selectedRecipe().isBlank()));
  }

  private static ScreenStyle copyStyle(ScreenStyle base) {
    var json = UiStyles.COPY.toJsonTree(base).getAsJsonObject();
    var widgets = json.getAsJsonObject("widgets");
    for (String[] ids :
        List.of(
            new String[] {"scrollbar", "aiTableScroll"},
            new String[] {"selectCpuScrollbar", "aiCpuScroll"},
            new String[] {"selectCpuList", "aiCpuList"})) {
      var copy = widgets.getAsJsonObject(ids[0]).deepCopy();
      widgets.add(ids[1], copy);
      widgets.getAsJsonObject(ids[0]).addProperty("left", -10000);
    }
    var token = new JsonObject();
    token.addProperty("left", 9);
    token.addProperty("bottom", 25);
    token.addProperty("width", 90);
    token.addProperty("height", 20);
    widgets.add("aiTokenUsage", token);
    return UiStyles.COPY.fromJson(json, ScreenStyle.class);
  }

  @Override
  protected void init() {
    super.init();
    int desired = Math.max(88, (width - imageWidth - 88) / 2 + 88), shift = desired - leftPos;
    leftPos = desired;
    for (var child : children())
      if (child instanceof net.minecraft.client.gui.components.AbstractWidget widget)
        widget.setX(widget.getX() + shift);
    steeringInput =
        addRenderableWidget(
            new NativeMultilineField(
                font, leftPos + 9, topPos + 126, 145, 26, label("steer_title")));
    steeringInput.setValueListener(
        value -> {
          if (!syncingInput && steeringDraft != null) steeringDraft.edit(value);
        });
    steeringSend =
        addRenderableWidget(
            new AE2Button(
                leftPos + 161,
                topPos + 134,
                52,
                18,
                label("steer_send"),
                button -> {
                  if (steeringDraft != null)
                    Protocol.send(
                        "steer_job",
                        steeringDraft.send(steeringJob, steeringTurn, System.currentTimeMillis()));
                }));
    steeringInput.visible = steeringSend.visible = false;
    Protocol.ClientReceiver.receiver =
        raw -> {
          try {
            var response = JsonParser.parseString(raw).getAsJsonObject();
            if (response.has("steer_request"))
              steeringDrafts.values().forEach(draft -> draft.receive(response));
            if (response.has("selected_job")) {
              var job = response.getAsJsonObject("selected_job");
              var draft = steeringDrafts.get(job.get("id").getAsString());
              if (draft != null && job.has("steering"))
                job.getAsJsonArray("steering")
                    .forEach(receipt -> draft.receive(receipt.getAsJsonObject()));
            }
            if (response.has("jobs") || response.has("error_key")) polling = false;
            if (!cancelPending.isBlank()
                && response.has("cancel_ack")
                && cancelPending.equals(response.get("cancel_ack").getAsString())) {
              cancelPending = "";
              if (menu.getSelectedCpuSerial() == cancelCpu
                  && selectedRecipe().equals(cancelRecipe)
                  && selectedCpu() != null
                  && selectedCpu().elapsedTimeNanos() >= cancelElapsed) menu.cancelCrafting();
            }
            if (response.has("error_key") && !response.has("steer_request")) {
              if (minecraft.player != null)
                minecraft.player.displayClientMessage(
                    label(response.get("error_key").getAsString()), false);
              cancelPending = "";
            }
            if (response.has("jobs")) {
              disconnected =
                  response.has("agent_disconnected")
                      && response.get("agent_disconnected").getAsBoolean()
                      && response.has("requested_recipe")
                      && selectedRecipe().equals(response.get("requested_recipe").getAsString());
              if (!disconnected) jobs = response.getAsJsonArray("jobs");
            }
            if (response.has("selected_job")) detail = response.getAsJsonObject("selected_job");
            else if (response.has("jobs") && !disconnected) detail = null;
          } catch (RuntimeException ignored) {
          }
        };
    polling = false;
    nextPoll = System.currentTimeMillis() + 250;
  }

  private CraftingStatusMenu.CraftingCpuListEntry selectedCpu() {
    return menu.cpuList.cpus().stream()
        .filter(c -> c.serial() == menu.getSelectedCpuSerial())
        .findFirst()
        .orElse(null);
  }

  private String selectedRecipe() {
    var cpu = selectedCpu();
    return cpu == null || cpu.currentJob() == null
        ? ""
        : AgentDisplay.recipe(cpu.currentJob().what());
  }

  private JsonObject matchingDetail() {
    if (detail == null
        || !detail.has("recipe")
        || detail.get("recipe").isJsonNull()
        || !selectedRecipe().equals(detail.get("recipe").getAsString())) return null;
    // A newly started CPU must not briefly show a previous completed run before dispatch.
    var cpu = selectedCpu();
    String state = detail.get("state").getAsString();
    if (cpu != null
        && !state.equals("running")
        && !state.equals("queued")
        && detail.get("updated").getAsDouble()
            < System.currentTimeMillis() / 1000.0 - cpu.elapsedTimeNanos() / 1_000_000_000.0 - 5)
      return null;
    return detail;
  }

  private boolean hasUsageTarget() {
    var cpu = selectedCpu();
    return cpu != null
        && (!selectedRecipe().isBlank()
            || cpu.currentJob() == null && recentUsage.containsKey(cpu.serial()));
  }

  private JsonObject usageJob() {
    var cpu = selectedCpu();
    if (cpu == null) return null;
    var recent = recentUsage.get(cpu.serial());
    if (recent == null || recent.job == null) return null;
    if (cpu.currentJob() != null) return recent.job;
    // The CLI may report totals only at turn completion, just as the CPU returns to idle.
    for (var value : jobs) {
      var summary = value.getAsJsonObject();
      if (summary.has("id") && summary.get("id").equals(recent.job.get("id"))) return summary;
    }
    return recent.job;
  }

  private void trackUsageTarget() {
    var cpu = selectedCpu();
    if (cpu == null) return;
    if (cpu.currentJob() == null) {
      var previous = recentUsage.get(cpu.serial());
      if (previous != null && previous.job == null) {
        var completed = AgentDisplay.find(jobs, previous.recipe);
        if (completed != null && completed.get("created").getAsDouble() >= previous.started - 5)
          previous.job = completed.deepCopy();
      }
      return;
    }
    String recipe = selectedRecipe();
    if (recipe.isBlank()) {
      recentUsage.remove(cpu.serial());
      return;
    }
    var recent = recentUsage.get(cpu.serial());
    if (recent == null
        || !recent.recipe.equals(recipe)
        || cpu.elapsedTimeNanos() < recent.elapsed) {
      recent = new RecentUsage(recipe);
      recent.started =
          System.currentTimeMillis() / 1000.0 - cpu.elapsedTimeNanos() / 1_000_000_000.0;
      recentUsage.put(cpu.serial(), recent);
    }
    recent.elapsed = cpu.elapsedTimeNanos();
    var candidate = matchingDetail();
    if (candidate == null) candidate = AgentDisplay.find(jobs, recipe);
    if (candidate != null
        && candidate.get("updated").getAsDouble()
            >= System.currentTimeMillis() / 1000.0 - cpu.elapsedTimeNanos() / 1_000_000_000.0 - 5)
      recent.job = candidate.deepCopy();
  }

  @Override
  public void containerTick() {
    super.containerTick();
    if (System.currentTimeMillis() >= nextPoll
        && (!polling || System.currentTimeMillis() - pollSent > 30000)) {
      nextPoll = System.currentTimeMillis() + 1000;
      var query = new JsonObject();
      query.addProperty("progress_only", true);
      String recipe = selectedRecipe();
      if (recipe.isBlank()) query.addProperty("summaries_only", true);
      else {
        query.addProperty("recipe", recipe);
        var cpu = selectedCpu();
        if (cpu != null)
          query.addProperty(
              "cpu_started",
              System.currentTimeMillis() / 1000.0 - cpu.elapsedTimeNanos() / 1_000_000_000.0);
      }
      polling = true;
      pollSent = System.currentTimeMillis();
      Protocol.send("status_jobs", query);
    }
  }

  @Override
  public void postUpdate(CraftingStatus status) {
    super.postUpdate(status);
    if (status.isFullStatus()) entries.clear();
    for (var e : status.getEntries()) {
      if (e.isDeleted()) entries.remove(e.getSerial());
      else {
        var key = e.getWhat();
        if (key == null && entries.containsKey(e.getSerial()))
          key = entries.get(e.getSerial()).getWhat();
        if (key != null)
          entries.put(
              e.getSerial(),
              new CraftingStatusEntry(
                  e.getSerial(),
                  key,
                  e.getStoredAmount(),
                  e.getActiveAmount(),
                  e.getPendingAmount()));
      }
    }
  }

  private List<CraftingStatusEntry> rows() {
    var result = new ArrayList<>(entries.values());
    Collections.sort(result);
    var job = matchingDetail();
    if (job != null && job.has("question") && !job.get("question").isJsonNull()) {
      var stack = new ItemStack(Items.WRITABLE_BOOK);
      var tag = new CompoundTag();
      tag.putBoolean("ai_question", true);
      stack.set(DataComponents.CUSTOM_DATA, CustomData.of(tag));
      stack.set(DataComponents.CUSTOM_NAME, label("question_waiting"));
      result.addFirst(new CraftingStatusEntry(-1000, AEItemKey.of(stack), 0, 1, 0));
    }
    if (job != null && job.has("images") && !job.getAsJsonArray("images").isEmpty()) {
      var stack = new ItemStack(Items.PAINTING);
      var tag = new CompoundTag();
      tag.putBoolean("ai_images", true);
      tag.putInt("count", job.getAsJsonArray("images").size());
      stack.set(DataComponents.CUSTOM_DATA, CustomData.of(tag));
      stack.set(
          DataComponents.CUSTOM_NAME,
          label("image_collection", job.getAsJsonArray("images").size()));
      result.addFirst(new CraftingStatusEntry(-1001, AEItemKey.of(stack), 0, 1, 0));
    }
    if (job == null || !job.has("progress")) return result;
    var progress = job.getAsJsonObject("progress");
    var steps = progress.getAsJsonArray("steps");
    if (steps == null) return result;
    for (int i = 0; i < Math.min(24, steps.size()); i++) {
      var step = steps.get(i).getAsJsonObject();
      String state = step.get("state").getAsString(), kind = step.get("kind").getAsString();
      String title =
          (i + 1)
              + ". "
              + (step.get("title").getAsString().isBlank()
                  ? label("step.action." + kind).getString()
                  : step.get("title").getAsString());
      Item icon =
          switch (state) {
            case "completed" -> Items.KNOWLEDGE_BOOK;
            case "running" -> Items.CLOCK;
            case "failed" -> Items.BARRIER;
            default -> Items.PAPER;
          };
      ItemStack stack = new ItemStack(icon);
      var tag = new CompoundTag();
      tag.putBoolean("ai_step", true);
      tag.putString("title", title);
      tag.putString("state", state);
      tag.putBoolean("planned", progress.get("planned").getAsBoolean());
      tag.putBoolean(
          "truncated", progress.has("truncated") && progress.get("truncated").getAsBoolean());
      tag.putInt("index", i);
      stack.set(DataComponents.CUSTOM_DATA, CustomData.of(tag));
      stack.set(DataComponents.CUSTOM_NAME, Component.literal(title));
      result.add(
          new CraftingStatusEntry(
              -i - 1,
              AEItemKey.of(stack),
              state.equals("completed") ? 1 : 0,
              state.equals("running") ? 1 : 0,
              state.equals("queued") ? 1 : 0));
    }
    return result;
  }

  @Override
  protected void updateBeforeRender() {
    super.updateBeforeRender();
    trackUsageTarget();
    var cpu = selectedCpu();
    if (!selectedRecipe().isBlank() && cpu != null) {
      Component heading =
          label(
              "crafting_agent_elapsed", AgentDisplay.time(cpu.elapsedTimeNanos() / 1_000_000_000L));
      var current = matchingDetail();
      if (current != null && current.has("question") && !current.get("question").isJsonNull())
        heading = heading.copy().append(" · ").append(label("question_waiting"));
      if (disconnected)
        heading =
            heading
                .copy()
                .append(" · ")
                .append(
                    label("connection_lost").copy().withStyle(net.minecraft.ChatFormatting.RED));
      if (menu.isCantStoreItems())
        heading =
            heading
                .copy()
                .append(" - ")
                .append(GuiText.CantStoreItems.text().withStyle(net.minecraft.ChatFormatting.RED));
      setTextContent(TEXT_ID_DIALOG_TITLE, heading);
    }
    updateSteering();
    int visibleRows = composerVisible ? 4 : 6;
    tableScroll.setHeight(composerVisible ? 91 : 137);
    tableScroll.setRange(0, Math.max(0, (rows().size() + 2) / 3 - visibleRows), 1);
  }

  private void updateSteering() {
    var job = matchingDetail();
    boolean active = job != null && job.get("state").getAsString().equals("running");
    boolean canSteer =
        job == null
            || !job.has("capabilities")
            || job.getAsJsonObject("capabilities").get("steering").getAsBoolean();
    composerVisible =
        canSteer
            && !selectedRecipe().isBlank()
            && (job == null || active || job.get("state").getAsString().equals("queued"));
    steeringInput.visible = steeringSend.visible = composerVisible;
    String id = active ? job.get("id").getAsString() : "";
    steeringJob = id;
    steeringTurn = active && job.has("turn") ? job.get("turn").getAsString() : "";
    steeringDraft =
        id.isBlank() ? null : steeringDrafts.computeIfAbsent(id, ignored -> new SteeringDraft());
    String text = steeringDraft == null ? "" : steeringDraft.text;
    if (!steeringInput.getValue().equals(text)) {
      syncingInput = true;
      steeringInput.setValue(text);
      syncingInput = false;
    }
    steeringAmbiguous =
        menu.cpuList.cpus().stream()
                .filter(
                    cpu ->
                        cpu.currentJob() != null
                            && selectedRecipe()
                                .equals(AgentDisplay.recipe(cpu.currentJob().what())))
                .count()
            > 1;
    boolean answering = active && job.has("question") && !job.get("question").isJsonNull();
    steeringInput.active =
        composerVisible
            && active
            && !steeringTurn.isBlank()
            && steeringDraft.editable()
            && !steeringAmbiguous;
    if (!steeringInput.active) steeringInput.setFocused(false);
    long now = System.currentTimeMillis();
    boolean retry = steeringDraft != null && steeringDraft.retryable(now);
    steeringSend.setMessage(label(retry ? "steer_retry" : "steer_send"));
    steeringSend.active =
        composerVisible
            && active
            && !steeringTurn.isBlank()
            && !answering
            && !steeringAmbiguous
            && !disconnected
            && !steeringDraft.text.isBlank()
            && (steeringDraft.editable() || retry);
  }

  @Override
  public void drawBG(GuiGraphics g, int x, int y, int mx, int my, float delta) {
    super.drawBG(g, x, y, mx, my, delta);
    if (composerVisible) {
      // Reuse blank panel and border pixels from the active AE2 background, including resource
      // packs.
      var background = style.getBackground();
      background.copy().src(8, 5, 1, 1).dest(x + 7, y + 111, 225, 47).blit(g);
      background.copy().src(8, 156, 206, 1).dest(x + 8, y + 111).blit(g);
      background.copy().src(218, 154, 12, 3).dest(x + 218, y + 108).blit(g);
    }
  }

  @Override
  public void drawFG(GuiGraphics g, int x, int y, int mx, int my) {
    var all = rows();
    int scroll = tableScroll.getCurrentScroll();
    // The native renderer draws six rows; stop its input at the fourth visible row for this layout.
    var visible = composerVisible ? all.subList(0, Math.min(all.size(), (scroll + 4) * 3)) : all;
    table.render(g, mx, my, visible, scroll);
    var imageJob = matchingDetail();
    if (imageJob != null
        && imageJob.has("images")
        && !imageJob.getAsJsonArray("images").isEmpty()) {
      var first = imageJob.getAsJsonArray("images").get(0).getAsJsonObject();
      if (first.has("thumbnail")) {
        String id = first.get("id").getAsString();
        if (!imageThumbnails.containsKey(id)) {
          try {
            byte[] bytes = Base64.getDecoder().decode(first.get("thumbnail").getAsString());
            if (bytes.length <= 2048) {
              int[] dimensions =
                  dev.tgft.appliedintelligence.local.ImageAssets.pngDimensions(bytes);
              if (dimensions[0] != 16 || dimensions[1] != 16) throw new java.io.IOException();
              var image = com.mojang.blaze3d.platform.NativeImage.read(bytes);
              if (image.getWidth() == 16 && image.getHeight() == 16)
                imageThumbnails.put(
                    id,
                    minecraft
                        .getTextureManager()
                        .register(
                            "ai-thumb",
                            new net.minecraft.client.renderer.texture.DynamicTexture(image)));
              else image.close();
            }
          } catch (Exception ignored) {
          }
        }
        var texture = imageThumbnails.get(id);
        if (texture != null)
          for (int i = scroll * 3;
              i < Math.min(visible.size(), (scroll + (composerVisible ? 4 : 6)) * 3);
              i++) {
            if (visible.get(i).getSerial() == -1001) {
              int tx = 9 + (i % 3) * 68 + 48, ty = 19 + (i / 3 - scroll) * 23 + 3;
              g.pose().pushPose();
              g.pose().translate(0, 0, 200);
              g.fill(tx, ty, tx + 16, ty + 16, 0xff66667e);
              g.blit(texture, tx, ty, 0, 0, 16, 16, 16, 16);
              g.pose().popPose();
            }
          }
      }
    }
    if (composerVisible) {
      int color = style.getColor(PaletteColor.DEFAULT_TEXT_COLOR).toARGB();
      g.drawString(font, label("steer_title"), 9, 115, color, false);
      String key = steeringDraft == null ? "" : steeringDraft.status(System.currentTimeMillis());
      var job = matchingDetail();
      if (key.isBlank() && job != null && job.has("question") && !job.get("question").isJsonNull())
        key = "steer_answer_first";
      if (steeringAmbiguous) key = "steer_ambiguous";
      if (!key.isBlank())
        g.drawString(
            font, font.plainSubstrByWidth(label(key).getString(), 148), 65, 115, color, false);
    }
  }

  @Override
  public StackWithBounds getStackUnderMouse(double x, double y) {
    return composerVisible && y >= topPos + 112 ? null : table.getHoveredStack();
  }

  @Override
  public boolean mouseClicked(double x, double y, int button) {
    if (button == 0 && !selectedRecipe().isBlank()) {
      var hovered = table.getHoveredStack();
      if (hovered != null
          && hovered.bounds().contains((int) x, (int) y)
          && hovered.stack().what() instanceof AEItemKey imageKey
          && imageKey
              .toStack()
              .getOrDefault(DataComponents.CUSTOM_DATA, CustomData.EMPTY)
              .copyTag()
              .getBoolean("ai_images")) {
        var job = matchingDetail();
        if (job != null && job.has("images"))
          ImageViewerScreen.open(
              this, job.get("id").getAsString(), job.getAsJsonArray("images"), 0);
        return true;
      }
      if (hovered != null
          && hovered.bounds().contains((int) x, (int) y)
          && hovered.stack().what() instanceof AEItemKey key
          && key.toStack()
              .getOrDefault(DataComponents.CUSTOM_DATA, CustomData.EMPTY)
              .copyTag()
              .getBoolean("ai_question")) {
        var job = matchingDetail();
        if (job != null && job.has("question") && !job.get("question").isJsonNull()) {
          var question =
              new Gson()
                  .fromJson(
                      job.get("question"), dev.tgft.appliedintelligence.local.AgentQuestion.class);
          switchToScreen(
              questionScreens.computeIfAbsent(
                  question.request(),
                  ignored -> new AgentQuestionScreen(this, job.get("id").getAsString(), question)));
        }
        return true;
      }
      var bounds =
          style
              .getWidget("cancel")
              .resolve(
                  new net.minecraft.client.renderer.Rect2i(
                      leftPos, topPos, imageWidth, imageHeight));
      for (var child : children())
        if (child instanceof net.minecraft.client.gui.components.Button widget
            && widget.getX() == bounds.getX()
            && widget.getY() == bounds.getY()
            && widget.isMouseOver(x, y)) {
          var job = matchingDetail();
          long candidates =
              menu.cpuList.cpus().stream()
                  .filter(
                      c ->
                          c.currentJob() != null
                              && selectedRecipe()
                                  .equals(AgentDisplay.recipe(c.currentJob().what())))
                  .count();
          if (job == null || candidates != 1) {
            if (minecraft.player != null)
              minecraft.player.displayClientMessage(
                  label(job == null ? "cancel_wait_dispatch" : "cancel_binding_ambiguous"), false);
            return true;
          }
          if (cancelPending.isBlank()) {
            cancelCpu = menu.getSelectedCpuSerial();
            cancelRecipe = selectedRecipe();
            cancelElapsed = selectedCpu().elapsedTimeNanos();
            cancelPending = job.get("id").getAsString();
            var query = new JsonObject();
            query.addProperty("id", cancelPending);
            Protocol.send("cancel_job", query);
          }
          return true;
        }
    }
    return super.mouseClicked(x, y, button);
  }

  @Override
  public boolean mouseScrolled(double x, double y, double dx, double dy) {
    if (composerVisible && steeringInput.isMouseOver(x, y))
      return steeringInput.mouseScrolled(x, y, dx, dy);
    if (composerVisible && x >= leftPos + 7 && y >= topPos + 112) return false;
    Point mouse = new Point((int) (x - leftPos), (int) (y - topPos));
    return mouse.getX() < 6
        ? listScroll.onMouseWheel(mouse, dy)
        : tableScroll.onMouseWheel(mouse, dy);
  }

  void appendAgentTooltip(ItemStack stack, List<Component> lines) {
    if (!stack.is(AppliedIntelligence.REPORT.get())) return;
    String recipe = AgentDisplay.recipe(AEItemKey.of(stack));
    var job = AgentDisplay.find(jobs, recipe);
    if (job == null) return;
    lines.add(label("agent_latest_execution"));
    String state = job.get("state").getAsString();
    double start =
        job.has("started") && job.get("started").getAsDouble() > 0
            ? job.get("started").getAsDouble()
            : job.get("created").getAsDouble();
    double end =
        state.equals("running") || state.equals("queued")
            ? System.currentTimeMillis() / 1000.0
            : job.get("updated").getAsDouble();
    lines.add(label("progress", jobState(state), Math.max(0, (long) (end - start))));
    int window = job.has("context_window") ? job.get("context_window").getAsInt() : 0;
    lines.add(
        window > 0
            ? label("context_capacity", String.format(Locale.ROOT, "%,d", window))
            : label("context_unknown"));
    if (job.has("stage")) lines.add(jobEvent(job.get("stage").getAsString()));
  }

  @Override
  public void removed() {
    Protocol.ClientReceiver.receiver = ignored -> {};
    imageThumbnails.values().forEach(minecraft.getTextureManager()::release);
    imageThumbnails.clear();
    super.removed();
  }
}
