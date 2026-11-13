package dev.tgft.appliedintelligence.local;

import com.google.gson.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

/** One integrated world's local Agent. No network listener or credentials of its own. */
public final class LocalCodex implements AutoCloseable {
  private static final Gson JSON = new GsonBuilder().setPrettyPrinting().create();
  private static final int MAX_JOBS = 100;
  private final Object lock = new Object();
  private final Path configFile, jobsFile, defaultWorkspace;
  private final ImageAssets images;
  private final ExecutorService worker = Executors.newSingleThreadExecutor(daemon("ai-codex-task"));
  private final ExecutorService checker =
      Executors.newSingleThreadExecutor(daemon("ai-codex-check"));
  private final ScheduledExecutorService timer =
      Executors.newSingleThreadScheduledExecutor(daemon("ai-codex-timeout"));
  private final Set<Process> processes = new HashSet<>();
  private final Map<String, Process> activeJobs = new HashMap<>();
  private final LinkedHashMap<String, Job> jobs = new LinkedHashMap<>();
  private final Duration taskTimeout;
  private Config config;
  private String status = "local_not_configured", version = "";
  private boolean checking, closed, storageError;
  private long generation;

  public record Config(String executable, String workspace, int contextTokens, String provider) {
    public Config(String executable, String workspace, int contextTokens) {
      this(executable, workspace, contextTokens, "codex");
    }

    public Config(String executable, String workspace) {
      this(executable, workspace, 0);
    }
  }

  private record Request(String prompt, List<String> skills) {}

  private static final class Event {
    long seq;
    double time;
    String data;

    Event(long seq, String type) {
      this.seq = seq;
      time = now();
      JsonObject value = new JsonObject();
      value.addProperty("type", type);
      data = value.toString();
    }
  }

  private static final class Job {
    String id, request, state = "queued", result = "", result_key = "", session = "";
    String agent = "codex-local";
    String title;
    String recipe = "";
    boolean retained;
    JobProgress progress = new JobProgress();
    AgentQuestion question;
    String turn = "";
    Map<String, SteerReceipt> steering = new LinkedHashMap<>();
    Map<String, String> answerReceipts = new LinkedHashMap<>();
    transient AgentSession server;
    transient JsonElement rpcQuestion;
    transient long deadline, waitingSince;
    double created = now(), updated = created;
    List<Event> events = new ArrayList<>();
  }

  private static final class SteerReceipt {
    String digest, turn, state = "pending", error = "";

    SteerReceipt(String digest, String turn) {
      this.digest = digest;
      this.turn = turn;
    }
  }

  public static final class Failure extends RuntimeException {
    public final String key;

    Failure(String key) {
      super(key);
      this.key = key;
    }
  }

  private static ThreadFactory daemon(String name) {
    return task -> {
      Thread thread = new Thread(task, name);
      thread.setDaemon(true);
      return thread;
    };
  }

  private static double now() {
    return System.currentTimeMillis() / 1000.0;
  }

  public LocalCodex(Path configFile, Path jobsFile, Path defaultWorkspace) {
    this(configFile, jobsFile, defaultWorkspace, Duration.ofMinutes(10));
  }

  public LocalCodex(Path configFile, Path jobsFile, Path defaultWorkspace, Duration taskTimeout) {
    this.configFile = configFile;
    this.jobsFile = jobsFile;
    this.images = new ImageAssets(jobsFile.toAbsolutePath().getParent().resolve("images"));
    this.defaultWorkspace = defaultWorkspace.toAbsolutePath().normalize();
    this.taskTimeout = taskTimeout;
    try {
      if (Files.exists(configFile)) {
        if (Files.size(configFile) > 16384) throw new IOException();
        config = validate(JSON.fromJson(Files.readString(configFile), Config.class));
      }
    } catch (Exception e) {
      status = "local_config_invalid";
    }
    try {
      if (Files.exists(jobsFile)) {
        if (Files.size(jobsFile) > 16 * 1024 * 1024) throw new IOException();
        Job[] saved = JSON.fromJson(Files.readString(jobsFile), Job[].class);
        if (saved == null || saved.length > MAX_JOBS) throw new IOException();
        for (Job job : saved) {
          UUID.fromString(job.id);
          if (job.request == null || job.state == null || job.events == null)
            throw new IOException();
          if (job.state.equals("running") || job.state.equals("queued")) {
            job.question = null;
            job.state = "interrupted";
            job.result_key = "local_interrupted";
            job.updated = now();
          }
          if (job.turn == null) job.turn = "";
          if (job.steering == null) job.steering = new LinkedHashMap<>();
          job.steering.values().stream()
              .filter(r -> r.state.equals("pending"))
              .forEach(
                  r -> {
                    r.state = "unknown";
                    r.error = "steer_unknown";
                  });
          if (job.answerReceipts == null) job.answerReceipts = new LinkedHashMap<>();
          if (job.progress == null) job.progress = new JobProgress();
          if (job.title == null)
            job.title = jobTitle(JSON.fromJson(job.request, Request.class).prompt());
          jobs.put(job.id, job);
        }
        persist();
      }
    } catch (Exception e) {
      storageError = true;
    }
    if (config != null) startCheck();
  }

