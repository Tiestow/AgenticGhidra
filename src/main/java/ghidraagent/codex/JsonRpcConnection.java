package ghidraagent.codex;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.Strictness;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;

/** Owns one process and its JSONL transport. All IO and shutdown work runs off the EDT. */
final class JsonRpcConnection implements AutoCloseable {
    private static final Gson JSON = new GsonBuilder().disableHtmlEscaping().create();
    private static final int MAX_LINE_LENGTH = 4 * 1024 * 1024;

    record Notification(String method, JsonObject params) { }

    private record Pending(CompletableFuture<JsonElement> future, ScheduledFuture<?> timeout) { }

    private final Object lock = new Object();
    private final Process process;
    private final Duration requestTimeout;
    private final Consumer<Notification> notifications;
    private final Consumer<CodexException> failures;
    private final BufferedWriter stdin;
    private final Map<String, Pending> pending = new HashMap<>();
    private final Set<CompletableFuture<Void>> writes = new HashSet<>();
    private final ExecutorService writer = Executors.newSingleThreadExecutor(
            Thread.ofVirtual().name("GhidraAgent-stdin").factory());
    private final ScheduledExecutorService timer = Executors.newSingleThreadScheduledExecutor(
            Thread.ofPlatform().daemon().name("GhidraAgent-request-timeout").factory());
    private final CompletableFuture<Void> terminated = new CompletableFuture<>();
    private final Thread stdout;
    private final Thread stderr;
    private final Thread exitWatcher;
    private long nextId;
    private boolean closed;

