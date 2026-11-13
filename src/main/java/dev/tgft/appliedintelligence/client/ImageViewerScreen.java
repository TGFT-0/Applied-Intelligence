package dev.tgft.appliedintelligence.client;

import static dev.tgft.appliedintelligence.client.TerminalExtension.*;

import appeng.client.gui.AEBaseScreen;
import appeng.client.gui.Icon;
import appeng.client.gui.style.BackgroundGenerator;
import appeng.client.gui.widgets.AE2Button;
import com.google.gson.*;
import com.mojang.blaze3d.platform.NativeImage;
import dev.tgft.appliedintelligence.Protocol;
import dev.tgft.appliedintelligence.local.ImageAssets;
import java.io.ByteArrayOutputStream;
import java.util.*;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.ResourceLocation;

/** Same AE2 gallery for a live crafting menu and the finished vanilla book. */
public final class ImageViewerScreen extends Screen {
  private final Screen parent;
  private final String execution;
  private JsonArray images;
  private final Map<String, ResourceLocation> textures = new HashMap<>();
  private int index, left, top, panelWidth, panelHeight, offset;
  private final ByteArrayOutputStream download = new ByteArrayOutputStream();
  private String loading = "", message = "image_loading";
  private long sentAt, nextRequest;
  private boolean pending, closed;
  private double zoom = 1, panX, panY;
  private AE2Button previous, next;
  private Runnable closeAction;

  private ImageViewerScreen(Screen parent, String execution, JsonArray images, int index) {
    super(label("image_title"));
    this.parent = parent;
    this.execution = execution;
    this.images = images;
    this.index = index;
  }

  public static void open(Screen parent, String execution, JsonArray images, int index) {
    var gallery = new ImageViewerScreen(parent, execution, images, index);
    if (parent instanceof AgentCraftingStatusScreen crafting)
      crafting.switchToScreen(new ImageMenuScreen(crafting, gallery));
    else Minecraft.getInstance().setScreen(gallery);
  }

  @Override
  protected void init() {
    closed = false;
    Protocol.ClientReceiver.receiver = this::receive;
    panelWidth = Math.min(360, width - 48);
    panelHeight = Math.min(260, height - 24);
    left = (width - panelWidth) / 2;
    top = (height - panelHeight) / 2;
    var back = tab(Icon.BACK, "back", left + panelWidth - 2, top + 1, false, this::onClose);
    back.setTooltip(net.minecraft.client.gui.components.Tooltip.create(label("back")));
    addRenderableWidget(back);
    int buttonWidth = (panelWidth - 28) / 4;
    previous = button(0, buttonWidth, "image_previous", () -> select(index - 1));
    button(
        1,
        buttonWidth,
        "image_fit",
        () -> {
          zoom = 1;
          panX = panY = 0;
        });
    button(
        2,
        buttonWidth,
        "image_actual",
        () -> {
          if (entry() != null) {
            zoom = 1 / (fit(entry()) * minecraft.getWindow().getGuiScale());
            panX = panY = 0;
          }
        });
    next = button(3, buttonWidth, "image_next", () -> select(index + 1));
    if (images == null) {
      var query = new JsonObject();
      query.addProperty("id", execution);
      Protocol.send("report_images", query);
      sentAt = System.currentTimeMillis();
    } else index = Math.clamp(index, 0, Math.max(0, images.size() - 1));
  }

  private AE2Button button(int position, int size, String label, Runnable action) {
    return addRenderableWidget(
        new AE2Button(
            left + 8 + position * (size + 4),
            top + panelHeight - 26,
            size,
            18,
            label(label),
            b -> action.run()));
  }

  private JsonObject entry() {
    return images == null || images.isEmpty() ? null : images.get(index).getAsJsonObject();
  }

  private double fit(JsonObject e) {
    return Math.min(
        (panelWidth - 20.0) / e.get("width").getAsInt(),
        (panelHeight - 104.0) / e.get("height").getAsInt());
  }

  private void select(int value) {
    if (images == null || value < 0 || value >= images.size()) return;
    index = value;
    zoom = 1;
    panX = panY = 0;
  }