  private Config validate(Config value) {
    if (value == null
        || value.executable() == null
        || value.workspace() == null
        || value.executable().isBlank()
        || value.executable().length() > 2048
        || value.executable().contains("\n")
        || value.executable().contains("\r")
        || value.workspace().isBlank()
        || value.workspace().length() > 2048
        || value.contextTokens() < 0
        || value.contextTokens() > 2_000_000) throw new Failure("local_config_invalid");
    return new Config(
        value.executable().trim(),
        Path.of(value.workspace()).toAbsolutePath().normalize().toString(),
        value.contextTokens(),
        AgentBackend.of(value.provider()).id);
  }

  /** Called off the Minecraft server thread. Only its private-world owner may reach this method. */
  public JsonObject dispatch(String action, JsonObject data) {
    synchronized (lock) {
      if (closed) throw new Failure("local_closed");
      switch (action) {
        case "local_configure" -> {
          Config next =
              validate(
                  new Config(
                      data.get("executable").getAsString(),
                      data.get("workspace").getAsString(),
                      data.has("context_tokens") ? data.get("context_tokens").getAsInt() : 0,
                      data.has("provider") ? data.get("provider").getAsString() : "codex"));
          try {
            Files.createDirectories(Path.of(next.workspace()));
            if (!Files.isDirectory(Path.of(next.workspace()))) throw new IOException();
            atomicWrite(configFile, JSON.toJson(next));
          } catch (IOException e) {
            throw new Failure("local_config_invalid");
          }
          config = next;
          generation++;
          checking = false;
          version = "";
          startCheck();
        }
        case "local_check" -> startCheck();
        case "submit" -> {
          return submit(data);
        }
        case "steer_job" -> {
          return steer(data);
        }
        case "answer_job" -> {
          return answer(data);
        }
        case "cancel_job" -> {
          Job job = jobs.get(data.get("id").getAsString());
          if (job != null && (job.state.equals("running") || job.state.equals("queued"))) {
            job.question = null;
            job.state = "cancelled";
            job.result = "";
            job.result_key = "task_cancelled";
            job.updated = now();
            event(job, "local.cancelled");
            persist();
            Process process = activeJobs.get(job.id);
            if (process != null) timer.execute(() -> stop(process));
          }
          JsonObject response = new JsonObject();
          if (job != null) response.add("job", publicJob(job));
          return response;
        }
        case "release_job" -> {
          Job job = jobs.get(data.get("id").getAsString());
          if (job != null && job.retained) {
            job.retained = false;
            persist();
          }
          return new JsonObject();
        }
        case "image_chunk" -> {
          try {
            return images.chunk(data);
          } catch (IOException | RuntimeException e) {
            throw new Failure("image_unavailable");
          }
        }
        case "job" -> {
          Job job = jobs.get(data.get("id").getAsString());
          JsonObject response = new JsonObject();
          if (job != null) response.add("job", publicJob(job));
          return response;
        }
        case "agents", "jobs", "status_jobs", "local_settings" -> {}
        default -> throw new Failure("unknown_action");
      }
      JsonObject response = snapshot();
      if (action.equals("local_configure")) response.addProperty("local_saved", true);
      if (action.equals("status_jobs")) {
        if (storageError) throw new Failure("local_storage_error");
        List<Job> all = new ArrayList<>(jobs.values());
        Collections.reverse(all);
        JsonArray list = new JsonArray();
        for (Job job : all) {
          JsonObject summary = new JsonObject();
          summary.addProperty("id", job.id);
          summary.addProperty("agent", job.agent);
          summary.addProperty("title", summaryTitle(job.title));
          summary.addProperty("recipe", job.recipe);
          summary.addProperty("started", job.progress.started);
          summary.addProperty("context_window", job.progress.contextWindow);
          if (job.progress.usage != null) summary.add("usage", JSON.toJsonTree(job.progress.usage));
          if (!job.events.isEmpty())
            summary.addProperty(
                "stage",
                JsonParser.parseString(job.events.getLast().data)
                    .getAsJsonObject()
                    .get("type")
                    .getAsString());
          summary.addProperty("waiting_input", job.question != null);
          summary.addProperty("state", job.state);
          summary.addProperty("created", job.created);
          summary.addProperty("updated", job.updated);
          if (data.has("progress_only")) {
            summary.remove("agent");
            summary.remove("title");
          }
          list.add(summary);
        }
        response.add("jobs", list);
        response.getAsJsonObject("local").remove("executable");
        response.getAsJsonObject("local").remove("workspace");
        Job selected = data.has("job") ? jobs.get(data.get("job").getAsString()) : null;
        if (data.has("recipe"))
          selected =
              all.stream()
                  .filter(j -> Objects.equals(j.recipe, data.get("recipe").getAsString()))
                  .min(
                      Comparator.comparingInt(
                          j -> j.state.equals("running") ? 0 : j.state.equals("queued") ? 1 : 2))
                  .orElse(null);
        if (selected == null && !data.has("recipe"))
          selected =
              all.stream()
                  .filter(j -> j.state.equals("running"))
                  .findFirst()
                  .orElseGet(
                      () ->
                          all.stream()
                              .filter(j -> j.state.equals("queued"))
                              .findFirst()
                              .orElse(all.isEmpty() ? null : all.getFirst()));
        if (selected != null && !data.has("summaries_only")) {
          JsonObject detail = publicJob(selected);
          if (data.has("progress_only")) {
            detail.remove("result");
            detail.remove("result_key");
            detail.remove("events");
            detail.remove("session");
          }
          response.add("selected_job", detail);
        }
      }
      if (action.equals("jobs")) {
        if (storageError) throw new Failure("local_storage_error");
        JsonArray recent = new JsonArray();
        List<Job> all = new ArrayList<>(jobs.values());
        for (int i = all.size() - 1; i >= Math.max(0, all.size() - 3); i--)
          recent.add(publicJob(all.get(i)));
        response.add("jobs", recent);
      }
      return response;
    }
  }

