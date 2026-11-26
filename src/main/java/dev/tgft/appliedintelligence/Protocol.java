package dev.tgft.appliedintelligence;

import appeng.core.definitions.AEItems;
import appeng.menu.SlotSemantics;
import appeng.menu.me.common.MEStorageMenu;
import appeng.menu.me.crafting.CraftingStatusMenu;
import appeng.menu.me.items.PatternEncodingTermMenu;
import com.google.gson.*;
import dev.tgft.appliedintelligence.local.NativeAgents;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.*;
import io.netty.handler.codec.DecoderException;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomData;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.registration.HandlerThread;

public final class Protocol {
  private static final Gson JSON = new GsonBuilder().disableHtmlEscaping().create();
  private static final Map<UUID, Long> LAST = new ConcurrentHashMap<>();
  private static final java.util.Set<ServerPlayer> IMAGE_PENDING = ConcurrentHashMap.newKeySet();
  private static final Map<UUID, Long> LAST_IMAGE = new ConcurrentHashMap<>();
  private static final Map<UUID, Long> LAST_QUERY = new ConcurrentHashMap<>();

  private static final Map<UUID, Ingress> INGRESS = new ConcurrentHashMap<>();
  private static final Map<UUID, Long> LAST_REPORT_IMAGES = new ConcurrentHashMap<>();
  private static final int MAX_REQUEST_BYTES = 32768;

  static final class Ingress {
    private static final int BURST = 20;
    private double tokens = BURST;
    private long updated;

    Ingress(long now) {
      updated = now;
    }

    synchronized boolean allow(long now) {
      if (now > updated) {
        tokens = Math.min(BURST, tokens + (now - updated) / 25_000_000.0);
        updated = now;
      }
      if (tokens < 1) return false;
      tokens--;
      return true;
    }
  }

  public record Request(String body) implements CustomPacketPayload {
    public static final Type<Request> TYPE =
        new Type<>(ResourceLocation.fromNamespaceAndPath(AppliedIntelligence.ID, "request"));
    public static final StreamCodec<RegistryFriendlyByteBuf, Request> CODEC =
        StreamCodec.of((buf, request) -> buf.writeUtf(request.body, MAX_REQUEST_BYTES), buf -> {
          int start = buf.readerIndex();
          int length = buf.readVarInt();
          if (length < 0 || length > MAX_REQUEST_BYTES)
            throw new DecoderException("Invalid Agent request size");
          buf.readerIndex(start);
          return new Request(buf.readUtf(MAX_REQUEST_BYTES));
        });

    @Override
    public Type<? extends CustomPacketPayload> type() {
      return TYPE;
    }
  }

  public record Message(String body) implements CustomPacketPayload {
    public static final Type<Message> TYPE =
        new Type<>(ResourceLocation.fromNamespaceAndPath(AppliedIntelligence.ID, "message"));
    public static final StreamCodec<RegistryFriendlyByteBuf, Message> CODEC =
        StreamCodec.of(
            (buf, msg) -> buf.writeUtf(msg.body, 65536), buf -> new Message(buf.readUtf(65536)));

    @Override
    public Type<? extends CustomPacketPayload> type() {
      return TYPE;
    }
  }

  public static void register(RegisterPayloadHandlersEvent event) {
    var registrar = event.registrar("2").executesOn(HandlerThread.NETWORK);
    registrar.playToClient(Message.TYPE, Message.CODEC, (message, context) -> {
      if (context.connection().isMemoryConnection())
        context.enqueueWork(() -> ClientReceiver.acceptRemote(message.body));
    });
    registrar.playToServer(Request.TYPE, Request.CODEC, (request, context) -> {
      if (!context.connection().isMemoryConnection()
          || !(context.player() instanceof ServerPlayer player)) return;
      long now = System.nanoTime();
      var ingress = INGRESS.computeIfAbsent(player.getUUID(), ignored -> new Ingress(now));
      if (ingress.allow(now)) context.enqueueWork(() -> handle(player, request.body));
    });
  }

  // Client installs a callback; dedicated servers never load client-only classes.
  public static final class ClientReceiver {
    public static java.util.function.Consumer<String> receiver = ignored -> {};
    public static volatile java.util.function.BooleanSupplier allowClientTransport = () -> false;

    static void accept(String body) {
      receiver.accept(body);
    }

    static void acceptRemote(String body) {
      if (!allowClientTransport.getAsBoolean()) return;
      try {
        parseBoundedObject(body, 65536, 16);
      } catch (RuntimeException e) {
        return;
      }
      receiver.accept(body);
    }
  }

