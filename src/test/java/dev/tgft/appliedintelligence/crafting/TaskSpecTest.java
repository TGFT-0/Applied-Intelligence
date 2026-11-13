package dev.tgft.appliedintelligence.crafting;

import static org.junit.jupiter.api.Assertions.*;

import java.util.*;
import org.junit.jupiter.api.Test;

class TaskSpecTest {
  @Test
  void recipeIdentitySurvivesReconstructionAndSeparatesDifferentProducts() {
    var spec = new TaskSpec("报告", "分析目录", List.of("中文", "只读"));
    assertEquals(spec.key(), new TaskSpec("报告", "分析目录", List.of("中文", "只读")).key());
    assertNotEquals(spec.key(), new TaskSpec("报告2", "分析目录", spec.skills()).key());
    assertNotEquals(spec.key(), new TaskSpec("报告", "新任务", spec.skills()).key());
    assertNotEquals(spec.key(), new TaskSpec("报告", "分析目录", List.of("只读", "中文")).key());
  }

  @Test
  void requestFreezesSkillsAndPreservesNamedTask() {
    var skills = new ArrayList<>(List.of("snapshot"));
    var spec = new TaskSpec("named task", "prompt", skills);
    skills.set(0, "changed");
    var request = spec.request("id");
    assertEquals("snapshot", request.getAsJsonArray("skills").get(0).getAsString());
    assertEquals("named task", request.get("title").getAsString());
    assertThrows(UnsupportedOperationException.class, () -> spec.skills().add("bad"));
  }

  @Test
  void rejectsInvalidNamesAndOversizedSerializedRecipes() {
    assertThrows(IllegalArgumentException.class, () -> new TaskSpec(" ", "task", List.of()));
    assertThrows(
        IllegalArgumentException.class, () -> new TaskSpec("line\nbreak", "task", List.of()));
    assertThrows(
        IllegalArgumentException.class,
        () -> new TaskSpec("name", "task", Collections.nCopies(10, "skill")));
    assertThrows(
        IllegalArgumentException.class,
        () -> new TaskSpec("name", "\\".repeat(8000), List.of("\\".repeat(8000))));
  }
}