  private JsonObject snapshot() {
    JsonObject response = new JsonObject(), local = new JsonObject();
    local.addProperty("configured", config != null);
    local.addProperty("provider", config == null ? "codex" : config.provider());
    local.addProperty("executable", config == null ? "codex" : config.executable());
    local.addProperty(
        "workspace", config == null ? defaultWorkspace.toString() : config.workspace());
    local.addProperty("status", status);
    local.addProperty("checking", checking);
    local.addProperty("context_tokens", config == null ? 0 : config.contextTokens());
    local.addProperty("version", version);
    response.add("local", local);
    JsonArray agents = new JsonArray();
    if (config != null && status.equals("local_ready")) {
      JsonObject agent = new JsonObject();
      agent.addProperty("id", config.provider() + "-local");
      agent.addProperty("name", AgentBackend.of(config.provider()).label);
      agent.addProperty("kind", "local");
      agents.add(agent);
    }
    response.add("agents", agents);
    return response;
  }

  private static String summaryTitle(String value) {
    String clean = value.replaceAll("[\\p{Cntrl}\\s]+", " ");
    return clean.substring(
        0, clean.offsetByCodePoints(0, Math.min(32, clean.codePointCount(0, clean.length()))));
  }

  private static String jobTitle(String value) {
    String title = value.replaceAll("\\s+", " ").trim();
    int end = title.offsetByCodePoints(0, Math.min(80, title.codePointCount(0, title.length())));
    return title.substring(0, end);
  }

  private JsonObject publicJob(Job job) {
    JsonObject result = JSON.toJsonTree(job).getAsJsonObject();
    result.remove("request");
    var capabilities = new JsonObject();
    var backend =
        Arrays.stream(AgentBackend.values())
            .filter(p -> (p.id + "-local").equals(job.agent))
            .findFirst()
            .orElse(AgentBackend.CODEX);
    capabilities.addProperty("steering", backend.steering);
    capabilities.addProperty("questions", backend.questions);
    result.add("capabilities", capabilities);
    try {
      result.add("images", images.manifest(job.id));
    } catch (IOException e) {
      result.add("images", new JsonArray());
    }
    result.remove("answerReceipts");
    result.remove("steering");
    var receipts = new JsonArray();
    job.steering.entrySet().stream()
        .skip(Math.max(0, job.steering.size() - 8))
        .forEach(entry -> receipts.add(steerReply(entry.getKey(), entry.getValue())));
    result.add("steering", receipts);
    if (!job.answerReceipts.isEmpty())
      result.addProperty(
          "answered_request", new ArrayList<>(job.answerReceipts.keySet()).getLast());
    return result;
  }

