package dev.tgft.appliedintelligence.client;

import static dev.tgft.appliedintelligence.client.TerminalExtension.*;

import appeng.client.gui.Icon;
import appeng.client.gui.style.BackgroundGenerator;
import appeng.client.gui.widgets.AE2Button;
import com.google.gson.*;
import dev.tgft.appliedintelligence.Protocol;
import dev.tgft.appliedintelligence.local.AgentBackend;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import org.lwjgl.util.tinyfd.TinyFileDialogs;

/** Settings belong to the local private world owner, not an arbitrary game server. */
final class LocalSettingsScreen extends Screen {
  private AgentBackend provider = AgentBackend.CODEX;
  private final java.util.EnumMap<AgentBackend, String> executableByProvider =
      new java.util.EnumMap<>(AgentBackend.class);
  private AE2Button providerButton;
  private static final int WIDTH = 250, HEIGHT = 228, INK = 0xff413f54, MUTED = 0xff878fa5;
  private final Screen parent;
  private EditBox executable, workspace, context;
  private String executableDraft = "", workspaceDraft = "", version = "", contextDraft = "0";
  private Component status = label("local_loading");
  private boolean loaded, dirty, pendingSave;
  private long nextPoll, pollSent;
  private boolean polling;

  LocalSettingsScreen(Screen parent) {
    super(label("local_settings"));
    this.parent = parent;
  }

  private int left() {
    return (width - WIDTH) / 2;
  }

  private int top() {
    return Math.max(4, (height - HEIGHT) / 2);
  }

  private void button(Icon icon, String key, int x, int y, Runnable action) {
    var button = iconButton(icon, key, action);
    button.setPosition(left() + x, top() + y);
    addRenderableWidget(button);
  }

  @Override
  protected void init() {
    Protocol.ClientReceiver.receiver = this::receive;
    providerButton =
        addRenderableWidget(
            new AE2Button(
                left() + 8,
                top() + 23,
                232,
                18,
                Component.literal(provider.label),
                button -> {
                  if (!loaded || pendingSave) return;
                  var before = provider;
                  provider =
                      AgentBackend.values()[
                          (provider.ordinal() + 1) % AgentBackend.values().length];
                  executableByProvider.put(before, executable.getValue());
                  executable.setValue(
                      executableByProvider.getOrDefault(provider, provider.executable));
                  dirty = true;
                  status = label("local_unsaved");
                  providerButton.setMessage(Component.literal(provider.label));
                }));
    executable = new NativeTextField(font, left() + 10, top() + 61, 204, label("local_executable"));
    executable.setMaxLength(2048);
    executable.setValue(executableDraft);
    executable.setResponder(
        value -> {
          executableDraft = value;
          if (loaded) {
            dirty = true;
            status = label("local_unsaved");
          }
        });
    addRenderableWidget(executable);
    workspace = new NativeTextField(font, left() + 10, top() + 95, 204, label("local_workspace"));
    workspace.setMaxLength(2048);
    workspace.setValue(workspaceDraft);
    workspace.setResponder(
        value -> {
          workspaceDraft = value;
          if (loaded) {
            dirty = true;
            status = label("local_unsaved");
          }
        });
    addRenderableWidget(workspace);
    context = new NativeTextField(font, left() + 10, top() + 129, 204, label("context_setting"));
    context.setMaxLength(7);
    context.setValue(contextDraft);
    context.setFilter(v -> v.matches("[0-9]*"));
    context.setResponder(
        v -> {
          contextDraft = v;
          if (loaded) {
            dirty = true;
            status = label("local_unsaved");
          }
        });
    addRenderableWidget(context);
    button(Icon.BACK, "local_back", -18, 4, this::onClose);
    button(
        Icon.ACCESS_WRITE,
        "local_save_check",
        -18,
        30,
        () -> {
          if (!loaded || pendingSave) return;
          JsonObject data = new JsonObject();
          data.addProperty("provider", provider.id);
          data.addProperty("executable", executable.getValue());
          data.addProperty("workspace", workspace.getValue());
          int capacity;
          try {
            capacity = Integer.parseInt(context.getValue().isEmpty() ? "0" : context.getValue());
          } catch (NumberFormatException e) {
            status = label("context_invalid");
            return;
          }
          if (capacity > 2_000_000) {
            status = label("context_invalid");
            return;
          }
          data.addProperty("context_tokens", capacity);
          pendingSave = true;
          Protocol.send("local_configure", data);
          status = label("local_checking");
        });
    button(
        Icon.SCHEDULING_ROUND_ROBIN,
        "local_check",
        -18,
        52,
        () -> {
          if (dirty) {
            status = label("local_unsaved");
            return;
          }
          Protocol.send("local_check", new JsonObject());
        });
    button(
        Icon.PATTERN_ACCESS_SHOW,
        "local_browse_executable",
        225,
        56,
        () -> {
          try {
            String path =
                TinyFileDialogs.tinyfd_openFileDialog(
                    label("local_browse_executable").getString(),
                    executable.getValue(),
                    (org.lwjgl.PointerBuffer) null,
                    null,
                    false);
            if (path != null) executable.setValue(path);
          } catch (RuntimeException | LinkageError e) {
            status = label("local_dialog_error");
          }
        });
    button(
        Icon.PATTERN_ACCESS_SHOW,
        "local_browse_workspace",
        225,
        90,
        () -> {
          try {
            String path =
                TinyFileDialogs.tinyfd_selectFolderDialog(
                    label("local_browse_workspace").getString(), workspace.getValue());
            if (path != null) workspace.setValue(path);
          } catch (RuntimeException | LinkageError e) {
            status = label("local_dialog_error");
          }
        });
    polling = false;
    nextPoll = System.currentTimeMillis() + 250;
  }

