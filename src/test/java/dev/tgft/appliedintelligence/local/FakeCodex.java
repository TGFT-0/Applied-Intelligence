package dev.tgft.appliedintelligence.local;

import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/** A real child-process fixture; never contacts Codex or a model provider. */
public final class FakeCodex {
  private static String quote(String value) {
    return "\""
        + value
            .replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("\n", "\\n")
            .replace("\r", "\\r")
        + "\"";
  }

  public static void main(String[] args) throws Exception {
    if (args.length == 1 && args[0].equals("--sleep")) {
      Thread.sleep(60000);
      return;
    }
    if (args.length > 0 && args[0].equals("app-server")) {
      FakeAppServer.main(args);
      return;
    }
    Path root = Path.of(System.getProperty("ai.test.root"));
    if (args[0].equals("--version")) {
      System.out.println("codex-test 1.0");
      return;
    }
    if (args[0].equals("login")) {
      System.exit(Files.exists(root.resolve("logged-out")) ? 1 : 0);
    }
    List<String> command = List.of(args);
    if (!command.getFirst().equals("exec")
        || !command.getLast().equals("-")
        || !command.get(command.indexOf("--sandbox") + 1).equals("read-only")
        || command.contains("--dangerously-bypass-approvals-and-sandbox")
        || System.getenv().keySet().stream().anyMatch(key -> key.startsWith("AI_BRIDGE_")))
      System.exit(9);
    Files.writeString(root.resolve("last-args.txt"), String.join("\n", command));
    String prompt = new String(System.in.readAllBytes(), StandardCharsets.UTF_8);
    Files.writeString(
        root.resolve("calls"), "run\n", StandardOpenOption.CREATE, StandardOpenOption.APPEND);
    System.out.println("{\"type\":\"thread.started\",\"thread_id\":\"fixture-session\"}");
    System.out.println("{\"type\":\"turn.started\"}");
    System.out.flush();
    boolean progressFixture =
        prompt.contains("PROGRESS_FIXTURE") || Files.exists(root.resolve("progress-fixture"));
    if (progressFixture) {
      System.out.println("{\"type\":\"context.update\",\"model_context_window\":128000}");
      for (int done = 0; done <= 3; done++) {
        if (!Files.exists(root.resolve("usage-final-only"))) {
          int input = done == 0 ? 12000 : 16480,
              cached = done == 0 ? 8000 : 12000,
              output = done == 0 ? 640 : 2160;
          System.out.println(
              "{\"type\":\"token_count\",\"info\":{\"total_token_usage\":{\"input_tokens\":"
                  + input
                  + ",\"cached_input_tokens\":"
                  + cached
                  + ",\"output_tokens\":"
                  + output
                  + "}}}");
        }
        String[] names = {"读取资料", "执行任务", "整理结果"};
        StringBuilder event =
            new StringBuilder(
                "{\"type\":\"item.updated\",\"item\":{\"id\":\"plan\",\"type\":\"todo_list\",\"items\":[");
        for (int i = 0; i < 3; i++) {
          if (i > 0) event.append(',');
          event
              .append("{\"text\":")
              .append(quote(names[i]))
              .append(",\"completed\":")
              .append(i < done)
              .append('}');
        }
        System.out.println(event.append("]}}"));
        System.out.flush();
        if (done < 3 && Files.exists(root.resolve("progress-slow"))) Thread.sleep(15000);
      }
    }
    if (prompt.contains("CHILD")) {
      Process child =
          new ProcessBuilder(
                  Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                  "-cp",
                  System.getProperty("java.class.path"),
                  FakeCodex.class.getName(),
                  "--sleep")
              .start();
      Files.writeString(root.resolve("child.pid"), String.valueOf(child.pid()));
      Thread.sleep(60000);
    }
    if (prompt.contains("SLOW")) Thread.sleep(60000);
    if (prompt.contains("FAIL")) System.exit(3);
    if (prompt.contains("ERROR_THEN_COMPLETE")) System.out.println("{\"type\":\"turn.failed\"}");
    if (prompt.contains("OVERSIZED")) System.out.println("x".repeat(100000));
    System.out.println(
        "{\"type\":\"item.started\",\"item\":{\"type\":\"reasoning\",\"text\":\"private-reasoning-fixture\"}}");
    if (!prompt.contains("NO_MESSAGE"))
      System.out.println(
          "{\"type\":\"item.completed\",\"item\":{\"type\":\"agent_message\",\"text\":"
              + quote(progressFixture ? "资料读取完成。任务已执行，结果已整理。" : prompt)
              + "}}");
    System.out.println(
        progressFixture
            ? "{\"type\":\"turn.completed\",\"usage\":{\"input_tokens\":16480,\"cached_input_tokens\":12000,\"output_tokens\":2160}}"
            : "{\"type\":\"turn.completed\"}");
  }
}