  private JsonObject submit(JsonObject data) {
    if (storageError) throw new Failure("local_storage_error");
    String id = UUID.fromString(data.get("id").getAsString()).toString();
    if (!Set.of("auto", config == null ? "codex-local" : config.provider() + "-local")
        .contains(data.get("agent").getAsString())) throw new Failure("agent_unavailable");
    String prompt = data.get("prompt").getAsString();
    if (prompt.isBlank() || prompt.length() > 8000) throw new Failure("invalid_text");
    JsonArray values = data.has("skills") ? data.getAsJsonArray("skills") : new JsonArray();
    if (values.size() > 9) throw new Failure("too_many_skills");
    List<String> skills = new ArrayList<>();
    for (JsonElement value : values) {
      if (!value.isJsonPrimitive()
          || !value.getAsJsonPrimitive().isString()
          || value.getAsString().length() > 8000) throw new Failure("invalid_skills");
      skills.add(value.getAsString());
    }
    String request = JSON.toJson(new Request(prompt, List.copyOf(skills)));
    if (request.getBytes(StandardCharsets.UTF_8).length > 30000)
      throw new Failure("request_too_large");
    Job existing = jobs.get(id);
    if (existing != null) {
      if (!existing.request.equals(request)) throw new Failure("request_conflict");
    } else {
      if (config == null || !status.equals("local_ready")) throw new Failure("local_not_ready");
      if (jobs.size() >= MAX_JOBS) {
        String removable =
            jobs.values().stream()
                .filter(j -> !j.retained && !j.state.equals("queued") && !j.state.equals("running"))
                .map(j -> j.id)
                .findFirst()
                .orElseThrow(() -> new Failure("local_queue_full"));
        jobs.remove(removable);
        try {
          images.discard(removable);
        } catch (IOException ignored) {
        }
      }
      Job job = new Job();
      job.agent = config.provider() + "-local";
      job.id = id;
      job.request = request;
      job.title = jobTitle(data.has("title") ? data.get("title").getAsString() : prompt);
      job.recipe =
          data.has("recipe") && data.get("recipe").getAsString().matches("[0-9a-f]{64}")
              ? data.get("recipe").getAsString()
              : "";
      job.progress.contextWindow = config.contextTokens();
      job.retained = data.has("retain") && data.get("retain").getAsBoolean();
      jobs.put(id, job);
      persist();
      Config captured = config;
      worker.execute(() -> run(job, captured));
    }
    JsonObject response = snapshot();
    response.addProperty("id", id);
    return response;
  }

  private void startCheck() {
    synchronized (lock) {
      if (closed || checking) return;
      if (config == null) {
        status = "local_not_configured";
        return;
      }
      checking = true;
      status = "local_checking";
      Config captured = config;
      long revision = generation;
      checker.execute(
          () -> {
            String result = "local_cli_missing", detected = "";
            try {
              List<String> prefix =
                  commandPrefix(captured.executable(), AgentBackend.of(captured.provider()));
              Capture ver = capture(prefix, List.of("--version"));
              if (ver.timedOut) result = "local_check_timeout";
              else if (ver.code != 0) result = "local_cli_invalid";
              else {
                detected =
                    ver.text
                        .lines()
                        .map(String::strip)
                        .filter(
                            line ->
                                line.matches("(?i)(?:[a-z][a-z0-9_-]*\\s+)?v?[0-9]+\\.[0-9]+.*"))
                        .findFirst()
                        .orElse("")
                        .replaceAll("[\\p{Cntrl}]", "");
                if (detected.length() > 100) detected = detected.substring(0, 100);
                if (detected.isBlank()) result = "local_cli_invalid";
                else if (!captured.provider().equals("codex")) {
                  if (captured.provider().equals("opencode")
                      && !(detected.startsWith("2.")
                          || detected.toLowerCase(Locale.ROOT).startsWith("opencode 2.")
                          || detected.toLowerCase(Locale.ROOT).startsWith("opencode v2.")))
                    result = "provider_version_unsupported";
                  else result = "local_ready";
                } else {
                  Capture login = capture(prefix, List.of("login", "status"));
                  result =
                      login.timedOut
                          ? "local_check_timeout"
                          : login.code == 0 ? "local_ready" : "local_login_required";
                }
              }
            } catch (Failure e) {
              result = e.key;
            } catch (Exception e) {
              result = "local_cli_missing";
            }
            synchronized (lock) {
              if (!closed && revision == generation) {
                checking = false;
                status = result;
                version = detected;
              }
            }
          });
    }
  }

  /**
   * Resolve the executable without interpreting user paths through a shell, including npm's Windows
   * shim.
   */
  static List<String> commandPrefix(String executable, AgentBackend provider) throws IOException {
    return commandPrefix(
        executable,
        provider,
        System.getProperty("os.name").toLowerCase(Locale.ROOT).contains("win"));
  }