  private void receive(String raw) {
    try {
      JsonObject response = JsonParser.parseString(raw).getAsJsonObject();
      polling = false;
      if (response.has("local_saved")) {
        dirty = false;
        pendingSave = false;
      }
      if (response.has("local")) {
        JsonObject local =
            response.has("local_config")
                ? response.getAsJsonObject("local_config")
                : response.getAsJsonObject("local");
        if (!loaded) {
          provider =
              AgentBackend.of(
                  local.has("provider") ? local.get("provider").getAsString() : "codex");
          providerButton.setMessage(Component.literal(provider.label));
          executable.setValue(local.get("executable").getAsString());
          workspace.setValue(local.get("workspace").getAsString());
          context.setValue(
              local.has("context_tokens") ? local.get("context_tokens").getAsString() : "0");
          loaded = true;
        }
        if (!dirty && !pendingSave) {
          String key = local.get("status").getAsString();
          status = label(key.equals("local_not_configured") ? "local_enter_paths" : key);
        }
        version = local.get("version").getAsString();
      }
      if (response.has("error_key")) {
        if (pendingSave) {
          pendingSave = false;
          dirty = true;
        }
        status = label(response.get("error_key").getAsString());
      }
    } catch (RuntimeException e) {
      status = label("invalid_server_response");
    }
  }

  @Override
  public void tick() {
    if (minecraft.player == null
        || minecraft.player.containerMenu == minecraft.player.inventoryMenu) {
      onClose();
      return;
    }
    if (System.currentTimeMillis() >= nextPoll
        && (!polling || System.currentTimeMillis() - pollSent > 30000)) {
      nextPoll = System.currentTimeMillis() + 1000;
      polling = true;
      pollSent = System.currentTimeMillis();
      Protocol.send("local_settings", new JsonObject());
    }
  }

  @Override
  public void renderBackground(GuiGraphics g, int mx, int my, float delta) {
    renderTransparentBackground(g);
    BackgroundGenerator.draw(WIDTH, HEIGHT, g, left(), top());
  }

  @Override
  public void render(GuiGraphics g, int mx, int my, float delta) {
    super.render(g, mx, my, delta);
    int x = left(), y = top();
    g.drawString(font, title, x + 8, y + 6, INK, false);
    g.drawString(font, label("local_executable"), x + 8, y + 46, INK, false);
    g.drawString(font, label("local_workspace"), x + 8, y + 80, INK, false);
    g.drawString(font, label("context_setting"), x + 8, y + 114, INK, false);
    var lines = font.split(status, 232);
    for (int i = 0; i < Math.min(3, lines.size()); i++)
      g.drawString(font, lines.get(i), x + 8, y + 152 + i * 10, INK, false);
    g.drawString(
        font, font.plainSubstrByWidth(dirty ? "" : version, 232), x + 8, y + 184, MUTED, false);
    var hint = font.split(label("local_install_hint"), 232);
    for (int i = 0; i < Math.min(2, hint.size()); i++)
      g.drawString(font, hint.get(i), x + 8, y + 202 + i * 10, MUTED, false);
  }

  @Override
  public boolean isPauseScreen() {
    return false;
  }

  @Override
  public void onClose() {
    Protocol.ClientReceiver.receiver = ignored -> {};
    minecraft.screen = null;
    if (minecraft.player != null
        && minecraft.player.containerMenu != minecraft.player.inventoryMenu)
      minecraft.setScreen(parent);
    else minecraft.setScreen(null);
  }
}
