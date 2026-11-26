package dev.tgft.appliedintelligence.crafting;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class AgentAssemblerTest {
  @Test
  void savedPhasesCannotAuthorizeNewAgentWork() {
    assertFalse(AgentAssembler.canRestorePhase("new"));
    assertFalse(AgentAssembler.canRestorePhase("dispatch"));
    assertFalse(AgentAssembler.canRestorePhase("unknown"));
    for (String phase : new String[] {"idle", "working", "output", "failed"})
      assertTrue(AgentAssembler.canRestorePhase(phase));
  }
}
