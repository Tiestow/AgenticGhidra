package ghidraagent.codex;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/** Standalone subprocess fixture. No network, credentials, Ghidra or model calls. */
public final class FakeAppServer {
    private static final BufferedReader INPUT = new BufferedReader(
            new InputStreamReader(System.in, StandardCharsets.UTF_8));
    private static final PrintWriter OUTPUT = new PrintWriter(System.out, true, StandardCharsets.UTF_8);

    private FakeAppServer() { }

    public static void main(String[] args) throws Exception {
        String mode = args[0];
        if (mode.equals("ignore-termination")) {
            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                try {
                    Thread.sleep(30_000);
                }
                catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }));
        }
        if (args.length > 1) {
            Files.writeString(Path.of(args[1]), Long.toString(ProcessHandle.current().pid()));
        }
        JsonObject first = read();
        switch (mode) {
            case "hang" -> {
                Thread.sleep(60_000);
                return;
            }
            case "exit" -> {
                System.exit(17);
                return;
            }
            case "eof" -> {
                System.out.close();
                Thread.sleep(60_000);
                return;
            }
            case "malformed" -> {
                OUTPUT.println("{\"id\":");
                idle();
                return;
            }
            case "lenient-json" -> {
                OUTPUT.println("{'id':'" + first.get("id").getAsString() + "','result':{}}");
                idle();
                return;
            }
            case "bad-envelope" -> {
                JsonObject reply = new JsonObject();
                reply.add("id", first.get("id"));
                reply.add("result", new JsonObject());
                reply.add("error", new JsonObject());
                OUTPUT.println(reply);
                idle();
                return;
            }
            case "unknown-id" -> {
                OUTPUT.println("{\"id\":\"unknown\",\"result\":{}}");
                idle();
                return;
            }
            case "error", "bad-error" -> {
                JsonObject reply = new JsonObject();
                reply.add("id", first.get("id"));
                JsonObject error = new JsonObject();
                if (mode.equals("error")) {
                    error.addProperty("code", -32001);
                }
                error.addProperty("message", "SENSITIVE_TEST_MARKER");
                error.addProperty("data", "SENSITIVE_TEST_MARKER");
                reply.add("error", error);
                OUTPUT.println(reply);
                idle();
                return;
            }
            case "reverse" -> {
                JsonObject second = read();
                respond(second, second.get("params"));
                respond(first, first.get("params"));
                idle();
                return;
            }
            case "echo" -> {
                do {
                    require(!first.has("jsonrpc") && first.has("method") && first.has("id"));
                    respond(first, first.get("params"));
                } while ((first = read()) != null);
                return;
            }
            default -> { }
        }

        require(first.get("method").getAsString().equals("initialize") && !first.has("jsonrpc"));
        JsonObject params = first.getAsJsonObject("params");
        JsonObject info = params.getAsJsonObject("clientInfo");
        require(info.get("name").getAsString().equals("ghidra_agent"));
        require(info.get("title").getAsString().equals("GhidraAgent"));
        require(info.get("version").getAsString().equals("0.1.0"));
        require(!params.getAsJsonObject("capabilities").get("experimentalApi").getAsBoolean());
        if (mode.equals("stderr-flood")) {
            System.err.write(new byte[512 * 1024]);
            System.err.println("SENSITIVE_TEST_MARKER");
            System.err.flush();
        }
        if (mode.equals("notification")) {
            OUTPUT.println("{\"method\":\"test/notice\",\"params\":{\"status\":\"ok\"}}");
        }
        if (mode.equals("server-request")) {
            OUTPUT.println("{\"method\":\"unsupported/request\",\"id\":12,\"params\":{}}");
            JsonObject rejection = read();
            require(rejection.get("id").getAsInt() == 12);
            require(rejection.getAsJsonObject("error").get("code").getAsInt() == -32601);
            require(!rejection.has("jsonrpc"));
        }
        respond(first, JsonParser.parseString(mode.equals("missing-init") ? "{}" :
                "{\"userAgent\":\"fake/1\",\"platformFamily\":\"unix\",\"platformOs\":\"linux\",\"codexHome\":\"/unused\"}"));
        JsonObject initialized = read();
        if (initialized == null) {
            return;
        }
        require(initialized.get("method").getAsString().equals("initialized"));
        require(!initialized.has("id") && !initialized.has("jsonrpc"));
        JsonObject account = read();
        require(account.get("method").getAsString().equals("account/read"));
        require(!account.getAsJsonObject("params").get("refreshToken").getAsBoolean());
        if (mode.equals("auth-error")) {
            JsonObject error = new JsonObject();
            error.addProperty("code", -32001);
            error.addProperty("message", "SENSITIVE_TEST_MARKER");
            JsonObject response = new JsonObject();
            response.add("id", account.get("id"));
            response.add("error", error);
            OUTPUT.println(response);
            idle();
            return;
        }
        String auth = switch (mode) {
            case "auth-null" -> "{\"account\":null,\"requiresOpenaiAuth\":true}";
            case "auth-optional" -> "{\"account\":null,\"requiresOpenaiAuth\":false}";
            case "auth-api" -> "{\"account\":{\"type\":\"apiKey\"},\"requiresOpenaiAuth\":true}";
            case "auth-unknown" -> "{\"account\":{\"type\":\"unrecognized\"},\"requiresOpenaiAuth\":true}";
            case "auth-bad" -> "{\"account\":{},\"requiresOpenaiAuth\":true}";
            case "auth-bad-boolean" -> "{\"requiresOpenaiAuth\":\"true\"}";
            default -> "{\"account\":{\"type\":\"chatgpt\",\"email\":null,\"planType\":\"unknown\"},\"requiresOpenaiAuth\":true}";
        };
        respond(account, JsonParser.parseString(auth));
        int page = 0;
        JsonObject request;
        while ((request = read()) != null) {
            require(request.get("method").getAsString().equals("model/list"));
            require(!request.getAsJsonObject("params").get("includeHidden").getAsBoolean());
            if (mode.equals("slow-pages")) {
                Thread.sleep(150);
            }
            if (mode.equals("early-account-notification")) {
                OUTPUT.println("{\"method\":\"account/updated\",\"params\":{\"authMode\":null}}");
            }
            if (mode.equals("bad-models")) {
                respond(request, JsonParser.parseString("{\"data\":{}}"));
                idle();
                return;
            }
            if (mode.equals("pagination") && page == 1) {
                require(request.getAsJsonObject("params").get("cursor").getAsString().equals("opaque/+cursor"));
            }
            String data = mode.equals("empty-models") ? "[]" :
                    page == 0 ? "[" + model("catalog-a", "wire-a", "Model A", false) + "]" :
                    "[" + model("catalog-a", "wire-a", "Model A", false) + "," +
                    model("catalog-b", "wire-b", "Model B", true) + "]";
            String next = (mode.equals("pagination") && page == 0) || mode.equals("loop")
                    ? ",\"nextCursor\":\"opaque/+cursor\"" :
                    mode.equals("slow-pages") ? ",\"nextCursor\":\"page-" + page + "\"" : "";
            respond(request, JsonParser.parseString("{\"data\":" + data + next + "}"));
            page++;
            if (next.isEmpty()) {
                if (mode.equals("account-notification")) {
                    OUTPUT.println("{\"method\":\"account/updated\",\"params\":{\"authMode\":null}}");
                }
                if (mode.equals("exit-after-connect")) {
                    Thread.sleep(150);
                    System.exit(23);
                }
                idle();
                return;
            }
        }
    }

    private static String model(String id, String model, String label, boolean isDefault) {
        return "{\"id\":\"" + id + "\",\"model\":\"" + model + "\",\"displayName\":\"" + label +
                "\",\"isDefault\":" + isDefault + ",\"hidden\":false,\"description\":\"Test model\"," +
                "\"defaultReasoningEffort\":\"medium\",\"supportedReasoningEfforts\":[]}";
    }

    private static JsonObject read() throws Exception {
        String line = INPUT.readLine();
        return line == null ? null : JsonParser.parseString(line).getAsJsonObject();
    }

    private static void respond(JsonObject request, JsonElement result) {
        JsonObject response = new JsonObject();
        response.add("id", request.get("id"));
        response.add("result", result);
        OUTPUT.println(response);
    }

    private static void idle() throws Exception {
        while (INPUT.readLine() != null) {
            // Wait until the client closes or destroys the process.
        }
    }

    private static void require(boolean condition) {
        if (!condition) {
            // Avoid dumping the offending protocol message, even in a failing subprocess.
            throw new IllegalStateException("Unexpected protocol sequence in fixture");
        }
    }
}
