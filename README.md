# AgenticGhidra

AgenticGhidra is a native Ghidra extension that integrates existing AI coding agents into reverse engineering workflows. Its long-term goal is to make reverse engineering more accessible and orchestrate larger analysis processes automatically. Codex is the first integration; the longer-term architecture is intended to support additional coding agents.

## Current features

- A dockable CodeBrowser panel displays the active program and the entry address of the function at the cursor. The function display resets outside a function or when the program closes.
- Connect to a locally installed Codex App Server, inspect connection and account status, and select from the server's available models.
- Configure the Codex executable path, which is saved in the Ghidra tool configuration.
- A scrollable, read-only output area and editable message field provide placeholders for future chat functionality. **Send** is disabled, and model selection is not sent to Codex.

## Requirements

The extension was developed and built against Ghidra 12.1.4 with JDK 21 and tested with Codex CLI 0.165.0. Other Codex versions must support the stable methods and fields used by the client. A local Codex executable is required for the connection; authentication is managed by Codex.

## Build and installation

Set `GHIDRA_INSTALL_DIR` in `gradle.properties` or as an environment variable to the Ghidra installation directory. Build the extension and run the tests with Ghidra's Gradle wrapper:

```bash
"$GHIDRA_INSTALL_DIR/support/gradle/gradlew" buildExtension
"$GHIDRA_INSTALL_DIR/support/gradle/gradlew" test
```

If the path is set only in `gradle.properties`, substitute the installation path for `$GHIDRA_INSTALL_DIR` in these commands. The archive is written to `dist/ghidra_12.1.4_PUBLIC_<YYYYMMDD>_GhidraAgent-0.1.2.zip`; the test report is at `build/reports/tests/test/index.html`. The archive name depends on the Ghidra version and build date.

1. In the Ghidra project window, open **File → Install Extensions**.
2. Select the ZIP in `dist` using the green **+**, confirm installation, and close the dialog.
3. Restart Ghidra, open a program in the CodeBrowser, and select **File → Configure**.
4. Under **Miscellaneous**, select **Configure**, find **GhidraAgentPlugin**, and enable it.
5. The panel appears on the right as **GhidraAgent**. Reopen it through **Window → GhidraAgent** if hidden. Save the tool configuration to keep the plugin enabled across restarts.

The panel and plugin names above are existing Ghidra identifiers.

## Configuration and usage

Enter `codex` if it is on PATH, or enter the absolute path to the executable in **Codex executable**. Do not include shell quotes or arguments. Select **Connect** to start the local app server and load account status and models. If login is required, run `codex login` locally. Select **Disconnect** to stop the process; reconnect to refresh the account and model catalog. Closing the panel also disconnects, while disabling the plugin shuts down the client.

The executable path persists in the tool configuration. Connections and model selections are not restored automatically. The function address follows the cursor in the active program; changes to function definitions or program names appear on the next program or location event.

## Architecture

- `GhidraAgentPlugin` handles plugin lifecycle and program and location events. `GhidraAgentProvider` hosts the Swing interface; `CodexConnectionPanel` handles connection controls and status updates on the event dispatch thread.
- `ProgramContext` resolves the function containing the current location without retaining program or function references.
- `CodexClient` manages the process, handshake, account status, model pagination and cleanup. `JsonRpcConnection` implements JSONL transport, request matching, notifications and timeouts. `CodexException` provides controlled error messages.

Connect launches the executable directly with `app-server`, without a shell. The stdio transport uses one JSON object per line and does not send `jsonrpc`. The handshake sends `initialize` with `clientInfo = {name: ghidra_agent, title: GhidraAgent, version: 0.1.0}` and `capabilities.experimentalApi = false`, waits for `userAgent`, then sends `initialized`, `account/read` with `refreshToken: false`, and paginated `model/list` requests with `limit: 100` and `includeHidden: false`. Unknown notifications are ignored; server requests are rejected with `-32601`.

Requests time out after 30 seconds, and connection setup after two minutes. Repeated cursors, more than 100 subsequent pages and incoming lines over 4 MiB are rejected. Disconnect also stops child processes. Codex inherits the local environment, configuration and login; the extension does not store credentials or implement OAuth. Raw messages, server errors, email addresses and tokens are neither displayed nor logged; stderr is discarded.

The implementation follows the [Codex App Server documentation](https://learn.chatgpt.com/docs/app-server) and [implementation](https://github.com/openai/codex/tree/main/codex-rs/app-server). Schemas can be inspected with:

```bash
/absolute/path/to/codex app-server generate-json-schema --out build/codex-schema
```

Schema generation is a development tool; the client does not enable experimental APIs. Ghidra supplies Gson 2.13.2; JUnit 6.0.0 is a test-only dependency.

## Development status and testing

The project is at phase 1.2. Automated tests cover program context, transport, handshake, authentication, pagination, process failures and cleanup using a local fake app-server subprocess. They do not require login, network access or model requests. A previous validation reported 48 passing tests and a successful real handshake with Codex CLI 0.165.0; the phase 1.2 interface still needs manual verification in a running Ghidra GUI.

The real connection smoke test is opt-in and is not run by `test` or `buildExtension`:

```bash
"$GHIDRA_INSTALL_DIR/support/gradle/gradlew" codexSmokeTest \
  -PcodexExecutable=/absolute/path/to/codex
```

It reports status and model count and releases the process without starting threads, turns or model requests. `account/read` reports Codex's account state; it does not independently validate a token through a model request or force a token refresh.

## Roadmap

Planned work includes chat and response streaming, GhidraMCP integration, support for additional coding agents, and orchestration of larger analysis workflows. None of these are implemented yet. There are currently no `thread/start` or `turn/start` requests or changes to Ghidra databases.

## Contributing

Contributions are welcome. Keep user-facing text, documentation, comments and error messages in English. Run the automated tests and build the extension before submitting changes; describe any manual verification and compatibility considerations with your contribution.
