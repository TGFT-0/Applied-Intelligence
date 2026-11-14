package dev.tgft.appliedintelligence.prototype.transport;

import java.io.*;
import java.nio.*;
import java.nio.channels.*;
import java.nio.file.*;
import java.nio.file.attribute.*;
import java.time.*;
import java.util.*;

/** Linux-only prototype durable state. Contains public certificates and mock jobs, never keys. */
final class GrantStore implements AutoCloseable {
  record Grant(
      String fingerprint, String certificate, Set<String> scopes, long until, boolean revoked) {}

  record Job(
      String owner, String id, String payloadHash, String state, int executions, int ticks) {}

  record State(String host, Map<String, Grant> grants, Map<String, Job> jobs) {}

  final Path directory, file;
  private final FileChannel ownership;
  private final FileLock lock;
  private State state;
  private boolean healthy = true;

  GrantStore(Path directory, String host) throws IOException {
    this.directory = directory.toAbsolutePath().normalize();
    this.file = this.directory.resolve("state.json");
    if (!Files.exists(this.directory))
      Files.createDirectory(
          this.directory,
          PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")));
    if (Files.isSymbolicLink(this.directory)
        || !this.directory.equals(this.directory.toRealPath())
        || !Files.isDirectory(this.directory)) throw new IOException("Invalid state directory");
    for (var p : Files.getPosixFilePermissions(this.directory))
      if (p.name().startsWith("GROUP_") || p.name().startsWith("OTHERS_"))
        throw new IOException("State directory is not private");
    ownership =
        FileChannel.open(
            this.directory.resolve("owner.lock"),
            StandardOpenOption.CREATE,
            StandardOpenOption.WRITE,
            LinkOption.NOFOLLOW_LINKS);
    FileLock acquired = null;
    try {
      acquired = ownership.tryLock();
      if (acquired == null) throw new IOException("State directory busy");
      if (Files.exists(file, LinkOption.NOFOLLOW_LINKS)) {
        if (Files.isSymbolicLink(file) || Files.size(file) > 1048576)
          throw new IOException("Invalid state");
        state = Wire.read(Files.readAllBytes(file), State.class);
        if (state == null
            || !host.equals(state.host)
            || state.grants == null
            || state.jobs == null
            || state.grants.size() > 32
            || state.jobs.size() > 128) throw new IOException("Invalid state");
        var jobs = new LinkedHashMap<>(state.jobs);
        jobs.replaceAll(
            (id, j) ->
                j.state.equals("running")
                    ? new Job(j.owner, j.id, j.payloadHash, "interrupted", j.executions, j.ticks)
                    : j);
        persist(new State(host, state.grants, jobs));
      } else persist(new State(host, Map.of(), Map.of()));
      lock = acquired;
    } catch (Exception e) {
      if (acquired != null) acquired.release();
      ownership.close();
      throw new IOException("Cannot open private state", e);
    }
  }

  private void persist(State next) throws IOException {
    if (!healthy) throw new IOException("State unavailable");
    Path temp = null;
    try {
      byte[] value = Wire.bytes(next);
      if (value.length > 1048576) throw new IOException("State full");
      temp =
          Files.createTempFile(
              directory,
              ".state-",
              ".tmp",
              PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
      try (var channel = FileChannel.open(temp, StandardOpenOption.WRITE)) {
        var buffer = ByteBuffer.wrap(value);
        while (buffer.hasRemaining()) channel.write(buffer);
        channel.force(true);
      }
      Files.move(temp, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
      try (var parent = FileChannel.open(directory, StandardOpenOption.READ)) {
        parent.force(true);
      }
      state = next;
    } catch (IOException | RuntimeException e) {
      healthy = false;
      throw e;
    } finally {
      if (temp != null)
        try {
          Files.deleteIfExists(temp);
        } catch (IOException e) {
          healthy = false;
          throw e;
        }
    }
  }

  synchronized void grant(Grant grant) throws IOException {
    if (!Set.of("observe", "simulate").containsAll(grant.scopes)
        || !grant.scopes.contains("observe")) throw new Wire.Fault(400, "invalid_scope");
    if (state.grants.size() >= 32 && !state.grants.containsKey(grant.fingerprint))
      throw new Wire.Fault(429, "device_limit");
    var grants = new LinkedHashMap<>(state.grants);
    grants.put(grant.fingerprint, grant);
    persist(new State(state.host, grants, state.jobs));
  }

  synchronized Grant require(String device, String scope, long now) {
    if (!healthy) throw new Wire.Fault(503, "state_unavailable");
    var grant = state.grants.get(device);
    if (grant == null || grant.revoked || now >= grant.until || !grant.scopes.contains(scope))
      throw new Wire.Fault(403, "not_authorized");
    return grant;
  }

  synchronized void revoke(String device) throws IOException {
    var grant = state.grants.get(device);
    if (grant == null) return;
    var grants = new LinkedHashMap<>(state.grants);
    grants.put(device, new Grant(device, grant.certificate, grant.scopes, grant.until, true));
    var jobs = new LinkedHashMap<>(state.jobs);
    jobs.replaceAll(
        (id, j) ->
            j.owner.equals(device) && j.state.equals("running")
                ? new Job(j.owner, j.id, j.payloadHash, "cancelled", j.executions, j.ticks)
                : j);
    persist(new State(state.host, grants, jobs));
  }

  private static String key(String device, String id) {
    return device + ":" + id;
  }

  synchronized Job submit(String device, String id, String payload, long now) throws IOException {
    require(device, "simulate", now);
    Wire.uuid(id);
    if (payload == null || payload.length() > 1000) throw new Wire.Fault(400, "invalid_payload");
    String k = key(device, id),
        hash = Wire.hash(payload.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    var old = state.jobs.get(k);
    if (old != null) {
      if (!old.payloadHash.equals(hash)) throw new Wire.Fault(409, "id_conflict");
      return old;
    }
    if (state.jobs.size() >= 128
        || state.jobs.values().stream().anyMatch(j -> j.state.equals("running")))
      throw new Wire.Fault(429, "job_limit");
    // Durable launch intent precedes the mock worker. Restart never replays it.
    var job = new Job(device, id, hash, "running", 1, 0);
    var jobs = new LinkedHashMap<>(state.jobs);
    jobs.put(k, job);
    persist(new State(state.host, state.grants, jobs));
    return job;
  }

  synchronized Job job(String device, String id, long now) {
    require(device, "observe", now);
    Wire.uuid(id);
    var job = state.jobs.get(key(device, id));
    if (job == null) throw new Wire.Fault(404, "not_found");
    return job;
  }

  synchronized boolean tick(String device, String id, long now) throws IOException {
    try {
      require(device, "simulate", now);
    } catch (Wire.Fault e) {
      return false;
    }
    var old = state.jobs.get(key(device, id));
    if (old == null || !old.state.equals("running")) return false;
    var next =
        new Job(
            device,
            id,
            old.payloadHash,
            old.ticks + 1 >= 20 ? "completed" : "running",
            old.executions,
            old.ticks + 1);
    var jobs = new LinkedHashMap<>(state.jobs);
    jobs.put(key(device, id), next);
    persist(new State(state.host, state.grants, jobs));
    return next.state.equals("running");
  }

  @Override
  public synchronized void close() throws IOException {
    lock.release();
    ownership.close();
  }
}
