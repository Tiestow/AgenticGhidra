package ghidraagent.codex;

import static ghidraagent.codex.JsonRpcConnection.object;
import static ghidraagent.codex.JsonRpcConnection.requiredString;

import java.io.IOException;
import java.time.Duration;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.function.Function;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

/** Asynchronous lifecycle and stable Codex handshake. Does not create threads/conversations in Codex. */
public final class CodexClient implements AutoCloseable {
    public enum State { DISCONNECTED, CONNECTING, CONNECTED, DISCONNECTING, ERROR }

    public enum AccountStatus {
        UNKNOWN("Not checked"),
        CHATGPT("Signed in locally with ChatGPT"),
        API_KEY("Local API key authentication available"),
        EXTERNAL_PROVIDER("Provider does not require OpenAI authentication"),
        LOGIN_REQUIRED("No suitable login found. Run codex login in a terminal."),
        UNSUPPORTED("Unsupported authentication type. Check your local Codex login.");

        private final String label;

        AccountStatus(String label) {
            this.label = label;
        }

        @Override
        public String toString() {
            return label;
        }
    }

    /** Preserve both the catalog ID and model identifier for future conversation support. */
    public record Model(String id, String model, String displayName, boolean isDefault) {
        @Override
        public String toString() {
            return displayName;
        }
    }

    public record Snapshot(State state, AccountStatus account, List<Model> models, String message) {
        public Snapshot {
            models = List.copyOf(models);
        }
    }

    private static final class Session {
        final CompletableFuture<Snapshot> connected = new CompletableFuture<>();
        final CompletableFuture<Void> stopped = new CompletableFuture<>();
        JsonRpcConnection rpc;
        ScheduledFuture<?> deadline;
        boolean stopping;
        CodexException error;
        boolean accountRead;
        AccountStatus accountUpdate;
    }

    private final Object lock = new Object();
    private final Duration requestTimeout;
    private final Duration connectionTimeout;
    private final Function<String, List<String>> command;
    private final Consumer<Snapshot> listener;
    private final ScheduledExecutorService timer = Executors.newSingleThreadScheduledExecutor(
            Thread.ofPlatform().daemon().name("GhidraAgent-connect-timeout").factory());
    private Snapshot snapshot = new Snapshot(State.DISCONNECTED, AccountStatus.UNKNOWN, List.of(), "");
    private Session session;
    private boolean disposed;

    public CodexClient(Consumer<Snapshot> listener) {
        this(Duration.ofSeconds(30), Duration.ofMinutes(2),
                executable -> List.of(executable, "app-server"), listener);
    }

    // Injection point for simulated servers; production always uses the direct executable above.
    CodexClient(Duration requestTimeout, Duration connectionTimeout,
            Function<String, List<String>> command, Consumer<Snapshot> listener) {
        if (requestTimeout.isZero() || requestTimeout.isNegative()
                || connectionTimeout.isZero() || connectionTimeout.isNegative()) {
            throw new IllegalArgumentException("Timeouts must be positive");
        }
        this.requestTimeout = requestTimeout;
        this.connectionTimeout = connectionTimeout;
        this.command = command;
        this.listener = listener;
    }

    public Snapshot snapshot() {
        synchronized (lock) {
            return snapshot;
        }
    }

    /** Returns immediately, including when ProcessBuilder.start or shutdown is slow. */
    public CompletableFuture<Snapshot> connect(String executable) {
        Session created;
        synchronized (lock) {
            if (disposed) {
                return CompletableFuture.failedFuture(CodexException.closed());
            }
            if (session != null) {
                return CompletableFuture.failedFuture(new CodexException(CodexException.Kind.START,
                        "A Codex connection is already active or closing."));
            }
            if (executable == null || executable.isBlank()) {
                return CompletableFuture.failedFuture(new CodexException(CodexException.Kind.START,
                        "Enter the path to the Codex executable."));
            }
            created = new Session();
            session = created;
            created.deadline = timer.schedule(() -> stop(created, CodexException.timeout()),
                    connectionTimeout.toMillis(), TimeUnit.MILLISECONDS);
            publish(State.CONNECTING, AccountStatus.UNKNOWN, List.of(), "Connecting to Codex …");
        }
        Thread.ofPlatform().daemon(false).name("GhidraAgent-process-start").start(() -> start(created, executable));
        return created.connected;
    }

