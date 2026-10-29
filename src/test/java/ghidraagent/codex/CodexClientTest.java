package ghidraagent.codex;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;

import javax.swing.SwingUtilities;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import ghidraagent.codex.CodexClient.AccountStatus;
import ghidraagent.codex.CodexClient.Snapshot;
import ghidraagent.codex.CodexClient.State;

class CodexClientTest {
    @TempDir Path temp;

    @Test
    void explicitConnectPerformsHandshakeAccountReadAndModelListInOrder() throws Exception {
        try (Fixture fixture = fixture("normal")) {
            assertEquals(0, fixture.launches.get());
            Snapshot result = fixture.client.connect("unused").get(10, TimeUnit.SECONDS);
            assertEquals(State.CONNECTED, result.state());
            assertEquals(AccountStatus.CHATGPT, result.account());
            assertEquals("catalog-a", result.models().getFirst().id());
            assertEquals("wire-a", result.models().getFirst().model());
            assertEquals("Model A", result.models().getFirst().displayName());
            assertEquals(1, fixture.launches.get());
        }
    }

    @Test
    void followsOpaqueCursorAndDeduplicatesModelsWhilePreservingServerNames() throws Exception {
        try (Fixture fixture = fixture("pagination")) {
            Snapshot result = fixture.client.connect("unused").get(10, TimeUnit.SECONDS);
            assertEquals(List.of("catalog-a", "catalog-b"), result.models().stream().map(CodexClient.Model::id).toList());
            assertEquals(List.of("Model A", "Model B"), result.models().stream().map(CodexClient.Model::displayName).toList());
            assertTrue(result.models().get(1).isDefault());
        }
    }

    @Test
    void absentNextCursorAndEmptyCatalogAreSupported() throws Exception {
        try (Fixture fixture = fixture("empty-models")) {
            Snapshot result = fixture.client.connect("unused").get(10, TimeUnit.SECONDS);
            assertTrue(result.models().isEmpty());
            assertTrue(result.message().contains("no visible models"));
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"auth-null", "auth-optional", "auth-api", "auth-unknown"})
    void reportsLocalAuthenticationWithoutReadingCredentials(String mode) throws Exception {
        try (Fixture fixture = fixture(mode)) {
            Snapshot result = fixture.client.connect("unused").get(10, TimeUnit.SECONDS);
            assertEquals(switch (mode) {
                case "auth-null" -> AccountStatus.LOGIN_REQUIRED;
                case "auth-optional" -> AccountStatus.EXTERNAL_PROVIDER;
                case "auth-api" -> AccountStatus.API_KEY;
                default -> AccountStatus.UNSUPPORTED;
            }, result.account());
            if (mode.equals("auth-null")) {
                assertTrue(result.message().contains("codex login"));
            }
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"missing-init", "auth-bad", "auth-bad-boolean", "bad-models", "loop"})
    void rejectsInvalidHandshakeAccountAndPaginationResponses(String mode) throws Exception {
        try (Fixture fixture = fixture(mode)) {
            ExecutionException error = assertThrows(ExecutionException.class,
                    () -> fixture.client.connect("unused").get(10, TimeUnit.SECONDS));
            assertEquals(CodexException.Kind.PROTOCOL, assertInstanceOf(CodexException.class, error.getCause()).kind());
            await(() -> fixture.client.snapshot().state() == State.ERROR);
            assertTrue(fixture.client.snapshot().models().isEmpty());
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"notification", "server-request", "stderr-flood"})
    void notificationsServerRequestsAndStderrDoNotDisruptHandshake(String mode) throws Exception {
        try (Fixture fixture = fixture(mode)) {
            assertEquals(State.CONNECTED, fixture.client.connect("unused").get(10, TimeUnit.SECONDS).state());
            assertFalse(fixture.events.toString().contains("SENSITIVE_TEST_MARKER"));
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"account-notification", "early-account-notification"})
    void accountUpdatedNotificationChangesDisplayedAuthentication(String mode) throws Exception {
        try (Fixture fixture = fixture(mode)) {
            fixture.client.connect("unused").get(10, TimeUnit.SECONDS);
            await(() -> fixture.client.snapshot().account() == AccountStatus.LOGIN_REQUIRED);
        }
    }

    @Test
    void accountServerErrorGivesSafeLoginAdvice() throws Exception {
        try (Fixture fixture = fixture("auth-error")) {
            assertThrows(ExecutionException.class, () -> fixture.client.connect("unused").get(10, TimeUnit.SECONDS));
            await(() -> fixture.client.snapshot().state() == State.ERROR);
            assertTrue(fixture.client.snapshot().message().contains("codex login"));
            assertFalse(fixture.events.toString().contains("SENSITIVE_TEST_MARKER"));
        }
    }

    @Test
    void unresponsiveShutdownIsEscalatedToForcedTermination() throws Exception {
        try (Fixture fixture = fixture("ignore-termination")) {
            fixture.client.connect("unused").get(10, TimeUnit.SECONDS);
            fixture.client.disconnect().get(10, TimeUnit.SECONDS);
            assertEquals(State.DISCONNECTED, fixture.client.snapshot().state());
        }
    }

