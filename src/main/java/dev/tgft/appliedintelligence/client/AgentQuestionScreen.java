package dev.tgft.appliedintelligence.client;

import static dev.tgft.appliedintelligence.client.TerminalExtension.*;

import appeng.client.Point;
import appeng.client.gui.AEBaseScreen;
import appeng.client.gui.Icon;
import appeng.client.gui.style.ScreenStyle;
import appeng.client.gui.widgets.AE2Button;
import appeng.client.gui.widgets.Scrollbar;
import appeng.menu.me.crafting.CraftingStatusMenu;
import com.google.gson.*;
import dev.tgft.appliedintelligence.Protocol;
import dev.tgft.appliedintelligence.local.AgentQuestion;
import java.util.*;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.network.chat.Component;

/** An AE2 generated dialog on the same open crafting menu. Closing it never answers a question. */
final class AgentQuestionScreen extends AEBaseScreen<CraftingStatusMenu> {
  private static final int WIDTH = 248, HEIGHT = 238, INK = 0xff413f54;
  private final AgentCraftingStatusScreen parent;
  private final String jobId;
  private final AgentQuestion request;
  private final Map<String, String> drafts = new LinkedHashMap<>();
  private final Scrollbar bodyScroll, optionScroll;
  private final List<AE2Button> options = new ArrayList<>();
  private NativeTextField answer;
  private AE2Button previous, next, submit;
  private int page;
  private long nextPoll, sentAt;
  private boolean sending, expired, offline;
  private Component status = Component.empty();

  AgentQuestionScreen(AgentCraftingStatusScreen parent, String jobId, AgentQuestion request) {
    super(
        parent.getMenu(),
        parent.getMenu().getPlayerInventory(),
        label("question_title"),
        style(parent));
    this.parent = parent;
    this.jobId = jobId;
    this.request = request;
    bodyScroll = widgets.addScrollBar("questionBodyScroll", Scrollbar.BIG);
    optionScroll = widgets.addScrollBar("questionOptionScroll", Scrollbar.BIG);
    bodyScroll.setCaptureMouseWheel(false);
    optionScroll.setCaptureMouseWheel(false);
  }

  private static ScreenStyle style(AgentCraftingStatusScreen parent) {
    var json = UiStyles.COPY.toJsonTree(parent.getStyle()).getAsJsonObject();
    for (String key : List.of("background", "terminalStyle", "helpTopic")) json.remove(key);
    var background = new JsonObject();
    background.addProperty("width", WIDTH);
    background.addProperty("height", HEIGHT);
    json.add("generatedBackground", background);
    json.add("slots", new JsonObject());
    json.add("text", new JsonObject());
    var widgets = new JsonObject();
    var toolbar = new JsonObject();
    toolbar.addProperty("left", 0);
    toolbar.addProperty("top", 4);
    widgets.add("verticalToolbar", toolbar);
    for (String[] values :
        List.of(
            new String[] {"questionBodyScroll", "36", "50"},
            new String[] {"questionOptionScroll", "96", "57"})) {
      var widget = new JsonObject();
      widget.addProperty("left", 232);
      widget.addProperty("top", Integer.parseInt(values[1]));
      widget.addProperty("height", Integer.parseInt(values[2]));
      widgets.add(values[0], widget);
    }
    json.add("widgets", widgets);
    return UiStyles.COPY.fromJson(json, ScreenStyle.class);
  }

  private AgentQuestion.Question current() {
    return request.questions().get(page);
  }

  @Override
  protected void init() {
    super.init();
    Protocol.ClientReceiver.receiver = this::receive;
    addRenderableWidget(
        tab(Icon.BACK, "back", leftPos + WIDTH - 2, topPos + 1, false, this::onClose));
    options.clear();
    for (int i = 0; i < 3; i++) {
      final int slot = i;
      var button =
          new AE2Button(
              leftPos + 8,
              topPos + 96 + i * 20,
              218,
              18,
              Component.empty(),
              b -> {
                int index = optionScroll.getCurrentScroll() + slot;
                if (index < current().options().size())
                  answer.setValue(current().options().get(index).label());
              });
      options.add(addRenderableWidget(button));
    }
    answer = new NativeTextField(font, leftPos + 10, topPos + 180, 218, label("question_answer"));
    answer.setMaxLength(2000);
    answer.setValue(drafts.getOrDefault(current().id(), ""));
    answer.setResponder(text -> drafts.put(current().id(), text));
    addRenderableWidget(answer);
    previous =
        addRenderableWidget(
            new AE2Button(
                leftPos + 8,
                topPos + 214,
                54,
                18,
                label("question_previous"),
                b -> changePage(-1)));
    next =
        addRenderableWidget(
            new AE2Button(
                leftPos + 66, topPos + 214, 54, 18, label("question_next"), b -> changePage(1)));
    submit =
        addRenderableWidget(
            new AE2Button(
                leftPos + 142, topPos + 214, 98, 18, label("question_send"), b -> send()));
    resetScroll();
    nextPoll = 0;
  }