    private void start(Session current, String executable) {
        try {
            synchronized (lock) {
                if (current.stopping) {
                    finish(current, null);
                    return;
                }
            }
            // Inherit local Codex auth/config and environment. Never read or copy credential files.
            Process process = new ProcessBuilder(command.apply(executable)).redirectErrorStream(false).start();
            JsonRpcConnection rpc;
            synchronized (lock) {
                rpc = new JsonRpcConnection(process, requestTimeout,
                        notification -> notification(current, notification), error -> stop(current, error));
                current.rpc = rpc;
                rpc.start();
                if (current.stopping) {
                    rpc.closeAsync().whenComplete((unused, error) -> finish(current, error));
                    return;
                }
            }
            initialize(rpc).thenCompose(unused -> {
                JsonObject params = new JsonObject();
                params.addProperty("refreshToken", false);
                return rpc.request("account/read", params).handle((result, error) -> {
                    if (error != null) {
                        throw new CodexException(safeError(error).kind(),
                                "Could not read account status. Check your local login with codex login.");
                    }
                    return result;
                });
            }).thenCompose(result -> {
                AccountStatus account = readAccount(result);
                synchronized (lock) {
                    if (!current.stopping) {
                        current.accountRead = true;
                        publish(State.CONNECTING, account, List.of(), "Loading available models …");
                    }
                }
                return readModels(rpc, null, new HashSet<>(), new LinkedHashMap<>())
                        .thenApply(models -> new Snapshot(State.CONNECTED, account, models, accountMessage(account, models)));
            }).whenComplete((connected, error) -> {
                if (error != null) {
                    stop(current, safeError(error));
                    return;
                }
                synchronized (lock) {
                    if (!current.stopping) {
                        current.deadline.cancel(false);
                        AccountStatus account = current.accountUpdate == null ? connected.account() : current.accountUpdate;
                        publish(connected.state(), account, connected.models(),
                                current.accountUpdate == null ? connected.message() : accountChangedMessage());
                        current.connected.complete(snapshot);
                    }
                }
            });
        }
        catch (IOException | RuntimeException e) {
            stop(current, new CodexException(CodexException.Kind.START,
                    "Could not start Codex. Check the executable path and file permissions."));
            // No process was published; there is nothing else for disconnect to wait for.
            synchronized (lock) {
                if (current.rpc == null) {
                    finish(current, null);
                }
            }
        }
    }

    private CompletableFuture<Void> initialize(JsonRpcConnection rpc) {
        JsonObject info = new JsonObject();
        info.addProperty("name", "ghidra_agent");
        info.addProperty("title", "GhidraAgent");
        info.addProperty("version", "0.1.0");
        JsonObject capabilities = new JsonObject();
        capabilities.addProperty("experimentalApi", false);
        JsonObject params = new JsonObject();
        params.add("clientInfo", info);
        params.add("capabilities", capabilities);
        return rpc.request("initialize", params).thenCompose(result -> {
            requiredString(object(result), "userAgent");
            return rpc.notify("initialized", new JsonObject());
        });
    }

    static AccountStatus readAccount(JsonElement result) {
        JsonObject response = object(result);
        boolean requiresAuth = requiredBoolean(response, "requiresOpenaiAuth");
        JsonElement account = response.get("account");
        if (account == null || account.isJsonNull()) {
            return requiresAuth ? AccountStatus.LOGIN_REQUIRED : AccountStatus.EXTERNAL_PROVIDER;
        }
        String type = requiredString(object(account), "type");
        return switch (type) {
            case "chatgpt" -> AccountStatus.CHATGPT;
            case "apiKey" -> AccountStatus.API_KEY;
            default -> requiresAuth ? AccountStatus.UNSUPPORTED : AccountStatus.EXTERNAL_PROVIDER;
        };
    }

    private CompletableFuture<List<Model>> readModels(JsonRpcConnection rpc, String cursor,
            Set<String> cursors, LinkedHashMap<String, Model> models) {
        JsonObject params = new JsonObject();
        params.addProperty("limit", 100);
        params.addProperty("includeHidden", false);
        if (cursor != null) {
            params.addProperty("cursor", cursor);
        }
        return rpc.request("model/list", params).thenCompose(result -> {
            JsonObject response = object(result);
            JsonElement data = response.get("data");
            if (data == null || !data.isJsonArray()) {
                throw CodexException.protocol();
            }
            for (JsonElement item : data.getAsJsonArray()) {
                JsonObject model = object(item);
                Model parsed = new Model(requiredString(model, "id"), requiredString(model, "model"),
                        requiredString(model, "displayName"), requiredBoolean(model, "isDefault"));
                if (!requiredBoolean(model, "hidden")) {
                    models.putIfAbsent(parsed.id(), parsed);
                }
            }
            JsonElement next = response.get("nextCursor");
            if (next == null || next.isJsonNull()) {
                return CompletableFuture.completedFuture(List.copyOf(models.values()));
            }
            String nextCursor = requiredString(response, "nextCursor");
            if (!cursors.add(nextCursor) || cursors.size() > 100) {
                throw CodexException.protocol();
            }
            return readModels(rpc, nextCursor, cursors, models);
        });
    }