  public static void send(String action, JsonObject data) {
    if (!ClientReceiver.allowClientTransport.getAsBoolean()) {
      JsonObject error = new JsonObject();
      error.addProperty("error_key", "singleplayer_only");
      ClientReceiver.accept(JSON.toJson(error));
      return;
    }
    JsonObject root = new JsonObject();
    root.addProperty("action", action);
    root.add("data", data);
    String body = JSON.toJson(root);
    if (body.getBytes(StandardCharsets.UTF_8).length > 30000) {
      JsonObject error = new JsonObject();
      error.addProperty("error_key", "request_too_large");
      ClientReceiver.accept(JSON.toJson(error));
      return;
    }
    PacketDistributor.sendToServer(new Request(body));
  }

  private static void reply(ServerPlayer p, JsonObject response) {
    String body = JSON.toJson(response);
    if (body.length() > 60000 || body.getBytes(StandardCharsets.UTF_8).length > 65536) {
      response = new JsonObject();
      response.addProperty("error_key", "response_too_large");
      body = JSON.toJson(response);
    }
    PacketDistributor.sendToPlayer(p, new Message(body));
  }

  public static void clearRateLimits() {
    LAST.clear();
    IMAGE_PENDING.clear();
    LAST_IMAGE.clear();
    LAST_QUERY.clear();
    LAST_REPORT_IMAGES.clear();
    INGRESS.clear();
  }

  public static void clearRateLimits(UUID playerId) {
    if (playerId == null) return;
    LAST.remove(playerId);
    IMAGE_PENDING.removeIf(player -> player.getUUID().equals(playerId));
    LAST_IMAGE.remove(playerId);
    LAST_QUERY.remove(playerId);
    LAST_REPORT_IMAGES.remove(playerId);
    INGRESS.remove(playerId);
  }

  static JsonObject parseBoundedObject(String raw, int maxBytes, int maxDepth) {
    try {
      return BoundedJson.object(raw, maxBytes, maxDepth);
    } catch (BoundedJson.SizeLimit e) {
      throw new RequestFailure("request_too_large");
    }
  }

  private static void error(ServerPlayer p, String text) {
    JsonObject r = new JsonObject();
    r.addProperty("error_key", text);
    reply(p, r);
  }

  private static final class RequestFailure extends IllegalArgumentException {
    RequestFailure(String key) {
      super(key);
    }
  }

  private static ItemStack card(ServerPlayer p, int slot, boolean skill) {
    if (slot < 0 || slot >= 36) throw new RequestFailure("invalid_slot");
    ItemStack stack = p.getInventory().getItem(slot);
    if (!stack.is((skill ? AppliedIntelligence.SKILL : AppliedIntelligence.TASK).get()))
      throw new RequestFailure("card_changed");
    return stack;
  }

  private static String cardName(JsonObject data) {
    if (!data.has("name") || !data.get("name").isJsonPrimitive())
      throw new RequestFailure("card_name_required");
    String value = data.get("name").getAsString().trim();
    if (value.isBlank()) throw new RequestFailure("card_name_required");
    if (value.length() > 64 || value.chars().anyMatch(Character::isISOControl))
      throw new RequestFailure("card_name_invalid");
    return value;
  }

  private static String text(JsonObject data, String key) {
    String value = data.get(key).getAsString();
    if (value.isBlank() || value.length() > 8000) throw new RequestFailure("invalid_text");
    return value;
  }

  private static JsonArray skills(ServerPlayer p, JsonObject data) {
    JsonArray result = new JsonArray();
    if (data.has("slots")) {
      if (data.getAsJsonArray("slots").size() > 9) throw new RequestFailure("too_many_skills");
      var seen = new java.util.HashSet<Integer>();
      for (var value : data.getAsJsonArray("slots")) {
        if (!seen.add(value.getAsInt())) continue;
        CompoundTag tag =
            card(p, value.getAsInt(), true)
                .getOrDefault(DataComponents.CUSTOM_DATA, CustomData.EMPTY)
                .copyTag();
        result.add(tag.getString("skill"));
      }
    }
    return result;
  }

  private static void give(ServerPlayer p, ItemStack stack) {
    if (!p.getInventory().add(stack)) p.drop(stack, false);
    p.containerMenu.broadcastChanges();
  }

  private static void consumeBlank(ServerPlayer p) {
    if (p.containerMenu instanceof PatternEncodingTermMenu menu) {
      for (var slot : menu.getSlots(SlotSemantics.BLANK_PATTERN)) {
        if (slot.getItem().is(AEItems.BLANK_PATTERN.get())) {
          slot.getItem().shrink(1);
          slot.setChanged();
          return;
        }
      }
    }
    throw new RequestFailure("blank_required");
  }

  private static boolean allowInterval(Map<UUID, Long> rate, UUID player, long now, long millis) {
    Long previous = rate.get(player);
    if (previous != null && now - previous < TimeUnit.MILLISECONDS.toNanos(millis)) return false;
    rate.put(player, now);
    return true;
  }

