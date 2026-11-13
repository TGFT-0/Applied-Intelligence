package dev.tgft.appliedintelligence.local;

import java.util.*;

/** Stable configuration identifiers, independent of translated UI labels and remote routes. */
public enum AgentBackend {
  CODEX("codex", "Codex", "codex", true, true),
  DEEPSEEK("deepseek", "DeepSeek Harness", "dsh", false, false),
  PI("pi", "Pi", "pi", true, false),
  OPENCODE("opencode", "OpenCode 2", "opencode", true, true);
  public final String id, label, executable;
  public final boolean steering, questions;

  AgentBackend(String id, String label, String executable, boolean steering, boolean questions) {
    this.id = id;
    this.label = label;
    this.executable = executable;
    this.steering = steering;
    this.questions = questions;
  }

  @Override
  public String toString() {
    return label;
  }

  public static AgentBackend of(String id) {
    if (id == null || id.isBlank()) return CODEX;
    return Arrays.stream(values())
        .filter(p -> p.id.equals(id))
        .findFirst()
        .orElseThrow(() -> new IllegalArgumentException("unknown provider"));
  }

  public static String author(String agent) {
    return Arrays.stream(values())
        .filter(p -> (p.id + "-local").equals(agent))
        .map(p -> p.label)
        .findFirst()
        .orElse("Agent");
  }

  List<String> arguments(int context, String patch) {
    return switch (this) {
      case CODEX ->
          context > 0
              ? List.of(
                  "app-server",
                  "-c",
                  "features.default_mode_request_user_input=true",
                  "-c",
                  "model_context_window=" + context)
              : List.of("app-server", "-c", "features.default_mode_request_user_input=true");
      case PI ->
          List.of(
              "--mode",
              "rpc",
              "--no-session",
              "--tools",
              "read,grep,find,ls",
              "--no-extensions",
              "--no-mcp",
              "--no-skills",
              "--no-prompt-templates",
              "--no-themes");
      case DEEPSEEK -> List.of("acp", "--patch", Objects.requireNonNull(patch));
      case OPENCODE -> List.of("serve", "--stdio", "--hostname", "127.0.0.1", "--port", "0");
    };
  }
}