  static List<String> commandPrefix(String executable, AgentBackend provider, boolean windows)
      throws IOException {
    if (provider == AgentBackend.CODEX || !windows) return commandPrefix(executable, windows);
    Path path = findExecutable(executable, true);
    String name = path.getFileName().toString().toLowerCase(Locale.ROOT);
    if (!name.endsWith(".cmd") && !name.endsWith(".bat")) return List.of(path.toString());
    if (provider == AgentBackend.OPENCODE) {
      Path binary = path.getParent().resolve("node_modules/@opencode/cli/bin/opencode.exe");
      if (!Files.isRegularFile(binary)) throw new Failure("local_launcher_unsupported");
      return List.of(binary.toString());
    }
    String entry =
        switch (provider) {
          case PI -> "node_modules/@earendil-works/pi-coding-agent/dist/bundle/cli.js";
          case DEEPSEEK -> "node_modules/@deepseek-ai/dsh/lib/bin.js";
          default -> throw new Failure("local_launcher_unsupported");
        };
    Path script = path.getParent().resolve(entry);
    if (!Files.isRegularFile(script)) throw new Failure("local_launcher_unsupported");
    Path node = path.getParent().resolve("node.exe");
    if (!Files.isRegularFile(node)) node = findExecutable("node", true);
    return List.of(node.toString(), script.toString());
  }

  static List<String> commandPrefix(String executable) throws IOException {
    boolean windows = System.getProperty("os.name").toLowerCase(Locale.ROOT).contains("win");
    return commandPrefix(executable, windows);
  }

  static List<String> commandPrefix(String executable, boolean windows) throws IOException {
    Path path = findExecutable(executable, windows);
    String name = path.getFileName().toString().toLowerCase(Locale.ROOT);
    if (windows && (name.endsWith(".cmd") || name.endsWith(".bat"))) {
      Path script = path.getParent().resolve("node_modules/@openai/codex/bin/codex.js");
      if (!Files.isRegularFile(script)) throw new Failure("local_launcher_unsupported");
      Path node = path.getParent().resolve("node.exe");
      if (!Files.isRegularFile(node)) node = findExecutable("node", true);
      return List.of(node.toString(), script.toString());
    }
    return List.of(path.toString());
  }

  private static Path findExecutable(String value, boolean windows) throws IOException {
    Path supplied = Path.of(value);
    if (supplied.isAbsolute() || supplied.getParent() != null) {
      if (!Files.isRegularFile(supplied)) throw new IOException("missing executable");
      return supplied.toAbsolutePath().normalize();
    }
    String path = System.getenv().getOrDefault("PATH", "");
    List<String> suffixes = windows ? List.of("", ".exe", ".cmd", ".bat") : List.of("");
    for (String folder : path.split(java.util.regex.Pattern.quote(File.pathSeparator))) {
      if (folder.isBlank()) continue;
      for (String suffix : suffixes) {
        Path candidate = Path.of(folder).resolve(value + suffix);
        if (Files.isRegularFile(candidate) && (windows || Files.isExecutable(candidate)))
          return candidate.toAbsolutePath();
      }
    }
    throw new IOException("missing executable");
  }

  private record Capture(int code, String text, boolean timedOut) {}