  private static void handle(ServerPlayer p, String body) {
    if (p.hasDisconnected() || !NativeAgents.allowed(p)) return;
    try {
      JsonObject root = parseBoundedObject(body, MAX_REQUEST_BYTES, 16);
      if (!root.has("data")
          || !root.get("data").isJsonObject()
          || !root.has("action")
          || !root.get("action").isJsonPrimitive()
          || !root.get("action").getAsJsonPrimitive().isString())
        throw new RequestFailure("invalid_request");
      JsonObject data = root.getAsJsonObject("data");
      String action = root.get("action").getAsString();
      if (action.equals("report_images") || action.equals("image_chunk")) {
        long imageNow = System.nanoTime();
        if (action.equals("report_images")) {
          if (!allowInterval(LAST_REPORT_IMAGES, p.getUUID(), imageNow, 150)) return;
        } else if (!allowInterval(LAST_IMAGE, p.getUUID(), imageNow, 100)
            || IMAGE_PENDING.contains(p)) {
          throw new RequestFailure("rate_limited");
        }
        String rawId = data.get("id").getAsString();
        String execution = UUID.fromString(rawId).toString();
        if (!rawId.equals(execution)) throw new RequestFailure("invalid_request");
        boolean terminal =
            p.containerMenu instanceof CraftingStatusMenu && p.containerMenu.stillValid(p);
        boolean book =
            java.util.stream.Stream.of(p.getMainHandItem(), p.getOffhandItem())
                .anyMatch(
                    stack ->
                        stack.is(net.minecraft.world.item.Items.WRITTEN_BOOK)
                            && execution.equals(
                                stack
                                    .getOrDefault(DataComponents.CUSTOM_DATA, CustomData.EMPTY)
                                    .copyTag()
                                    .getString("execution")));
        if (!terminal && !book) throw new RequestFailure("image_unavailable");
        var archive = dev.tgft.appliedintelligence.crafting.ReportArchive.get(p.serverLevel());
        if (action.equals("report_images")) {
          var response = new JsonObject();
          response.addProperty("id", execution);
          response.add("images", archive.images(execution));
          reply(p, response);
          return;
        }
        if (book && !terminal && !archive.containsImage(execution, data.get("image").getAsString()))
          throw new RequestFailure("image_unavailable");
        IMAGE_PENDING.add(p);
        var server = p.getServer();
        NativeAgents.dispatch(p, action, data)
            .whenComplete(
                (response, failure) -> {
                  IMAGE_PENDING.remove(p);
                  if (failure != null || server.isStopped()) return;
                  server.execute(
                      () -> {
                        if (response.has("error_key")) {
                          response.addProperty("id", execution);
                          if (data.has("image")
                              && data.get("image").isJsonPrimitive()
                              && data.get("image").getAsJsonPrimitive().isString()) {
                            String image = data.get("image").getAsString();
                            if (image.length() <= 64) response.addProperty("image", image);
                          }
                          if (data.has("offset")
                              && data.get("offset").isJsonPrimitive()
                              && data.get("offset").getAsJsonPrimitive().isNumber()) {
                            try {
                              response.addProperty("offset", data.get("offset").getAsInt());
                            } catch (RuntimeException ignored) {
                            }
                          }
                        }
                        if (!p.hasDisconnected() && NativeAgents.allowed(p)) reply(p, response);
                      });
                });
        return;
      }
      if (!(p.containerMenu instanceof MEStorageMenu)
          && !(p.containerMenu instanceof CraftingStatusMenu)
          && !(p.containerMenu instanceof appeng.menu.me.crafting.CraftConfirmMenu
              && action.equals("local_settings"))) throw new RequestFailure("open_terminal");
      if (!p.containerMenu.stillValid(p)) throw new RequestFailure("terminal_unavailable");
      if (!action.startsWith("local_")
          && p.containerMenu instanceof MEStorageMenu m
          && (m.getGridNode() == null || !m.getGridNode().isActive()))
        throw new RequestFailure("network_offline");
      long now = System.nanoTime();
      boolean query =
          action.equals("agents")
              || action.equals("jobs")
              || action.equals("status_jobs")
              || action.equals("local_settings");
      Map<UUID, Long> rate = query ? LAST_QUERY : LAST;
      if (!allowInterval(rate, p.getUUID(), now, 150)) {
        if (!query) error(p, "rate_limited");
        return;
      }
      if (action.equals("skill") || action.equals("encode") || action.equals("copy")) {
        if (!NativeAgents.allowed(p)) throw new RequestFailure("singleplayer_only");
        if (!(p.containerMenu instanceof PatternEncodingTermMenu))
          throw new RequestFailure("encoding_terminal_required");
        String name = cardName(data);
        boolean task = action.equals("encode");
        boolean editing = data.has("slot") && !action.equals("copy");
        ItemStack source =
            data.has("slot") ? card(p, data.get("slot").getAsInt(), !task) : ItemStack.EMPTY;
        String bodyText = text(data, "text");
        CompoundTag tag = new CompoundTag();
        tag.putString("name", name);
        if (!task) tag.putString("skill", bodyText);
        else {
          JsonArray selected;
          JsonArray names = new JsonArray();
          if (editing && data.has("keep_skills") && data.get("keep_skills").getAsBoolean()) {
            CompoundTag old =
                source.getOrDefault(DataComponents.CUSTOM_DATA, CustomData.EMPTY).copyTag();
            selected = BoundedJson.array(old.getString("skills"), 24000, 1);
            if (old.contains("skill_names"))
              names = BoundedJson.array(old.getString("skill_names"), 24000, 1);
          } else {
            selected = skills(p, data);
            if (data.has("slots")) {
              var seen = new java.util.HashSet<Integer>();
              for (var value : data.getAsJsonArray("slots"))
                if (seen.add(value.getAsInt()))
                  names.add(card(p, value.getAsInt(), true).getHoverName().getString());
            }
          }
          if (selected.size() > 9 || names.size() > 9) throw new RequestFailure("too_many_skills");
          for (var skill : selected)
            if (!skill.isJsonPrimitive() || !skill.getAsJsonPrimitive().isString()
                || skill.getAsString().length() > 8000) throw new RequestFailure("invalid_text");
          for (int i = 0; i < names.size(); i++) {
            String display = names.get(i).getAsString();
            int end = Math.min(64, display.length());
            if (end > 0 && Character.isHighSurrogate(display.charAt(end - 1))) end--;
            names.set(i, new JsonPrimitive(display.substring(0, end)));
          }
          JsonObject encoded = new JsonObject();
          encoded.addProperty("prompt", bodyText);
          encoded.add("skills", selected);
          encoded.add("skill_names", names);
          if (JSON.toJson(encoded).getBytes(java.nio.charset.StandardCharsets.UTF_8).length > 24000)
            throw new RequestFailure("pattern_too_large");
          tag.putString("prompt", bodyText);
          tag.putString("skills", selected.toString());
          tag.putString("skill_names", names.toString());
        }
        ItemStack out;
        if (editing) out = source;
        else {
          consumeBlank(p);
          out = new ItemStack((task ? AppliedIntelligence.TASK : AppliedIntelligence.SKILL).get());
        }
        out.set(DataComponents.CUSTOM_DATA, CustomData.of(tag));
        out.set(DataComponents.CUSTOM_NAME, Component.literal(name));
        if (!editing) give(p, out);
        p.containerMenu.broadcastChanges();
        JsonObject ok = new JsonObject();
        ok.addProperty("message_key", "saved");
        reply(p, ok);
        return;
      }
      if (action.equals("craft")) {
        if (!NativeAgents.allowed(p)) throw new RequestFailure("singleplayer_only");
        if (!(p.containerMenu instanceof MEStorageMenu menu))
          throw new RequestFailure("open_terminal");
        var task =
            new dev.tgft.appliedintelligence.crafting.TaskPattern(
                appeng.api.stacks.AEItemKey.of(card(p, data.get("slot").getAsInt(), false)));
        var product = appeng.api.stacks.AEItemKey.of(task.product());
        if (menu.getGridNode() == null
            || !menu.getGridNode().getGrid().getCraftingService().isCraftable(product))
          throw new RequestFailure("craft_install_pattern");
        if (menu.getLocator() == null) throw new RequestFailure("open_terminal");
        appeng.menu.me.crafting.CraftAmountMenu.open(p, menu.getLocator(), product, 1);
        return;
      }
      if (!action.equals("agents")
          && !action.equals("status_jobs")
          && !action.equals("local_settings")
          && !action.equals("local_configure")
          && !action.equals("cancel_job")
          && !action.equals("answer_job")
          && !action.equals("steer_job")
          && !action.equals("local_check")) throw new RequestFailure("unknown_action");
      var server = p.getServer();
      NativeAgents.dispatch(p, action, data)
          .thenAccept(
              response -> {
                if (server.isStopped()) return;
                server.execute(
                    () -> {
                      if (action.equals("steer_job")
                          && data.has("request")
                          && data.get("request").isJsonPrimitive()
                          && data.get("request").getAsJsonPrimitive().isString()) {
                        String requestId = data.get("request").getAsString();
                        if (requestId.length() <= 64) response.addProperty("steer_request", requestId);
                      }
                      if (!p.hasDisconnected() && NativeAgents.allowed(p)) reply(p, response);
                    });
              });
    } catch (RequestFailure e) {
      error(p, e.getMessage());
    } catch (Exception e) {
      error(p, "invalid_request");
    }
  }
}