    JsonRpcConnection(Process process, Duration requestTimeout,
            Consumer<Notification> notifications, Consumer<CodexException> failures) {
        this.process = process;
        this.requestTimeout = requestTimeout;
        this.notifications = notifications;
        this.failures = failures;
        stdin = new BufferedWriter(new OutputStreamWriter(process.getOutputStream(), StandardCharsets.UTF_8));
        stdout = Thread.ofVirtual().name("GhidraAgent-stdout").unstarted(this::readStdout);
        stderr = Thread.ofVirtual().name("GhidraAgent-stderr").unstarted(this::drainStderr);
        exitWatcher = Thread.ofVirtual().name("GhidraAgent-process-exit").unstarted(() -> {
            try {
                process.waitFor();
                fail(new CodexException(CodexException.Kind.PROCESS_EXIT, "The Codex process exited."));
            }
            catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
    }

    void start() {
        stdout.start();
        stderr.start();
        exitWatcher.start();
    }

    CompletableFuture<JsonElement> request(String method, JsonObject params) {
        CompletableFuture<JsonElement> future = new CompletableFuture<>();
        synchronized (lock) {
            if (closed) {
                return CompletableFuture.failedFuture(CodexException.closed());
            }
            String id = "ghidra-" + ++nextId;
            JsonObject message = message(method, params);
            message.addProperty("id", id);
            ScheduledFuture<?> timeout = timer.schedule(() -> {
                synchronized (lock) {
                    if (!pending.containsKey(id)) {
                        return;
                    }
                }
                fail(CodexException.timeout());
            },
                    requestTimeout.toMillis(), TimeUnit.MILLISECONDS);
            pending.put(id, new Pending(future, timeout));
            enqueue(message).whenComplete((unused, error) -> {
                if (error != null) {
                    future.completeExceptionally(error);
                }
            });
        }
        return future;
    }

    CompletableFuture<Void> notify(String method, JsonObject params) {
        return enqueue(message(method, params));
    }

    private static JsonObject message(String method, JsonObject params) {
        JsonObject message = new JsonObject();
        message.addProperty("method", method);
        message.add("params", params);
        // Codex intentionally omits the jsonrpc field on the wire.
        return message;
    }

    private CompletableFuture<Void> enqueue(JsonObject message) {
        CompletableFuture<Void> sent = new CompletableFuture<>();
        // Serialize before returning so the caller cannot mutate an already queued message.
        String line = JSON.toJson(message) + "\n";
        synchronized (lock) {
            if (closed) {
                return CompletableFuture.failedFuture(CodexException.closed());
            }
            writes.add(sent);
            sent.whenComplete((unused, error) -> {
                synchronized (lock) {
                    writes.remove(sent);
                }
            });
            writer.execute(() -> {
                try {
                    stdin.write(line);
                    stdin.flush();
                    sent.complete(null);
                }
                catch (IOException e) {
                    CodexException error = new CodexException(CodexException.Kind.IO,
                            "Failed to write to Codex.");
                    sent.completeExceptionally(error);
                    fail(error);
                }
            });
        }
        return sent;
    }

    private void readStdout() {
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
            StringBuilder line = new StringBuilder();
            int c;
            while ((c = reader.read()) != -1) {
                if (c == '\n') {
                    receive(line.toString());
                    line.setLength(0);
                }
                else {
                    if (line.length() >= MAX_LINE_LENGTH) {
                        throw CodexException.protocol();
                    }
                    line.append((char) c);
                }
            }
            // JSONL frames must end in a newline, including the final frame.
            fail(line.isEmpty()
                    ? new CodexException(CodexException.Kind.EOF, "Codex closed the connection (EOF).")
                    : CodexException.protocol());
        }
        catch (CodexException e) {
            fail(e);
        }
        catch (IOException | RuntimeException e) {
            fail(new CodexException(CodexException.Kind.PROTOCOL, "The Codex data stream is invalid or interrupted."));
        }
    }

    private void receive(String line) throws IOException {
        JsonElement parsed;
        try (JsonReader reader = new JsonReader(new StringReader(line))) {
            reader.setStrictness(Strictness.STRICT);
            parsed = JsonParser.parseReader(reader);
            if (reader.peek() != JsonToken.END_DOCUMENT || !parsed.isJsonObject()) {
                throw CodexException.protocol();
            }
        }
        JsonObject message = parsed.getAsJsonObject();
        if (message.has("method")) {
            String method = requiredString(message, "method");
            if (message.has("result") || message.has("error")) {
                throw CodexException.protocol();
            }
            JsonObject params = new JsonObject();
            if (message.has("params") && !message.get("params").isJsonNull()) {
                params = object(message.get("params"));
            }
            if (message.has("id")) {
                // No server-initiated operations are supported in Phase 1.2.
                JsonElement id = message.get("id");
                if (!id.isJsonPrimitive() || (!id.getAsJsonPrimitive().isString()
                        && !id.getAsJsonPrimitive().isNumber())) {
                    throw CodexException.protocol();
                }
                JsonObject reply = new JsonObject();
                reply.add("id", id);
                JsonObject error = new JsonObject();
                error.addProperty("code", -32601);
                error.addProperty("message", "Method not supported by GhidraAgent Phase 1.2");
                reply.add("error", error);
                enqueue(reply);
            }
            else {
                notifications.accept(new Notification(method, params));
            }
            return;
        }
        String id = requiredString(message, "id");
        if (message.has("result") == message.has("error")) {
            throw CodexException.protocol();
        }
        Pending request;
        synchronized (lock) {
            if (closed) {
                return;
            }
            request = pending.remove(id);
        }
        if (request == null) {
            throw CodexException.protocol();
        }
        request.timeout().cancel(false);
        if (message.has("error")) {
            try {
                JsonObject error = object(message.get("error"));
                JsonElement code = error.get("code");
                if (code == null || !code.isJsonPrimitive() || !code.getAsJsonPrimitive().isNumber()) {
                    throw CodexException.protocol();
                }
                int number = code.getAsBigDecimal().intValueExact();
                requiredString(error, "message");
                request.future().completeExceptionally(new CodexException(CodexException.Kind.SERVER,
                        "Codex reported a server error (code " + number + ")."));
            }
            catch (RuntimeException e) {
                request.future().completeExceptionally(CodexException.protocol());
                throw CodexException.protocol();
            }
        }
        else {
            request.future().complete(message.get("result"));
        }
    }

    static JsonObject object(JsonElement element) {
        if (element == null || !element.isJsonObject()) {
            throw CodexException.protocol();
        }
        return element.getAsJsonObject();
    }

    static String requiredString(JsonObject object, String key) {
        JsonElement value = object.get(key);
        if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString()
                || value.getAsString().isBlank()) {
            throw CodexException.protocol();
        }
        return value.getAsString();
    }