  private Capture capture(List<String> prefix, List<String> args) throws Exception {
    List<String> command = new ArrayList<>(prefix);
    command.addAll(args);
    AtomicBoolean expired = new AtomicBoolean();
    Process process = start(command, true);
    ScheduledFuture<?> timeout =
        timer.schedule(
            () -> {
              expired.set(true);
              stop(process);
            },
            10,
            TimeUnit.SECONDS);
    try {
      process.getOutputStream().close();
      StringBuilder output = new StringBuilder();
      try (Reader reader =
          new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8)) {
        char[] buffer = new char[1024];
        int n;
        while ((n = reader.read(buffer)) >= 0)
          if (output.length() < 8192) output.append(buffer, 0, Math.min(n, 8192 - output.length()));
      }
      return new Capture(process.waitFor(), output.toString(), expired.get());
    } finally {
      timeout.cancel(false);
      release(process);
    }
  }

  private Process start(List<String> command, boolean captureErrors) throws IOException {
    return start(command, captureErrors, null, Map.of());
  }

  private Process start(
      List<String> command, boolean captureErrors, Path cwd, Map<String, String> environment)
      throws IOException {
    synchronized (lock) {
      if (closed) throw new Failure("local_closed");
      ProcessBuilder builder = new ProcessBuilder(command);
      builder.environment().keySet().removeIf(key -> key.startsWith("AI_BRIDGE_"));
      builder.environment().putAll(environment);
      if (cwd != null) builder.directory(cwd.toFile());
      if (captureErrors) builder.redirectErrorStream(true);
      else builder.redirectError(ProcessBuilder.Redirect.DISCARD);
      Process process = builder.start();
      processes.add(process);
      return process;
    }
  }

  private void release(Process process) {
    synchronized (lock) {
      processes.remove(process);
    }
  }

  private static void stop(Process process) {
    synchronized (process) {
      List<ProcessHandle> children = process.descendants().toList();
      for (int i = children.size() - 1; i >= 0; i--) children.get(i).destroyForcibly();
      // Let the still-living parent reap its children before stopping it. Otherwise a
      // native CLI wrapper may leave zombies behind on hosts whose PID 1 does not reap.
      long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(300);
      for (ProcessHandle child : children) {
        long remaining = deadline - System.nanoTime();
        if (remaining <= 0) break;
        try {
          child.onExit().get(remaining, TimeUnit.NANOSECONDS);
        } catch (InterruptedException e) {
          Thread.currentThread().interrupt();
          break;
        } catch (ExecutionException | TimeoutException ignored) {
        }
      }
      process.destroyForcibly();
    }
  }

  private static JsonObject steerReply(String id, SteerReceipt receipt) {
    var response = new JsonObject();
    response.addProperty("steer_request", id);
    response.addProperty("steer_state", receipt.state);
    if (!receipt.error.isBlank()) response.addProperty("error_key", receipt.error);
    return response;
  }

  private JsonObject steer(JsonObject data) {
    String id = UUID.fromString(data.get("id").getAsString()).toString();
    String requestId = UUID.fromString(data.get("request").getAsString()).toString();
    String turn = data.get("turn").getAsString();
    var value = data.getAsJsonPrimitive("text");
    if (value == null || !value.isString()) throw new Failure("invalid_text");
    String text = value.getAsString().trim();
    if (text.isBlank()
        || text.length() > 4000
        || text.getBytes(StandardCharsets.UTF_8).length > 16000) throw new Failure("invalid_text");
    var payload = new JsonObject();
    payload.addProperty("turn", turn);
    payload.addProperty("text", text);
    String digest = AgentQuestion.digest(payload);
    Job job = jobs.get(id);
    if (job == null) throw new Failure("steer_inactive");
    var existing = job.steering.get(requestId);
    if (existing != null) {
      if (!existing.digest.equals(digest)) throw new Failure("request_conflict");
      return steerReply(requestId, existing);
    }
    if (!job.state.equals("running")
        || job.server == null
        || job.turn.isBlank()
        || !job.turn.equals(turn)) throw new Failure("steer_inactive");
    if (job.question != null) throw new Failure("steer_answer_first");
    if (job.steering.values().stream().anyMatch(r -> r.state.equals("pending")))
      throw new Failure("steer_pending");
    // Keep every receipt for this live turn: a retry must never be forwarded a second time.
    if (job.steering.size() >= 64) throw new Failure("steer_limit");
    var receipt = new SteerReceipt(digest, turn);
    job.steering.put(requestId, receipt);
    persist();
    job.server
        .steer(requestId, turn, text)
        .thenAccept(
            outcome -> {
              synchronized (lock) {
                receipt.state = outcome.state();
                receipt.error = outcome.error();
                event(
                    job,
                    outcome.state().equals("accepted")
                        ? "steering.accepted"
                        : "steering.unconfirmed");
                if (!closed) persist();
              }
            });
    return steerReply(requestId, receipt);
  }

  private JsonObject answer(JsonObject data) {
    Job job = jobs.get(data.get("id").getAsString());
    String request = data.get("request").getAsString();
    JsonObject answers = data.getAsJsonObject("answers");
    String digest;
    try {
      digest = AgentQuestion.digest(answers);
    } catch (RuntimeException e) {
      throw new Failure("question_invalid_answer");
    }
    if (job == null) throw new Failure("question_expired");
    if (job.answerReceipts.containsKey(request)) {
      if (!job.answerReceipts.get(request).equals(digest)) throw new Failure("request_conflict");
    } else {
      if (!job.state.equals("running")
          || job.question == null
          || job.server == null
          || !job.question.request().equals(request)) throw new Failure("question_expired");
      JsonObject response;
      try {
        response = job.question.validateAnswers(answers);
      } catch (RuntimeException e) {
        throw new Failure("question_invalid_answer");
      }
      try {
        job.server.answer(job.rpcQuestion, response);
      } catch (IOException e) {
        throw new Failure("question_expired");
      }
      job.answerReceipts.put(request, digest);
      if (job.answerReceipts.size() > 32)
        job.answerReceipts.remove(job.answerReceipts.keySet().iterator().next());
      job.deadline += System.nanoTime() - job.waitingSince;
      job.question = null;
      event(job, "question.answered");
      persist();
    }
    var result = new JsonObject();
    result.addProperty("answer_ack", request);
    return result;
  }

  private void run(Job job, Config captured) {
    synchronized (lock) {
      if (closed || !job.state.equals("queued")) return;
      job.state = "running";
      job.progress.started = now();
      job.deadline = System.nanoTime() + taskTimeout.toNanos();
      event(job, "local.started");
      persist();
    }
    Process process = null;
    ScheduledFuture<?> timeout = null;
    Path overlay = null;
    AtomicBoolean expired = new AtomicBoolean();
    try {
      AgentBackend backend = AgentBackend.of(captured.provider());
      String password = UUID.randomUUID().toString() + UUID.randomUUID();
      var environment = new HashMap<String, String>();
      if (backend == AgentBackend.DEEPSEEK) {
        overlay =
            Files.createTempFile(configFile.toAbsolutePath().getParent(), "ai-read-only-", ".yml");
        var patches = new JsonArray();
        var sandbox = new JsonObject();
        sandbox.addProperty("id", "sandbox-policy");
        var sandboxConfig = new JsonObject();
        sandboxConfig.addProperty("mode", "read-only");
        sandboxConfig.addProperty("workspaceRoot", captured.workspace());
        sandbox.add("config", sandboxConfig);
        patches.add(sandbox);
        var approval = new JsonObject();
        approval.addProperty("id", "approval");
        var approvalConfig = new JsonObject();
        approvalConfig.addProperty("policy", "never");
        approval.add("config", approvalConfig);
        patches.add(approval);
        var hmr = new JsonObject();
        hmr.addProperty("id", "hmr");
        hmr.addProperty("disabled", true);
        patches.add(hmr);
        Files.writeString(overlay, patches.toString());
        environment.put("DSH_PERMISSION_MODE", "read-only");
      }
      if (backend == AgentBackend.OPENCODE) {
        environment.put("OPENCODE_PASSWORD", password);
        environment.put("OPENCODE_SERVER_PASSWORD", password);
      }
      List<String> command = new ArrayList<>(commandPrefix(captured.executable(), backend));
      command.addAll(
          backend.arguments(captured.contextTokens(), overlay == null ? null : overlay.toString()));
      synchronized (lock) {
        if (!job.state.equals("running")) return;
        process = start(command, false, Path.of(captured.workspace()), environment);
        activeJobs.put(job.id, process);
      }
      Process target = process;
      timeout =
          timer.scheduleAtFixedRate(
              () -> {
                synchronized (lock) {
                  if (job.state.equals("running")
                      && job.question == null
                      && System.nanoTime() >= job.deadline) {
                    expired.set(true);
                    stop(target);
                  }
                }
              },
              100,
              100,
              TimeUnit.MILLISECONDS);
      Request request = JSON.fromJson(job.request, Request.class);
      StringBuilder prompt = new StringBuilder();
      for (String skill : request.skills()) prompt.append("Skill:\n").append(skill).append("\n\n");
      prompt.append(
          "Use only read-only tools. Do not change files, permissions or configurations.\n");
      prompt.append(
          "For multi-step work, maintain a concise plan with the plan tool when available.\n");
      if (backend.questions)
        prompt.append(
            "When a decision needs player input, use your structured question tool and wait for the"
                + " answer.\n");
      prompt.append("Task:\n").append(request.prompt());
      var listener =
          new AgentSession.Listener() {
            public void event(JsonObject value) {
              synchronized (lock) {
                if (closed || !job.state.equals("running")) return;
                LocalCodex.this.event(job, value.get("type").getAsString());
                job.progress.observe(value);
                persist();
              }
            }

            public void image(String path, String inline) {
              try {
                if (path != null) images.capture(job.id, Path.of(captured.workspace()), path);
                else if (inline != null && inline.length() <= ImageAssets.MAX_BYTES * 4 / 3 + 8)
                  images.capture(job.id, Base64.getDecoder().decode(inline), "image.png");
              } catch (IOException | RuntimeException ignored) {
                /* Unsupported/unsafe images never read arbitrary files. */
              }
            }

            public void turn(String id) {
              synchronized (lock) {
                job.turn = id;
                persist();
              }
            }

            public void session(String id) {
              synchronized (lock) {
                job.session = id.substring(0, Math.min(128, id.length()));
                persist();
              }
            }

            public void resolved(JsonElement rpcId) {
              synchronized (lock) {
                if (job.question != null && Objects.equals(job.rpcQuestion, rpcId)) {
                  job.deadline += System.nanoTime() - job.waitingSince;
                  job.question = null;
                  LocalCodex.this.event(job, "question.resolved");
                  persist();
                }
              }
            }

            public void question(JsonElement rpcId, JsonObject params) {
              synchronized (lock) {
                if (closed || !job.state.equals("running")) return;
                if (job.question != null) throw new Failure("question_unsupported");
                try {
                  job.question = AgentQuestion.read(params);
                } catch (RuntimeException e) {
                  throw new Failure("question_unsupported");
                }
                job.rpcQuestion = rpcId.deepCopy();
                job.waitingSince = System.nanoTime();
                LocalCodex.this.event(job, "question.requested");
                persist();
              }
            }
          };
      AgentSession session =
          switch (backend) {
            case CODEX -> new CodexAppServer(process, listener);
            case PI -> new PiSession(process, listener);
            case DEEPSEEK -> new AcpSession(process, listener);
            case OPENCODE -> new OpenCodeSession(process, listener, password);
          };
      synchronized (lock) {
        job.server = session;
      }
      var outcome = session.run(captured.workspace(), prompt.toString());
      synchronized (lock) {
        if (closed || job.state.equals("cancelled")) return;
        boolean success = outcome.success() && !expired.get();
        job.question = null;
        job.state = success ? "completed" : "failed";
        job.result = success ? outcome.text() : "";
        job.result_key =
            success ? "" : expired.get() ? "local_task_timeout" : "local_execution_failed";
        job.updated = now();
        persist();
      }
    } catch (Exception e) {
      if (e instanceof InterruptedException) Thread.currentThread().interrupt();
      synchronized (lock) {
        if (!closed && !job.state.equals("cancelled")) {
          job.question = null;
          job.state = "failed";
          job.result_key =
              expired.get()
                  ? "local_task_timeout"
                  : e instanceof Failure f ? f.key : "local_execution_failed";
          job.updated = now();
          persist();
        }
      }
    } finally {
      synchronized (lock) {
        activeJobs.remove(job.id);
        job.server = null;
        job.rpcQuestion = null;
      }
      if (timeout != null) timeout.cancel(false);
      if (overlay != null)
        try {
          Files.deleteIfExists(overlay);
        } catch (IOException ignored) {
        }
      if (process != null) {
        if (process.isAlive()) stop(process);
        release(process);
      }
    }
  }

  static String boundedLine(Reader reader) throws IOException {
    StringBuilder line = new StringBuilder();
    boolean oversized = false;
    int c;
    while ((c = reader.read()) != -1 && c != '\n') {
      if (line.length() < ImageAssets.MAX_BYTES * 4 / 3 + 65536) line.append((char) c);
      else oversized = true;
    }
    if (c == -1 && line.isEmpty() && !oversized) return null;
    return oversized ? "" : line.toString();
  }

  private void event(Job job, String type) {
    long seq = job.events.isEmpty() ? 1 : job.events.getLast().seq + 1;
    job.events.add(new Event(seq, type));
    if (job.events.size() > 12) job.events.removeFirst();
    job.updated = now();
  }

  private void persist() {
    if (storageError) throw new Failure("local_storage_error");
    try {
      atomicWrite(jobsFile, JSON.toJson(jobs.values()));
    } catch (IOException e) {
      storageError = true;
      throw new Failure("local_storage_error");
    }
  }

  private static void atomicWrite(Path file, String text) throws IOException {
    Files.createDirectories(file.toAbsolutePath().getParent());
    Path temporary = Files.createTempFile(file.toAbsolutePath().getParent(), ".ai-", ".tmp");
    try {
      Files.writeString(temporary, text, StandardCharsets.UTF_8);
      try {
        Files.move(
            temporary, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
      } catch (AtomicMoveNotSupportedException e) {
        Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING);
      }
    } finally {
      Files.deleteIfExists(temporary);
    }
  }

  @Override
  public void close() {
    synchronized (lock) {
      if (closed) return;
      closed = true;
      for (Process process : processes) stop(process);
      for (Job job : jobs.values())
        if (job.state.equals("running") || job.state.equals("queued")) {
          job.question = null;
          job.state = "interrupted";
          job.result_key = "local_interrupted";
          job.updated = now();
        }
      try {
        if (!storageError) persist();
      } catch (Failure ignored) {
      }
      worker.shutdownNow();
      checker.shutdownNow();
      timer.shutdownNow();
    }
  }
}
