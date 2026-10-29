package ghidraagent.codex;

import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

/** Manual, explicit opt-in smoke test; prints only status enums and counts. */
public final class CodexLocalSmokeTest {
    private CodexLocalSmokeTest() { }

    public static void main(String[] args) throws Exception {
        CodexClient client = new CodexClient(snapshot -> { });
        try {
            CodexClient.Snapshot result = client.connect(args[0]).get(125, TimeUnit.SECONDS);
            System.out.println("Handshake: OK");
            System.out.println("Anmeldestatus: " + result.account().name());
            System.out.println("Sichtbare Modelle: " + result.models().size());
            if (result.account() == CodexClient.AccountStatus.LOGIN_REQUIRED) {
                System.out.println("Bitte lokal codex login ausführen und anschließend erneut prüfen.");
            }
        }
        catch (ExecutionException e) {
            String kind = e.getCause() instanceof CodexException safe ? safe.kind().name() : "UNKNOWN";
            // Do not attach causes: future changes must not accidentally print server payloads.
            throw new IllegalStateException("Codex-Verbindungstest fehlgeschlagen: " + kind);
        }
        finally {
            client.closeAsync().get(15, TimeUnit.SECONDS);
            System.out.println("Prozess und Transport freigegeben.");
        }
    }
}