    private void drainStderr() {
        try (var stream = process.getErrorStream()) {
            // Never retain or log stderr: it may contain authentication information.
            stream.transferTo(OutputStream.nullOutputStream());
        }
        catch (IOException e) {
            fail(new CodexException(CodexException.Kind.IO, "The Codex stderr stream was interrupted."));
        }
    }

    private void fail(CodexException error) {
        terminate(error, true);
    }

    CompletableFuture<Void> closeAsync() {
        terminate(CodexException.closed(), false);
        return terminated;
    }

    private void terminate(CodexException error, boolean unexpected) {
        List<Pending> outstanding;
        List<CompletableFuture<Void>> queuedWrites;
        synchronized (lock) {
            if (closed) {
                return;
            }
            closed = true;
            outstanding = new ArrayList<>(pending.values());
            pending.clear();
            queuedWrites = new ArrayList<>(writes);
            writes.clear();
        }
        timer.shutdownNow();
        // Let queued writes finish/fail after killing the process so every send future settles.
        writer.shutdown();
        // Non-daemon: plugin disposal during JVM exit must still reap the child process.
        Thread.ofPlatform().daemon(false).name("GhidraAgent-process-cleanup").start(this::releaseResources);
        for (Pending request : outstanding) {
            request.timeout().cancel(false);
            request.future().completeExceptionally(error);
        }
        queuedWrites.forEach(write -> write.completeExceptionally(error));
        if (unexpected) {
            failures.accept(error);
        }
    }

    private void releaseResources() {
        try {
            List<ProcessHandle> children = process.descendants().toList();
            children.forEach(ProcessHandle::destroy);
            process.destroy();
            if (!process.waitFor(1, TimeUnit.SECONDS)) {
                process.destroyForcibly();
            }
            children.stream().filter(ProcessHandle::isAlive).forEach(ProcessHandle::destroyForcibly);
            if (!process.waitFor(2, TimeUnit.SECONDS)) {
                throw new IOException("Process did not exit");
            }
            // Destroy first: closing a stream while another thread is blocked in IO can block.
            closeQuietly(stdin);
            closeQuietly(process.getOutputStream());
            closeQuietly(process.getInputStream());
            closeQuietly(process.getErrorStream());
            if (!writer.awaitTermination(2, TimeUnit.SECONDS)) {
                throw new IOException("Writer did not stop");
            }
            stdout.join(2000);
            stderr.join(2000);
            exitWatcher.join(2000);
            if (stdout.isAlive() || stderr.isAlive() || exitWatcher.isAlive()) {
                throw new IOException("Reader did not stop");
            }
            terminated.complete(null);
        }
        catch (IOException | InterruptedException | RuntimeException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            process.destroyForcibly();
            terminated.completeExceptionally(new CodexException(CodexException.Kind.IO,
                    "Could not fully release Codex resources."));
        }
        finally {
            closeQuietly(process.getOutputStream());
            closeQuietly(process.getInputStream());
            closeQuietly(process.getErrorStream());
            writer.shutdownNow();
            timer.shutdownNow();
        }
    }

    private static void closeQuietly(AutoCloseable resource) {
        try {
            resource.close();
        }
        catch (Exception ignored) {
            // Broken pipes are expected after forced termination. Never log raw IO diagnostics.
        }
    }

    @Override
    public void close() {
        closeAsync();
    }
}
