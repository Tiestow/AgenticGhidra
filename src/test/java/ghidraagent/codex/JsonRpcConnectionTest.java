package ghidraagent.codex;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

class JsonRpcConnectionTest {
    @TempDir Path temp;

    @Test
    void serializationUsesJsonlWithoutJsonrpcAndEscapesNewlines() throws Exception {
        try (Fixture fixture = fixture("echo", Duration.ofSeconds(5))) {
            JsonObject params = new JsonObject();
            params.addProperty("text", "Umlaut ä\nquote \" backslash \\ <html>");
            assertEquals(params, fixture.rpc.request("test/echo", params).get(5, TimeUnit.SECONDS));
        }
    }

    @Test
    void responsesAreMatchedByIdEvenInReverseOrder() throws Exception {
        try (Fixture fixture = fixture("reverse", Duration.ofSeconds(5))) {
            JsonObject one = new JsonObject();
            one.addProperty("value", 1);
            JsonObject two = new JsonObject();
            two.addProperty("value", 2);
            var first = fixture.rpc.request("test/one", one);
            var second = fixture.rpc.request("test/two", two);
            assertEquals(one, first.get(5, TimeUnit.SECONDS));
            assertEquals(two, second.get(5, TimeUnit.SECONDS));
        }
    }

    @Test
    void concurrentWritesRemainCompleteJsonLinesWithUniqueIds() throws Exception {
        try (Fixture fixture = fixture("echo", Duration.ofSeconds(5))) {
            List<CompletableFuture<Void>> calls = new ArrayList<>();
            for (int i = 0; i < 50; i++) {
                int value = i;
                calls.add(CompletableFuture.runAsync(() -> {
                    JsonObject params = new JsonObject();
                    params.addProperty("value", value);
                    assertEquals(params, fixture.rpc.request("test/echo", params).join());
                }));
            }
            CompletableFuture.allOf(calls.toArray(CompletableFuture[]::new)).get(10, TimeUnit.SECONDS);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"malformed", "lenient-json", "bad-envelope", "unknown-id", "bad-error"})
    void invalidMessagesFailPendingRequestsAndCloseProcess(String mode) throws Exception {
        try (Fixture fixture = fixture(mode, Duration.ofSeconds(5))) {
            CodexException error = failure(fixture.rpc.request("initialize", new JsonObject()));
            assertEquals(CodexException.Kind.PROTOCOL, error.kind());
            fixture.rpc.closeAsync().get(10, TimeUnit.SECONDS);
            assertFalse(fixture.process.isAlive());
        }
    }

    @Test
    void serverErrorsNeverExposeTheirMessageOrData() throws Exception {
        try (Fixture fixture = fixture("error", Duration.ofSeconds(5))) {
            CodexException error = failure(fixture.rpc.request("initialize", new JsonObject()));
            assertEquals(CodexException.Kind.SERVER, error.kind());
            assertTrue(error.getMessage().contains("-32001"));
            assertFalse(error.toString().contains("SENSITIVE_TEST_MARKER"));
            assertNull(error.getCause());
        }
    }

    @Test
    void requestTimeoutTerminatesProcessAndReleasesResources() throws Exception {
        try (Fixture fixture = fixture("hang", Duration.ofMillis(300))) {
            assertEquals(CodexException.Kind.TIMEOUT,
                    failure(fixture.rpc.request("initialize", new JsonObject())).kind());
            fixture.rpc.closeAsync().get(10, TimeUnit.SECONDS);
            assertFalse(fixture.process.isAlive());
            assertEquals(CodexException.Kind.CLOSED,
                    failure(fixture.rpc.request("test/late", new JsonObject())).kind());
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"exit", "eof"})
    void processExitAndStdoutEofFailPendingRequests(String mode) throws Exception {
        try (Fixture fixture = fixture(mode, Duration.ofSeconds(5))) {
            CodexException error = failure(fixture.rpc.request("initialize", new JsonObject()));
            assertTrue(error.kind() == CodexException.Kind.EOF || error.kind() == CodexException.Kind.PROCESS_EXIT);
            fixture.rpc.closeAsync().get(10, TimeUnit.SECONDS);
            assertFalse(fixture.process.isAlive());
        }
    }

    @Test
    void closeIsIdempotentAndCompletesPendingRequests() throws Exception {
        try (Fixture fixture = fixture("hang", Duration.ofSeconds(5))) {
            var pending = fixture.rpc.request("initialize", new JsonObject());
            var closed = fixture.rpc.closeAsync();
            assertSame(closed, fixture.rpc.closeAsync());
            closed.get(10, TimeUnit.SECONDS);
            assertFalse(fixture.process.isAlive());
            assertEquals(CodexException.Kind.CLOSED, failure(pending).kind());
        }
    }

    @Test
    void closingBlockedStdinSettlesQueuedNotificationWrites() throws Exception {
        try (Fixture fixture = fixture("hang", Duration.ofSeconds(10))) {
            fixture.rpc.request("initialize", new JsonObject());
            JsonObject large = new JsonObject();
            large.addProperty("padding", "x".repeat(128 * 1024));
            List<CompletableFuture<Void>> sends = new ArrayList<>();
            for (int i = 0; i < 10; i++) {
                sends.add(fixture.rpc.notify("test/notice", large));
            }
            fixture.rpc.closeAsync().get(10, TimeUnit.SECONDS);
            assertTrue(sends.stream().allMatch(CompletableFuture::isDone));
        }
    }

    private Fixture fixture(String mode, Duration timeout) throws Exception {
        Process process = new ProcessBuilder(FakeServerSupport.command(mode, temp.resolve("pid"))).start();
        JsonRpcConnection rpc = new JsonRpcConnection(process, timeout, notification -> { }, error -> { });
        rpc.start();
        return new Fixture(process, rpc);
    }

    private static CodexException failure(CompletableFuture<JsonElement> future) {
        ExecutionException error = assertThrows(ExecutionException.class, () -> future.get(10, TimeUnit.SECONDS));
        return assertInstanceOf(CodexException.class, error.getCause());
    }

    private record Fixture(Process process, JsonRpcConnection rpc) implements AutoCloseable {
        @Override
        public void close() throws Exception {
            rpc.closeAsync().get(10, TimeUnit.SECONDS);
            assertFalse(process.isAlive());
        }
    }
}