  private void receive(String raw) {
    if (closed) return;
    try {
      var response = JsonParser.parseString(raw).getAsJsonObject();
      if (response.has("images") && execution.equals(response.get("id").getAsString())) {
        var list = response.getAsJsonArray("images");
        if (list.size() > ImageAssets.MAX_IMAGES) throw new IllegalArgumentException();
        for (var value : list) {
          var e = value.getAsJsonObject();
          int w = e.get("width").getAsInt(), h = e.get("height").getAsInt();
          if (w < 1
              || h < 1
              || (long) w * h > 4_194_304
              || e.get("bytes").getAsInt() > ImageAssets.MAX_BYTES)
            throw new IllegalArgumentException();
        }
        images = list;
        if (images.isEmpty()) message = "image_unavailable";
        index = Math.clamp(index, 0, Math.max(0, images.size() - 1));
        return;
      }
      if (!response.has("image")
          || !execution.equals(response.get("id").getAsString())
          || !loading.equals(response.get("image").getAsString())
          || response.get("offset").getAsInt() != offset) return;
      if (!pending) return;
      if (response.has("error_key")) {
        fail();
        return;
      }
      int total = response.get("total").getAsInt();
      if (total < 1 || total > ImageAssets.MAX_BYTES) throw new IllegalArgumentException();
      byte[] part = Base64.getDecoder().decode(response.get("data").getAsString());
      if (part.length != Math.min(ImageAssets.CHUNK, total - offset))
        throw new IllegalArgumentException();
      download.writeBytes(part);
      offset += part.length;
      pending = false;
      if (offset == total) {
        byte[] bytes = download.toByteArray();
        if (!ImageAssets.digest(bytes).equals(loading)) throw new IllegalArgumentException();
        ImageAssets.pngDimensions(bytes);
        var image = NativeImage.read(bytes);
        if ((long) image.getWidth() * image.getHeight() > 4_194_304) {
          image.close();
          throw new IllegalArgumentException();
        }
        var texture = new DynamicTexture(image);
        textures.put(loading, minecraft.getTextureManager().register("ai-image", texture));
        loading = "";
        download.reset();
        offset = 0;
        message = "";
      }
    } catch (Exception e) {
      fail();
    }
  }

  private void fail() {
    pending = false;
    loading = "";
    download.reset();
    offset = 0;
    message = "image_unavailable";
    nextRequest = Long.MAX_VALUE;
  }

  @Override
  public void tick() {
    long now = System.currentTimeMillis();
    if (pending && now - sentAt > 10000 || images == null && now - sentAt > 10000) {
      fail();
      return;
    }
    if (images == null || images.isEmpty() || pending || now < nextRequest) return;
    if (loading.isEmpty()) {
      var wanted = entry();
      if (textures.containsKey(wanted.get("id").getAsString())) {
        wanted = null;
        for (var element : images)
          if (!textures.containsKey(element.getAsJsonObject().get("id").getAsString())) {
            wanted = element.getAsJsonObject();
            break;
          }
      }
      if (wanted == null) return;
      loading = wanted.get("id").getAsString();
      offset = 0;
      download.reset();
    }
    var query = new JsonObject();
    query.addProperty("id", execution);
    query.addProperty("image", loading);
    query.addProperty("offset", offset);
    pending = true;
    sentAt = now;
    nextRequest = now + 175;
    Protocol.send("image_chunk", query);
  }

  @Override
  public void renderBackground(GuiGraphics g, int x, int y, float partial) {
    /* Draw the dim layer before the AE2 frame, never blur the image itself. */
  }

  @Override
  public void render(GuiGraphics g, int mx, int my, float partial) {
    renderTransparentBackground(g);
    BackgroundGenerator.draw(panelWidth, panelHeight, g, left, top);
    g.drawString(font, title, left + 8, top + 6, 0xff413f54, false);
    var e = entry();
    String count = e == null ? "" : (index + 1) + " / " + images.size();
    g.drawString(
        font, count, left + panelWidth - 8 - font.width(count), top + 6, 0xff413f54, false);
    g.drawString(
        font,
        e == null ? "" : font.plainSubstrByWidth(e.get("name").getAsString(), panelWidth - 16),
        left + 8,
        top + 21,
        0xff413f54,
        false);
    int x = left + 8, y = top + 36, w = panelWidth - 16, h = panelHeight - 100;
    g.fill(x - 1, y - 1, x + w + 1, y + h + 1, 0xff66667e);
    g.fill(x, y, x + w, y + h, 0xff252635);
    if (e != null && textures.containsKey(e.get("id").getAsString())) {
      double scale = fit(e) * zoom;
      int iw = (int) Math.max(1, e.get("width").getAsInt() * scale),
          ih = (int) Math.max(1, e.get("height").getAsInt() * scale);
      panX = Math.clamp(panX, -Math.max(0, (iw - w) / 2.0), Math.max(0, (iw - w) / 2.0));
      panY = Math.clamp(panY, -Math.max(0, (ih - h) / 2.0), Math.max(0, (ih - h) / 2.0));
      g.enableScissor(x, y, x + w, y + h);
      g.blit(
          textures.get(e.get("id").getAsString()),
          x + (w - iw) / 2 + (int) panX,
          y + (h - ih) / 2 + (int) panY,
          0,
          0,
          iw,
          ih,
          iw,
          ih);
      g.disableScissor();
    } else
      g.drawCenteredString(
          font,
          label(message.isEmpty() ? "image_loading" : message),
          x + w / 2,
          y + h / 2,
          0xffdddde6);
    if (images != null)
      for (int i = 0; i < images.size(); i++) {
        int tx = left + 8 + i * 34, ty = top + panelHeight - 56;
        g.fill(tx - 1, ty - 1, tx + 31, ty + 23, i == index ? 0xff8eeaff : 0xff66667e);
        g.fill(tx, ty, tx + 30, ty + 22, 0xff252635);
        var image = images.get(i).getAsJsonObject();
        var texture = textures.get(image.get("id").getAsString());
        if (texture != null) {
          double scale =
              Math.min(28.0 / image.get("width").getAsInt(), 20.0 / image.get("height").getAsInt());
          int tw = Math.max(1, (int) (image.get("width").getAsInt() * scale)),
              th = Math.max(1, (int) (image.get("height").getAsInt() * scale));
          g.blit(texture, tx + (30 - tw) / 2, ty + (22 - th) / 2, 0, 0, tw, th, tw, th);
        } else g.drawCenteredString(font, Integer.toString(i + 1), tx + 15, ty + 7, 0xffffffff);
      }
    previous.active = e != null && index > 0;
    next.active = e != null && index + 1 < images.size();
    super.render(g, mx, my, partial);
  }

