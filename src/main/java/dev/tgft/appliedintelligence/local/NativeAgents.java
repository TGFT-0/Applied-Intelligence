package dev.tgft.appliedintelligence.local;

import com.google.gson.JsonObject;
import dev.tgft.appliedintelligence.AppliedIntelligence;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.storage.LevelResource;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.fml.loading.FMLPaths;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

/** Only the private world's owner can reach a local task runner. No background sharing service. */
@EventBusSubscriber(modid = AppliedIntelligence.ID)
public final class NativeAgents {
  private static final Map<MinecraftServer, LocalAgentService> SERVICES = new IdentityHashMap<>();

  public static boolean allowed(ServerPlayer player) {
    MinecraftServer server = player.getServer();
    return server != null
        && server.isSingleplayer()
        && !server.isPublished()
        && server.isSingleplayerOwner(player.getGameProfile());
  }

  public static CompletableFuture<JsonObject> dispatch(
      ServerPlayer player, String action, JsonObject data) {
    if (!allowed(player)) return CompletableFuture.completedFuture(error("singleplayer_only"));
    if (action.startsWith("remote_") || action.startsWith("host_"))
      return CompletableFuture.completedFuture(error("remote_disabled"));
    LocalAgentService service;
    try {
      synchronized (SERVICES) {
        service = SERVICES.computeIfAbsent(player.getServer(), NativeAgents::createService);
      }
    } catch (RuntimeException e) {
      return CompletableFuture.completedFuture(error("local_storage_error"));
    }
    JsonObject captured = data.deepCopy();
    return CompletableFuture.supplyAsync(() -> service.dispatch(action, captured));
  }

  private static LocalAgentService createService(MinecraftServer server) {
    var local =
        new LocalCodex(
            FMLPaths.CONFIGDIR.get().resolve("applied-intelligence-local.json"),
            server.getWorldPath(LevelResource.ROOT).resolve("data/appliedintelligence/jobs.json"),
            FMLPaths.GAMEDIR.get().resolve("applied-intelligence-workspace"));
    try {
      return new LocalAgentService(
          local,
          server.getWorldPath(LevelResource.ROOT).resolve("data/appliedintelligence/routes.json"));
    } catch (java.io.IOException e) {
      local.close();
      throw new IllegalStateException(e);
    }
  }

  private static JsonObject error(String key) {
    var value = new JsonObject();
    value.addProperty("error_key", key);
    return value;
  }

  @SubscribeEvent
  public static void stopping(ServerStoppingEvent event) {
    close(event.getServer());
  }

  @SubscribeEvent
  public static void tick(ServerTickEvent.Post event) {
    if (event.getServer().isPublished()) close(event.getServer());
  }

  private static void close(MinecraftServer server) {
    LocalAgentService service;
    synchronized (SERVICES) {
      service = SERVICES.remove(server);
    }
    if (service != null) service.close();
  }
}
