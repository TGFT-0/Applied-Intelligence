package dev.tgft.appliedintelligence;

import appeng.api.AECapabilities;
import appeng.api.crafting.PatternDetailsHelper;
import dev.tgft.appliedintelligence.crafting.*;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.item.CreativeModeTabs;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.capabilities.RegisterCapabilitiesEvent;
import net.neoforged.neoforge.event.BuildCreativeModeTabContentsEvent;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

@Mod(AppliedIntelligence.ID)
public final class AppliedIntelligence {
  public static final String ID = "appliedintelligence";
  public static final DeferredRegister<Item> ITEMS = DeferredRegister.create(Registries.ITEM, ID);
  public static final DeferredHolder<Item, Item> SKILL =
      ITEMS.register("skill_card", () -> new CardItem(false));
  public static final DeferredHolder<Item, Item> TASK =
      ITEMS.register("task_pattern", () -> new CardItem(true));

  public static final DeferredRegister<Block> BLOCKS =
      DeferredRegister.create(Registries.BLOCK, ID);
  public static final DeferredRegister<BlockEntityType<?>> BLOCK_ENTITIES =
      DeferredRegister.create(Registries.BLOCK_ENTITY_TYPE, ID);
  public static final DeferredHolder<Block, AgentAssemblerBlock> ASSEMBLER =
      BLOCKS.register(
          "agent_assembler",
          () -> new AgentAssemblerBlock(BlockBehaviour.Properties.of().strength(3.0f, 6.0f)));
  public static final DeferredHolder<Item, Item> ASSEMBLER_ITEM =
      ITEMS.register("agent_assembler", () -> new AgentAssemblerItem(ASSEMBLER.get()));
  public static final DeferredHolder<Item, Item> REPORT =
      ITEMS.register("task_report", ReportItem::new);
  public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<AgentAssembler>>
      ASSEMBLER_ENTITY =
          BLOCK_ENTITIES.register(
              "agent_assembler",
              () -> BlockEntityType.Builder.of(AgentAssembler::new, ASSEMBLER.get()).build(null));

  public AppliedIntelligence(IEventBus bus) {
    BLOCKS.register(bus);
    BLOCK_ENTITIES.register(bus);
    PatternDetailsHelper.registerDecoder(TaskPattern.DECODER);
    bus.addListener(
        (RegisterCapabilitiesEvent event) ->
            event.registerBlockEntity(
                AECapabilities.CRAFTING_MACHINE, ASSEMBLER_ENTITY.get(), (be, side) -> be));
    bus.addListener(
        (BuildCreativeModeTabContentsEvent event) -> {
          if (event.getTabKey() == CreativeModeTabs.FUNCTIONAL_BLOCKS)
            event.accept(ASSEMBLER_ITEM.get());
        });
    ITEMS.register(bus);
    bus.addListener(Protocol::register);
  }
}