  private void resetScroll() {
    bodyScroll.setRange(
        0, Math.max(0, font.split(Component.literal(current().question()), 216).size() - 5), 1);
    bodyScroll.setCurrentScroll(0);
    optionScroll.setRange(0, Math.max(0, current().options().size() - 3), 1);
    optionScroll.setCurrentScroll(0);
  }

  private void changePage(int delta) {
    page += delta;
    answer.setValue(drafts.getOrDefault(current().id(), ""));
    resetScroll();
  }

  private void send() {
    var values = new JsonObject();
    for (var q : request.questions()) values.addProperty(q.id(), drafts.getOrDefault(q.id(), ""));
    var data = new JsonObject();
    data.addProperty("id", jobId);
    data.addProperty("request", request.request());
    data.add("answers", values);
    sending = true;
    sentAt = System.currentTimeMillis();
    status = label("question_sending");
    Protocol.send("answer_job", data);
  }

  private void receive(String raw) {
    try {
      var value = JsonParser.parseString(raw).getAsJsonObject();
      if (value.has("answer_ack")
          && request.request().equals(value.get("answer_ack").getAsString())) {
        onClose();
        return;
      }
      if (value.has("error_key")) {
        sending = false;
        status = label(value.get("error_key").getAsString());
        if (value.get("error_key").getAsString().equals("question_expired")) expired = true;
      }
      if (value.has("agent_disconnected")) offline = value.get("agent_disconnected").getAsBoolean();
      if (value.has("selected_job")) {
        var job = value.getAsJsonObject("selected_job");
        if (job.has("answered_request")
            && request.request().equals(job.get("answered_request").getAsString())) {
          onClose();
          return;
        }
        if (jobId.equals(job.get("id").getAsString())
            && (!job.has("question")
                || job.get("question").isJsonNull()
                || !request
                    .request()
                    .equals(job.getAsJsonObject("question").get("request").getAsString()))) {
          expired = true;
          status = label("question_expired");
        }
      }
    } catch (RuntimeException e) {
      status = label("invalid_server_response");
    }
  }

  @Override
  public void containerTick() {
    super.containerTick();
    long now = System.currentTimeMillis();
    if (sending && now - sentAt > 10000) {
      sending = false;
      status = label("question_retry");
    }
    if (now >= nextPoll) {
      nextPoll = now + 1500;
      var query = new JsonObject();
      query.addProperty("job", jobId);
      query.addProperty("progress_only", true);
      Protocol.send("status_jobs", query);
    }
  }

  @Override
  protected void updateBeforeRender() {
    super.updateBeforeRender();
    previous.active = page > 0;
    next.active = page + 1 < request.questions().size();
    submit.active =
        !expired
            && !sending
            && request.questions().stream()
                .allMatch(q -> !drafts.getOrDefault(q.id(), "").isBlank());
    answer.setEditable(!expired && !sending);
    for (int i = 0; i < options.size(); i++) {
      int index = optionScroll.getCurrentScroll() + i;
      var button = options.get(i);
      button.visible = index < current().options().size();
      button.active = !expired && !sending;
      if (button.visible) {
        var option = current().options().get(index);
        boolean selected = option.label().equals(answer.getValue());
        button.setMessage(
            Component.literal(
                (selected ? "● " : "") + font.plainSubstrByWidth(option.label(), 198)));
        button.setTooltip(
            Tooltip.create(Component.literal(option.label() + "\n" + option.description())));
      }
    }
  }

  @Override
  public void drawFG(GuiGraphics g, int x, int y, int mx, int my) {
    g.drawString(font, title, 8, 6, INK, false);
    String count = (page + 1) + "/" + request.questions().size();
    g.drawString(font, count, 226 - font.width(count), 6, INK, false);
    g.drawString(font, font.plainSubstrByWidth(current().header(), 216), 8, 22, INK, false);
    var lines = font.split(Component.literal(current().question()), 216);
    int start = bodyScroll.getCurrentScroll();
    for (int i = start; i < Math.min(lines.size(), start + 5); i++)
      g.drawString(font, lines.get(i), 8, 36 + (i - start) * 10, INK, false);
    g.drawString(font, label("question_answer"), 8, 163, INK, false);
    var message = offline ? label("connection_lost") : status;
    g.drawString(font, font.plainSubstrByWidth(message.getString(), 232), 8, 198, INK, false);
  }

  @Override
  public boolean mouseScrolled(double x, double y, double dx, double dy) {
    Point mouse = new Point((int) (x - leftPos), (int) (y - topPos));
    return (mouse.getY() < 92 ? bodyScroll : optionScroll).onMouseWheel(mouse, dy);
  }

  @Override
  public void onClose() {
    Protocol.ClientReceiver.receiver = ignored -> {};
    switchToScreen(parent);
  }

  @Override
  public void removed() {
    Protocol.ClientReceiver.receiver = ignored -> {};
    super.removed();
  }
}
