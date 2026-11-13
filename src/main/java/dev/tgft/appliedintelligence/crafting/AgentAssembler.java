package dev.tgft.appliedintelligence.crafting;

import appeng.api.AECapabilities;
import appeng.api.config.Actionable;
import appeng.api.crafting.IPatternDetails;
import appeng.api.implementations.blockentities.*;
import appeng.api.stacks.*;
import com.google.gson.JsonObject;
import dev.tgft.appliedintelligence.AppliedIntelligence;
import dev.tgft.appliedintelligence.local.NativeAgents;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import net.minecraft.core.*;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.*;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.*;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

/** Normal AE2 machine contract. Never produces an expected output for a failed Agent task. */
public final class AgentAssembler extends BlockEntity implements ICraftingMachine {
  private ItemStack pattern = ItemStack.EMPTY;
  private String job = "", phase = "idle", error = "", target = "local";
  private Direction provider = Direction.NORTH;
  private CompletableFuture<JsonObject> pending;
  private boolean submitting, probing;
  private int ticks;

  public AgentAssembler(BlockPos pos, BlockState state) {
    super(AppliedIntelligence.ASSEMBLER_ENTITY.get(), pos, state);
  }

  private ServerPlayer owner() {
    if (!(level instanceof ServerLevel server)) return null;
    return server.getServer().getPlayerList().getPlayers().stream()
        .filter(NativeAgents::allowed)
        .findFirst()
        .orElse(null);
  }

  public PatternContainerGroup getCraftingMachineInfo() {
    return new PatternContainerGroup(
        AEItemKey.of(AppliedIntelligence.ASSEMBLER_ITEM.get()),
        Component.translatable("block.appliedintelligence.agent_assembler"),
        List.of());
  }

  public boolean acceptsPlans() {
    return phase.equals("idle") && owner() != null;
  }

  public boolean pushPattern(IPatternDetails details, KeyCounter[] inputs, Direction side) {
    if (!(details instanceof TaskPattern task) || !acceptsPlans() || inputs.length != 1)
      return false;
    var paper = AEItemKey.of(Items.PAPER);
    if (inputs[0].size() != 1 || inputs[0].get(paper) != 1) return false;
    target = "local";
    pattern = task.getDefinition().toStack();
    provider = side;
    job = UUID.randomUUID().toString();
    phase = "new";
    error = "";
    inputs[0].clear();
    setChanged();
    return true;
  }

  public void tick() {
    if (phase.equals("idle") || phase.equals("failed") || ++ticks % 20 != 0) return;
    ServerPlayer owner = owner();
    if (owner == null) return;
    TaskPattern task;
    try {
      task = new TaskPattern(AEItemKey.of(pattern));
    } catch (RuntimeException e) {
      fail("invalid_text");
      return;
    }
    if (phase.equals("output")) {
      releaseRecord(owner);
      BlockPos target = worldPosition.relative(provider);
      var inventory =
          level.getCapability(AECapabilities.GENERIC_INTERNAL_INV, target, provider.getOpposite());
      if (inventory != null) {
        var product = AEItemKey.of(task.product());
        for (int slot = 0; slot < inventory.size(); slot++)
          if (inventory.insert(slot, product, 1, Actionable.MODULATE) == 1) {
            clear();
            break;
          }
      }
      return;
    }
    if (pending != null) {
      if (!pending.isDone()) return;
      JsonObject response;
      try {
        response = pending.join();
      } catch (RuntimeException e) {
        pending = null;
        fail("local_execution_failed");
        return;
      }
      pending = null;
      if (response.has("error_key")) {
        if (response.has("retryable") && response.get("retryable").getAsBoolean()) {
          if (submitting) phase = "dispatch";
          if (probing) phase = "new";
          submitting = probing = false;
          ticks = -60;
          error = response.get("error_key").getAsString();
          setChanged();
          return;
        }
        fail(response.get("error_key").getAsString());
        return;
      }
      if (probing) {
        probing = false;
        String status = response.getAsJsonObject("local").get("status").getAsString();
        if (!response.getAsJsonArray("agents").isEmpty()) {
          phase = "dispatch";
          error = "";
        } else if (!status.equals("local_checking")) fail(status);
        setChanged();
        return;
      }
      if (submitting) {
        submitting = false;
        phase = "working";
        setChanged();
        return;
      }
      if (!response.has("job")) {
        fail("craft_job_missing");
        return;
      }
      var record = response.getAsJsonObject("job");
      switch (record.get("state").getAsString()) {
        case "completed" -> {
          ReportArchive.get((ServerLevel) level).store(task.spec(), record);
          phase = "output";
          setChanged();
          releaseRecord(owner);
        }
        case "failed", "interrupted", "cancelled" ->
            fail(
                record.get("result_key").getAsString().isBlank()
                    ? "craft_execution_failed"
                    : record.get("result_key").getAsString());
        default -> {}
      }
      return;
    }
    if (phase.equals("new")) {
      probing = true;
      var query = new JsonObject();
      query.addProperty("target", target);
      pending = NativeAgents.dispatch(owner, "agents", query);
    } else if (phase.equals("dispatch")) {
      // Persist the dispatch intent. Reload queries the same job instead of replaying a model call.
      phase = "working";
      setChanged();
      submitting = true;
      var request = task.spec().request(job);
      request.addProperty("target", target);
      pending = NativeAgents.dispatch(owner, "submit", request);
    } else {
      JsonObject query = new JsonObject();
      query.addProperty("id", job);
      pending = NativeAgents.dispatch(owner, "job", query);
    }
  }