    @Test
    void duplicateConnectDoesNotStartAnotherProcessAndReconnectWorksAfterDisconnect() throws Exception {
        try (Fixture fixture = fixture("normal")) {
            var first = fixture.client.connect("unused");
            assertThrows(ExecutionException.class, () -> fixture.client.connect("unused").get(5, TimeUnit.SECONDS));
            first.get(10, TimeUnit.SECONDS);
            assertEquals(1, fixture.launches.get());
            long firstPid = Long.parseLong(Files.readString(fixture.pidFile));
            fixture.client.disconnect().get(10, TimeUnit.SECONDS);
            assertFalse(ProcessHandle.of(firstPid).map(ProcessHandle::isAlive).orElse(false));
            assertEquals(State.DISCONNECTED, fixture.client.snapshot().state());
            fixture.client.connect("unused").get(10, TimeUnit.SECONDS);
            assertEquals(2, fixture.launches.get());
        }
    }

    @Test
    void connectionDeadlineAlsoBoundsAContinuingPaginationSequence() throws Exception {
        try (Fixture fixture = fixture("slow-pages", Duration.ofSeconds(5), Duration.ofSeconds(1))) {
            ExecutionException error = assertThrows(ExecutionException.class,
                    () -> fixture.client.connect("unused").get(10, TimeUnit.SECONDS));
            assertEquals(CodexException.Kind.TIMEOUT, assertInstanceOf(CodexException.class, error.getCause()).kind());
        }
    }

    @Test
    void unexpectedExitAfterConnectionClearsModelsAndReportsError() throws Exception {
        try (Fixture fixture = fixture("exit-after-connect")) {
            fixture.client.connect("unused").get(10, TimeUnit.SECONDS);
            await(() -> fixture.client.snapshot().state() == State.ERROR);
            assertTrue(fixture.client.snapshot().models().isEmpty());
            assertEquals(AccountStatus.UNKNOWN, fixture.client.snapshot().account());
        }
    }

    @Test
    void nonexistentExecutableProducesSafeStartFailure() throws Exception {
        try (CodexClient client = new CodexClient(snapshot -> { })) {
            ExecutionException error = assertThrows(ExecutionException.class,
                    () -> client.connect(temp.resolve("missing-executable").toString()).get(5, TimeUnit.SECONDS));
            assertEquals(CodexException.Kind.START, assertInstanceOf(CodexException.class, error.getCause()).kind());
            await(() -> client.snapshot().state() == State.ERROR);
            assertFalse(client.snapshot().message().contains(temp.toString()));
        }
    }

    @Test
    void connectAndCloseNeverWaitForServerOnEdtAndDisposedClientCannotReconnect() throws Exception {
        try (Fixture fixture = fixture("hang")) {
            SwingUtilities.invokeAndWait(() -> {
                assertTimeout(Duration.ofSeconds(1), () -> {
                    fixture.client.connect("unused");
                    fixture.client.closeAsync();
                });
            });
            fixture.client.closeAsync().get(10, TimeUnit.SECONDS);
            assertThrows(ExecutionException.class, () -> fixture.client.connect("unused").get(5, TimeUnit.SECONDS));
        }
    }

    @Test
    void disconnectDuringProcessStartPreventsReconnectUntilTheProcessIsReaped() throws Exception {
        CountDownLatch starting = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        Path pidFile = temp.resolve("starting.pid");
        CodexClient client = new CodexClient(Duration.ofSeconds(5), Duration.ofSeconds(15), unused -> {
            starting.countDown();
            try {
                if (!release.await(5, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("Fixture start was not released");
                }
            }
            catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("Fixture interrupted");
            }
            return FakeServerSupport.command("hang", pidFile);
        }, snapshot -> { });
        try {
            var connected = client.connect("unused");
            assertTrue(starting.await(5, TimeUnit.SECONDS));
            var stopped = client.disconnect();
            assertFalse(stopped.isDone());
            assertThrows(ExecutionException.class, () -> client.connect("unused").get(5, TimeUnit.SECONDS));
            release.countDown();
            stopped.get(10, TimeUnit.SECONDS);
            assertThrows(ExecutionException.class, () -> connected.get(5, TimeUnit.SECONDS));
            assertEquals(State.DISCONNECTED, client.snapshot().state());
            if (Files.exists(pidFile)) {
                long pid = Long.parseLong(Files.readString(pidFile));
                assertFalse(ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false));
            }
        }
        finally {
            release.countDown();
            client.closeAsync().get(15, TimeUnit.SECONDS);
        }
    }

    private Fixture fixture(String mode) {
        return fixture(mode, Duration.ofSeconds(5), Duration.ofSeconds(15));
    }

    private Fixture fixture(String mode, Duration requestTimeout, Duration connectionTimeout) {
        Path pid = temp.resolve("server.pid");
        AtomicInteger launches = new AtomicInteger();
        List<Snapshot> events = new CopyOnWriteArrayList<>();
        CodexClient client = new CodexClient(requestTimeout, connectionTimeout, unused -> {
            launches.incrementAndGet();
            return FakeServerSupport.command(mode, pid);
        }, events::add);
        return new Fixture(client, launches, pid, events);
    }

    private static void await(BooleanSupplier condition) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (!condition.getAsBoolean() && System.nanoTime() < deadline) {
            Thread.sleep(10);
        }
        assertTrue(condition.getAsBoolean(), "Expected asynchronous state was not reached");
    }

    private record Fixture(CodexClient client, AtomicInteger launches, Path pidFile,
            List<Snapshot> events) implements AutoCloseable {
        @Override
        public void close() throws Exception {
            client.closeAsync().get(15, TimeUnit.SECONDS);
            if (Files.exists(pidFile)) {
                long pid = Long.parseLong(Files.readString(pidFile));
                assertFalse(ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false));
            }
        }
    }
}