  @Override
  public boolean mouseClicked(double x, double y, int b) {
    if (b == 0 && images != null && y >= top + panelHeight - 57 && y < top + panelHeight - 32) {
      int i = (int) (x - left - 8) / 34;
      if (x >= left + 8 && i < images.size()) {
        select(i);
        return true;
      }
    }
    return super.mouseClicked(x, y, b);
  }

  private boolean canvas(double x, double y) {
    return x >= left + 8
        && x < left + panelWidth - 8
        && y >= top + 36
        && y < top + panelHeight - 64;
  }

  @Override
  public boolean mouseScrolled(double x, double y, double dx, double dy) {
    if (canvas(x, y) && entry() != null) {
      zoom = Math.clamp(zoom * Math.pow(1.15, dy), 0.1, Math.max(16, 1 / fit(entry())));
      return true;
    }
    return super.mouseScrolled(x, y, dx, dy);
  }

  @Override
  public boolean mouseDragged(double x, double y, int b, double dx, double dy) {
    if (b == 0 && canvas(x, y)) {
      panX += dx;
      panY += dy;
      return true;
    }
    return super.mouseDragged(x, y, b, dx, dy);
  }

  @Override
  public boolean isPauseScreen() {
    return false;
  }

  @Override
  public void onClose() {
    if (closeAction != null) closeAction.run();
    else minecraft.setScreen(parent);
  }

  @Override
  public void removed() {
    closed = true;
    Protocol.ClientReceiver.receiver = ignored -> {};
    textures.values().forEach(minecraft.getTextureManager()::release);
    textures.clear();
  }

  /** AE2 owns menu transitions, so the live CPU and its slots survive opening the gallery. */
  private static final class ImageMenuScreen
      extends AEBaseScreen<appeng.menu.me.crafting.CraftingStatusMenu> {
    private final AgentCraftingStatusScreen parent;
    private final ImageViewerScreen gallery;

    ImageMenuScreen(AgentCraftingStatusScreen parent, ImageViewerScreen gallery) {
      super(
          parent.getMenu(),
          parent.getMenu().getPlayerInventory(),
          gallery.getTitle(),
          emptyStyle(parent));
      this.parent = parent;
      this.gallery = gallery;
      gallery.closeAction = this::onClose;
    }

    private static appeng.client.gui.style.ScreenStyle emptyStyle(
        AgentCraftingStatusScreen parent) {
      var json = UiStyles.COPY.toJsonTree(parent.getStyle()).getAsJsonObject();
      json.remove("background");
      json.remove("terminalStyle");
      json.remove("helpTopic");
      json.add("slots", new JsonObject());
      json.add("text", new JsonObject());
      json.add("generatedBackground", JsonParser.parseString("{\"width\":1,\"height\":1}"));
      json.add("widgets", JsonParser.parseString("{\"verticalToolbar\":{\"left\":0,\"top\":4}}"));
      return UiStyles.COPY.fromJson(json, appeng.client.gui.style.ScreenStyle.class);
    }

    @Override
    protected void init() {
      super.init();
      gallery.init(minecraft, width, height);
    }

    @Override
    public void containerTick() {
      super.containerTick();
      gallery.tick();
    }

    @Override
    public void render(GuiGraphics g, int x, int y, float p) {
      gallery.render(g, x, y, p);
    }

    @Override
    public boolean mouseClicked(double x, double y, int b) {
      return gallery.mouseClicked(x, y, b);
    }

    @Override
    public boolean mouseReleased(double x, double y, int b) {
      return gallery.mouseReleased(x, y, b);
    }

    @Override
    public boolean mouseDragged(double x, double y, int b, double dx, double dy) {
      return gallery.mouseDragged(x, y, b, dx, dy);
    }

    @Override
    public boolean mouseScrolled(double x, double y, double dx, double dy) {
      return gallery.mouseScrolled(x, y, dx, dy);
    }

    @Override
    public boolean keyPressed(int k, int s, int m) {
      if (k == 256) {
        onClose();
        return true;
      }
      return gallery.keyPressed(k, s, m);
    }

    @Override
    public void onClose() {
      switchToScreen(parent);
    }

    @Override
    public void removed() {
      gallery.removed();
      super.removed();
    }
  }
}