    private static boolean requiredBoolean(JsonObject object, String key) {
        JsonElement value = object.get(key);
        if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isBoolean()) {
            throw CodexException.protocol();
        }
        return value.getAsBoolean();
    }

    private static String accountMessage(AccountStatus account, List<Model> models) {
        if (account == AccountStatus.LOGIN_REQUIRED || account == AccountStatus.UNSUPPORTED) {
            return account.toString();
        }
        return models.isEmpty() ? "Connected. Codex returned no visible models." : "Connected.";
    }

    private void notification(Session current, JsonRpcConnection.Notification notification) {
        synchronized (lock) {
            if (session != current || current.stopping || !current.accountRead) {
                return;
            }
            if (notification.method().equals("account/updated")) {
                JsonElement mode = notification.params().get("authMode");
                AccountStatus account;
                if (mode == null || mode.isJsonNull()) {
                    account = AccountStatus.LOGIN_REQUIRED;
                }
                else {
                    account = switch (requiredString(notification.params(), "authMode")) {
                        case "chatgpt" -> AccountStatus.CHATGPT;
                        case "apikey" -> AccountStatus.API_KEY;
                        default -> AccountStatus.UNKNOWN;
                    };
                }
                current.accountUpdate = account;
                publish(snapshot.state(), account, snapshot.models(), accountChangedMessage());
            }
            // Unknown notifications are intentionally ignored, never rendered or logged.
        }
    }

    private static String accountChangedMessage() {
        return "Account status changed. Reconnect to check it and reload models.";
    }

    public CompletableFuture<Void> disconnect() {
        Session current;
        synchronized (lock) {
            current = session;
            if (current == null) {
                publish(State.DISCONNECTED, AccountStatus.UNKNOWN, List.of(), "");
                return CompletableFuture.completedFuture(null);
            }
        }
        stop(current, null);
        return current.stopped;
    }

    private void stop(Session current, CodexException error) {
        JsonRpcConnection rpc;
        synchronized (lock) {
            if (session != current || current.stopping) {
                return;
            }
            current.stopping = true;
            current.error = error;
            current.deadline.cancel(false);
            publish(State.DISCONNECTING, AccountStatus.UNKNOWN, List.of(), "Stopping Codex …");
            current.connected.completeExceptionally(error == null ? CodexException.closed() : error);
            rpc = current.rpc;
        }
        if (rpc != null) {
            rpc.closeAsync().whenComplete((unused, cleanupError) -> finish(current, cleanupError));
        }
        // If ProcessBuilder is still starting, start() will close it before allowing a reconnect.
    }

    private void finish(Session current, Throwable cleanupError) {
        synchronized (lock) {
            if (session != current) {
                return;
            }
            session = null;
            CodexException error = cleanupError == null ? current.error : safeError(cleanupError);
            publish(error == null ? State.DISCONNECTED : State.ERROR, AccountStatus.UNKNOWN, List.of(),
                    error == null ? "" : error.getMessage());
            if (cleanupError == null) {
                current.stopped.complete(null);
            }
            else {
                current.stopped.completeExceptionally(error);
            }
        }
    }

    private static CodexException safeError(Throwable error) {
        while (error instanceof CompletionException && error.getCause() != null) {
            error = error.getCause();
        }
        return error instanceof CodexException safe ? safe : CodexException.protocol();
    }

    private void publish(State state, AccountStatus account, List<Model> models, String message) {
        // Called under lock, preserving event ordering. UI listeners must enqueue work, never wait.
        snapshot = new Snapshot(state, account, models, message);
        try {
            listener.accept(snapshot);
        }
        catch (RuntimeException ignored) {
            // A host UI callback must not interfere with process cleanup or leak payloads to logs.
        }
    }

    public CompletableFuture<Void> closeAsync() {
        synchronized (lock) {
            disposed = true;
        }
        timer.shutdownNow();
        return disconnect();
    }

    @Override
    public void close() {
        closeAsync();
    }
}
