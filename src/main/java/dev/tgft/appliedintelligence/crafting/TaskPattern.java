package dev.tgft.appliedintelligence.crafting;

import appeng.api.crafting.*;
import appeng.api.stacks.*;
import com.google.gson.*;
import dev.tgft.appliedintelligence.AppliedIntelligence;
import java.util.*;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.*;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.level.Level;

/** A real AE2 processing recipe: one paper -> one claimable, named work product. */
public final class TaskPattern implements IPatternDetails {
  public static final IPatternDetailsDecoder DECODER =
      new IPatternDetailsDecoder() {
        public boolean isEncodedPattern(ItemStack stack) {
          return stack.is(AppliedIntelligence.TASK.get());
        }

        public IPatternDetails decodePattern(AEItemKey key, Level level) {
          if (key == null || !key.is(AppliedIntelligence.TASK.get())) return null;
          try {
            return new TaskPattern(key);
          } catch (RuntimeException e) {
            return null;
          }
        }
      };
  private final AEItemKey definition;
  private final TaskSpec spec;
  private final List<GenericStack> outputs;
  private static final AEItemKey PAPER = AEItemKey.of(Items.PAPER);
  private static final IInput[] INPUTS = {
    new IInput() {
      public GenericStack[] getPossibleInputs() {
        return new GenericStack[] {new GenericStack(PAPER, 1)};
      }

      public long getMultiplier() {
        return 1;
      }

      public boolean isValid(AEKey key, Level level) {
        return PAPER.equals(key);
      }

      public AEKey getRemainingKey(AEKey key) {
        return null;
      }
    }
  };

  public TaskPattern(AEItemKey definition) {
    this.definition = definition;
    var tag =
        definition.toStack().getOrDefault(DataComponents.CUSTOM_DATA, CustomData.EMPTY).copyTag();
    var skills = new ArrayList<String>();
    for (var value : JsonParser.parseString(tag.getString("skills")).getAsJsonArray())
      skills.add(value.getAsString());
    String name = tag.getString("name");
    if (name.isBlank()) name = definition.toStack().getHoverName().getString();
    spec = new TaskSpec(name, tag.getString("prompt"), skills);
    outputs = List.of(new GenericStack(AEItemKey.of(product()), 1));
  }

  public TaskSpec spec() {
    return spec;
  }

  public ItemStack product() {
    ItemStack stack = new ItemStack(AppliedIntelligence.REPORT.get());
    var tag = new net.minecraft.nbt.CompoundTag();
    tag.putString("recipe", spec.key());
    tag.putString("name", spec.name());
    stack.set(DataComponents.CUSTOM_DATA, CustomData.of(tag));
    stack.set(
        DataComponents.CUSTOM_NAME,
        Component.translatable("item.appliedintelligence.task_report.named", spec.name()));
    return stack;
  }

  public AEItemKey getDefinition() {
    return definition;
  }

  public IInput[] getInputs() {
    return INPUTS;
  }

  public List<GenericStack> getOutputs() {
    return outputs;
  }

  public boolean supportsPushInputsToExternalInventory() {
    return false;
  }

  public boolean equals(Object obj) {
    return obj instanceof TaskPattern other && definition.equals(other.definition);
  }

  public int hashCode() {
    return definition.hashCode();
  }
}