  private void fail(String key) {
    phase = "failed";
    error = key;
    setChanged();
  }

  private void releaseRecord(ServerPlayer owner) {
    if (owner == null || job.isBlank()) return;
    JsonObject request = new JsonObject();
    request.addProperty("id", job);
    NativeAgents.dispatch(owner, "release_job", request);
  }

  private void clear() {
    pattern = ItemStack.EMPTY;
    job = "";
    phase = "idle";
    error = "";
    pending = null;
    submitting = probing = false;
    ticks = 0;
    setChanged();
  }

  public void interact(Player player) {
    if (!(player instanceof ServerPlayer server) || !NativeAgents.allowed(server)) return;
    if (phase.equals("failed") && player.isShiftKeyDown()) {
      releaseRecord(server);
      if (!player.addItem(new ItemStack(Items.PAPER)))
        player.drop(new ItemStack(Items.PAPER), false);
      clear();
      player.displayClientMessage(
          Component.translatable("gui.appliedintelligence.craft_reset"), false);
      return;
    }
    player.displayClientMessage(
        Component.translatable(
            "gui.appliedintelligence.assembler." + (phase.equals("dispatch") ? "new" : phase)),
        false);
    if (!error.isBlank())
      player.displayClientMessage(
          Component.translatable("gui.appliedintelligence." + error), false);
    if (!job.isBlank()) player.displayClientMessage(Component.literal("Agent: " + job), false);
  }

  public void dropContents() {
    releaseRecord(owner());
    if (!pattern.isEmpty()) {
      ItemStack drop;
      try {
        drop =
            phase.equals("output")
                ? new TaskPattern(AEItemKey.of(pattern)).product()
                : new ItemStack(Items.PAPER);
      } catch (RuntimeException e) {
        drop = new ItemStack(Items.PAPER);
      }
      Block.popResource(level, worldPosition, drop);
    }
    clear();
  }

  @Override
  protected void saveAdditional(CompoundTag tag, HolderLookup.Provider lookup) {
    super.saveAdditional(tag, lookup);
    tag.putString("target", target);
    tag.putString("phase", phase);
    tag.putString("job", job);
    tag.putString("error", error);
    tag.putString("provider", provider.getName());
    if (!pattern.isEmpty()) tag.put("pattern", pattern.save(lookup));
  }

  @Override
  protected void loadAdditional(CompoundTag tag, HolderLookup.Provider lookup) {
    super.loadAdditional(tag, lookup);
    target = tag.contains("target") ? tag.getString("target") : "local";
    phase = tag.getString("phase");
    if (phase.isBlank()) phase = "idle";
    job = tag.getString("job");
    error = tag.getString("error");
    Direction direction = Direction.byName(tag.getString("provider"));
    provider = direction == null ? Direction.NORTH : direction;
    pattern = ItemStack.parseOptional(lookup, tag.getCompound("pattern"));
    // Never replay an accepted remote task on this computer after the transport is removed.
    if (!target.equals("local") && !phase.equals("idle") && !phase.equals("output")) {
      phase = "failed";
      error = "remote_disabled";
    }
  }
}
