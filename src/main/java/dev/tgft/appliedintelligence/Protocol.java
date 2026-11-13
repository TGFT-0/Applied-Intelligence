package dev.tgft.appliedintelligence;

import appeng.core.definitions.AEItems;
import appeng.menu.SlotSemantics;
import appeng.menu.me.common.MEStorageMenu;
import appeng.menu.me.crafting.CraftingStatusMenu;
import appeng.menu.me.items.PatternEncodingTermMenu;
import com.google.gson.*;
import dev.tgft.appliedintelligence.local.NativeAgents;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.*;
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

public final class Protocol {
  private static final Gson JSON = new GsonBuilder().disableHtmlEscaping().create();
  private static final Map<UUID, Long> LAST = new ConcurrentHashMap<>();
  private static final java.util.Set<UUID> IMAGE_PENDING = ConcurrentHashMap.newKeySet();
  private static final Map<UUID, Long> LAST_IMAGE = new ConcurrentHashMap<>();
  private static final Map<UUID, Long> LAST_QUERY = new ConcurrentHashMap<>();

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
    event
        .registrar("1")
        .playBidirectional(
            Message.TYPE,
            Message.CODEC,
            (message, context) ->
                context.enqueueWork(
                    () -> {
                      if (context.player() instanceof ServerPlayer player)
                        handle(player, message.body);
                      else ClientReceiver.accept(message.body);
                    }));
  }

  // Client installs a callback; dedicated servers never load client-only classes.
  public static final class ClientReceiver {
    public static java.util.function.Consumer<String> receiver = ignored -> {};

    static void accept(String body) {
      receiver.accept(body);
    }
  }

  public static void send(String action, JsonObject data) {
    JsonObject root = new JsonObject();
    root.addProperty("action", action);
    root.add("data", data);
    String body = JSON.toJson(root);
    if (body.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > 30000) {
      JsonObject error = new JsonObject();
      error.addProperty("error_key", "request_too_large");
      ClientReceiver.accept(JSON.toJson(error));
      return;
    }
    PacketDistributor.sendToServer(new Message(body));
  }

  private static void reply(ServerPlayer p, JsonObject response) {
    String body = JSON.toJson(response);
    if (body.length() > 60000) {
      response = new JsonObject();
      response.addProperty("error_key", "response_too_large");
      body = JSON.toJson(response);
    }
    PacketDistributor.sendToPlayer(p, new Message(body));
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
      for (var value : data.getAsJsonArray("slots")) {
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

  private static void handle(ServerPlayer p, String body) {
    try {
      JsonObject root = JsonParser.parseString(body).getAsJsonObject(),
          data = root.getAsJsonObject("data");
      String action = root.get("action").getAsString();
      if (action.equals("report_images") || action.equals("image_chunk")) {
        if (!NativeAgents.allowed(p)) throw new RequestFailure("singleplayer_only");
        String execution = UUID.fromString(data.get("id").getAsString()).toString();
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
        long imageNow = System.currentTimeMillis();
        if (imageNow - LAST_IMAGE.getOrDefault(p.getUUID(), 0L) < 100
            || !IMAGE_PENDING.add(p.getUUID())) throw new RequestFailure("rate_limited");
        LAST_IMAGE.put(p.getUUID(), imageNow);
        var server = p.getServer();
        NativeAgents.dispatch(p, action, data)
            .whenComplete(
                (response, failure) -> {
                  IMAGE_PENDING.remove(p.getUUID());
                  if (failure != null || server.isStopped()) return;
                  server.execute(
                      () -> {
                        if (response.has("error_key")) {
                          response.add("id", data.get("id"));
                          response.add("image", data.get("image"));
                          response.add("offset", data.get("offset"));
                        }
                        if (!p.hasDisconnected()) reply(p, response);
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
      long now = System.currentTimeMillis();
      boolean query =
          action.equals("agents")
              || action.equals("jobs")
              || action.equals("status_jobs")
              || action.equals("local_settings");
      Map<UUID, Long> rate = query ? LAST_QUERY : LAST;
      Long last = rate.get(p.getUUID());
      if (last != null && now - last < 150) {
        if (!query) error(p, "rate_limited");
        return;
      }
      rate.put(p.getUUID(), now);
      if (action.equals("skill") || action.equals("encode") || action.equals("copy")) {
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
            selected = JsonParser.parseString(old.getString("skills")).getAsJsonArray();
            if (old.contains("skill_names"))
              names = JsonParser.parseString(old.getString("skill_names")).getAsJsonArray();
          } else {
            selected = skills(p, data);
            if (data.has("slots"))
              for (var value : data.getAsJsonArray("slots"))
                names.add(card(p, value.getAsInt(), true).getHoverName().getString());
          }
          JsonObject encoded = new JsonObject();
          encoded.addProperty("prompt", bodyText);
          encoded.add("skills", selected);
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
                      if (action.equals("steer_job") && data.has("request"))
                        response.add("steer_request", data.get("request"));
                      if (!p.hasDisconnected()) reply(p, response);
                    });
              });
    } catch (RequestFailure e) {
      error(p, e.getMessage());
    } catch (Exception e) {
      error(p, "invalid_request");
    }
  }
}
