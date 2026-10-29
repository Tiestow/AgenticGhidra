package ghidraagent.codex;

/** Contains only controlled diagnostic text, never server payloads, stderr or credentials. */
public final class CodexException extends RuntimeException {
    public enum Kind { START, IO, EOF, PROCESS_EXIT, PROTOCOL, SERVER, TIMEOUT, CLOSED }

    private final Kind kind;

    CodexException(Kind kind, String safeMessage) {
        super(safeMessage);
        this.kind = kind;
    }

    public Kind kind() {
        return kind;
    }

    static CodexException protocol() {
        return new CodexException(Kind.PROTOCOL, "Invalid response from the Codex App Server.");
    }

    static CodexException closed() {
        return new CodexException(Kind.CLOSED, "The Codex connection was closed.");
    }

    static CodexException timeout() {
        return new CodexException(Kind.TIMEOUT, "The Codex connection timed out.");
    }
}
