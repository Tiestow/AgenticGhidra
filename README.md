# GhidraAgent – Phase 1.2

Native Ghidra-Erweiterung mit einem andockbaren Fenster im CodeBrowser und einer
Verbindung zum lokal installierten offiziellen Codex App Server.
Entwickelt und gebaut mit JDK 21 gegen die lokal installierte Ghidra-Version 12.1.4.

Die UI-Sprache ist Englisch. Neue und geänderte UI-Texte, einschließlich
Statusmeldungen, Fehlermeldungen, Tooltips und barrierefreien Bezeichnungen,
müssen ebenfalls auf Englisch formuliert werden.

Das Fenster zeigt den Namen des aktiven Programms und die Einsprungadresse der
Funktion unter dem Cursor. Beim Wechsel zu einer Position außerhalb einer Funktion
oder beim Schließen des Programms wird die Funktionsanzeige zurückgesetzt.
Ein scrollbarer, schreibgeschützter Ausgabebereich enthält Platzhaltertext.
Das Nachrichtenfeld ist editierbar; der Send-Button ist deaktiviert.

Phase 1.2 ergänzt Connect/Disconnect, einen konfigurierbaren Codex-Pfad,
Verbindungs- und Anmeldestatus sowie eine Modell-ComboBox. Die Auswahl bleibt
ausschließlich im UI; sie wird noch nicht an Codex gesendet.

## Aufbau

- `src/main/java/ghidraagent/GhidraAgentPlugin.java`: Plugin-Metadaten,
  Initialisierung, Programm- und Positionsereignisse und Abmeldung des Providers.
  Erstellung, Registrierung, Anzeige, Aktualisierung und Entfernung der Oberfläche
  erfolgen über `Swing.runNow` auf dem Swing Event Dispatch Thread.
  Nur der ausführbare Codex-Pfad wird über `SaveState` in der Tool-Konfiguration gespeichert.
- `src/main/java/ghidraagent/GhidraAgentProvider.java`: `ComponentProviderAdapter`
  mit der Swing-Oberfläche und anfänglicher Andockposition rechts.
  Registrierung über `addToTool`, Entfernung über `removeFromTool`.
  Das Schließen über den Fenster-Schließen-Button trennt Codex und blendet das
  Fenster aus; es bleibt über das Window-Menü erreichbar. Das Deaktivieren des
  Plugins beendet den Client dauerhaft.
- `src/main/java/ghidraagent/CodexConnectionPanel.java`: Verbindungssteuerung,
  Pfadeingabe, Statusanzeigen und Modellauswahl. Asynchrone Client-Ereignisse werden
  mit `SwingUtilities.invokeLater` auf dem EDT angewendet. Bereits eingeplante
  UI-Updates werden nach dem Dispose ignoriert.
- `src/main/java/ghidraagent/codex/CodexClient.java`: Prozessstart über
  `ProcessBuilder`, Schutz gegen parallele Verbindungen, Handshake, Anmeldung,
  Modell-Pagination und asynchrones Trennen. Die unveränderlichen Snapshots
  enthalten ausschließlich für die Oberfläche bestimmte Daten.
- `src/main/java/ghidraagent/codex/JsonRpcConnection.java`: JSONL-Transport,
  eindeutige String-IDs, Zuordnung auch vertauschter Antworten, Notifications,
  serialisierte Schreibvorgänge, separate stdout-/stderr-Verarbeitung und Timeouts.
- `src/main/java/ghidraagent/codex/CodexException.java`: Fehlerkategorien mit
  kontrollierten Meldungen ohne rohe Serverdaten oder verkettete IO-Fehler.
- `src/main/java/ghidraagent/ProgramContext.java`: Unveränderlicher Anzeigekontext
  ohne dauerhafte Referenzen auf Programme oder Funktionen. Die Auflösung verwendet
  `FunctionManager.getFunctionContaining` und `Function.getEntryPoint`; Positionen
  aus einem anderen oder geschlossenen Programm werden verworfen.
- `src/test/java/ghidraagent/ProgramContextTest.java`: Neun isolierte JUnit-Tests
  für Funktionsauflösung, Positionswechsel und fehlende/veraltete Kontexte.
  Die Tests benötigen keine gestartete Ghidra-Anwendung und keine Programmdatenbank.

Die verwendeten APIs wurden anhand der mit Ghidra 12.1.4 ausgelieferten
Quellarchive `Base-src.zip`, `Project-src.zip`, `Docking-src.zip` und
`SoftwareModeling-src.zip` geprüft. `ProgramPlugin` übernimmt das Abonnement der
Programm- und Positionsereignisse. Ghidra liefert einem neu aktivierten Plugin
die vorhandenen Ereignisse nach dessen Initialisierung erneut aus.
Die Programmkontextauflösung und ihre neun Tests wurden unverändert übernommen.

## Codex-Protokoll und Prozessverwaltung

Grundlage sind die [offizielle App-Server-Dokumentation](https://learn.chatgpt.com/docs/app-server)
und die [offizielle Implementierung](https://github.com/openai/codex/tree/main/codex-rs/app-server).
Lokal geprüft wurde **codex-cli 0.165.0**. Die Schemata wurden mit dieser
Installation erzeugt und unter `build/codex-schema` geprüft:

```bash
/absoluter/pfad/zu/codex app-server generate-json-schema --out build/codex-schema
```

Relevant sind `v1/InitializeParams.json`, `v1/InitializeResponse.json`,
`v2/GetAccountParams.json`, `v2/GetAccountResponse.json`,
`v2/ModelListParams.json`, `v2/ModelListResponse.json` und
`v2/AccountUpdatedNotification.json`. Die experimentelle Schema-Generierung ist
ein Entwicklungswerkzeug; der Client aktiviert keine experimentellen APIs.

Erst **Connect** startet direkt `[ausführbare Datei, app-server]`, ohne Shell oder
Argument-Zerlegung. Der Standardtransport ist stdio mit einem JSON-Objekt pro
Zeile; `jsonrpc` wird nicht gesendet. Die Reihenfolge ist:

1. `initialize` mit `clientInfo = {name: ghidra_agent, title: GhidraAgent, version: 0.1.0}`
   und `capabilities.experimentalApi = false`.
2. Erfolgreiche Antwort mit `userAgent` abwarten.
3. `initialized` als Notification ohne Request-ID senden und das Schreiben abwarten.
4. `account/read` mit `refreshToken: false`.
5. `model/list` mit `limit: 100` und `includeHidden: false`; weitere Seiten über
   den unveränderten `nextCursor`. Fehlender oder null-Cursor beendet die Liste.

Die ComboBox verwendet die gelieferten Anzeigenamen. Katalog-ID und Modell-ID
bleiben getrennt erhalten; doppelte Katalog-IDs werden zusammengeführt.
Das Server-Standardmodell wird anfänglich ausgewählt. Es gibt keine feste Modellliste.
Fehlende Anmeldung verhindert die reine Kataloganzeige nicht und erzeugt einen
Hinweis auf `codex login`. Authentifizierung ohne OpenAI-Anmeldung wird gemäß
`requiresOpenaiAuth` angezeigt. `account/updated` aktualisiert die Statusanzeige;
zum vollständigen erneuten Prüfen und Laden der Modelle muss neu verbunden werden.
Unbekannte Notifications werden ignoriert. Serveranfragen werden mit `-32601`
abgelehnt; Freigaben, Token-Refresh durch den Host und andere Serveranfragen sind
in dieser Phase nicht implementiert.

Jeder Request hat 30 Sekunden Timeout, der gesamte Verbindungsaufbau einschließlich
Pagination höchstens zwei Minuten. Wiederholte Cursor und mehr als 100 Folgeseiten
werden abgelehnt; die maximale eingehende JSONL-Zeile beträgt 4 MiB.
EOF, Prozessende, ungültige Nachrichten und IO-/Timeout-Fehler schließen den
Transport und beenden offene Futures mit einem Fehler. Disconnect ist auch
während des Starts möglich; vor vollständigem Beenden kann kein zweiter Prozess
gestartet werden. Nach einem Fehler kann erneut verbunden werden.

Prozessstart, Lesen, Schreiben, Warten und Cleanup laufen außerhalb des EDT.
Beim Trennen werden auch gestartete Kindprozesse beendet. Nach einer Sekunde
wird nötigenfalls erzwungen beendet und bis zu zwei weitere Sekunden gewartet.
Streams, Executor und Timer werden freigegeben; Reader und Writer werden beim
Cleanup abgewartet. Start und Cleanup verwenden kurzlebige Nicht-Daemon-Threads,
damit auch beim Beenden von Ghidra die Prozessfreigabe noch ausgeführt wird.

Codex erbt Umgebung, lokale Konfiguration und Anmeldung. Die Erweiterung liest,
kopiert oder speichert keine Zugangsdaten und implementiert keinen OAuth-Flow.
stderr wird kontinuierlich verworfen. Rohe JSON-Nachrichten, Fehlertexte,
Fehlerdaten, E-Mail-Adressen und Tokens werden nicht angezeigt oder protokolliert.

## Build und Tests

`GHIDRA_INSTALL_DIR` wird wie bisher über `gradle.properties` oder die gleichnamige
Umgebungsvariable gesetzt. Ghidras bestehendes Buildskript und die
Erweiterungsmetadaten bleiben erhalten. JUnit 6.0.0 ist ausschließlich eine
Testabhängigkeit und wird nicht in die Erweiterung gepackt.
Für JSON wird das von Ghidra bereitgestellte **Gson 2.13.2** verwendet;
es wurden keine neuen Bibliotheksabhängigkeiten eingeführt.

```bash
/home/matebook/tools/ghidra_12.1.4_PUBLIC/support/gradle/gradlew buildExtension
/home/matebook/tools/ghidra_12.1.4_PUBLIC/support/gradle/gradlew test
```

Das Archiv liegt unter
`dist/ghidra_12.1.4_PUBLIC_<YYYYMMDD>_GhidraAgent-0.1.2.zip`.
Der Testbericht liegt unter `build/reports/tests/test/index.html`.

Die Tests verwenden `FakeAppServer` als echten lokalen Java-Subprozess mit
simulierten Antworten. Sie laufen ohne Anmeldung, Netzwerk oder Modellanfragen.
`FakeServerSupport` stellt dessen Startkommando zusammen. `JsonRpcConnectionTest`
prüft Serialisierung, IDs, parallele Schreibzugriffe, Protokollfehler, Timeouts,
EOF und Ressourcenfreigabe. `CodexClientTest` prüft Handshake-Reihenfolge,
Authentication, Notifications, Pagination, Prozessabbrüche, Reconnect und
Abbruch während des Starts. Die neun `ProgramContextTest`-Tests bleiben erhalten.

Der reale Verbindungstest ist ausdrücklich separat aufzurufen und läuft nicht
automatisch mit `test` oder `buildExtension`:

```bash
/home/matebook/tools/ghidra_12.1.4_PUBLIC/support/gradle/gradlew codexSmokeTest \
  -PcodexExecutable=/absoluter/pfad/zu/codex
```

`CodexLocalSmokeTest` führt ausschließlich die oben beschriebene Sequenz aus,
gibt nur Status und Modellanzahl aus und beendet den Prozess anschließend.
Es werden keine Threads oder Turns gestartet und keine Modellanfragen gestellt.

Validierung am 29.10.2026: **48 automatisierte Tests erfolgreich**, davon neun
unveränderte Kontexttests. Der reale Test mit Codex 0.165.0 meldete erfolgreichen
Handshake, Anmeldestatus `CHATGPT`, **acht sichtbare Modelle** und abgeschlossene
Prozess-/Transportfreigabe. Die Erweiterung wurde nicht eigenständig installiert.

Für die Validierung dieser Implementierung wurden Gradles Benutzer-Cache und
temporäre JVM-Dateien in das Projekt umgeleitet. Die vorhandenen Wrapper- und
Testabhängigkeiten wurden aus dem lokalen Cache kopiert; der Build lief offline:

```bash
GRADLE_USER_HOME="$PWD/.gradle/phase1-user-home" \
JAVA_TOOL_OPTIONS="-Djava.io.tmpdir=$PWD/build/tmp -XX:-UsePerfData" \
/home/matebook/tools/ghidra_12.1.4_PUBLIC/support/gradle/gradlew \
  --offline --no-daemon --console=plain buildExtension test
```

Diese Variante setzt den bereits vorbereiteten projektinternen Cache und das
Verzeichnis `build/tmp` voraus. Sie verändert keine globale Gradle-Konfiguration.

## Installation in Ghidra 12.1.4

1. Im Ghidra-Projektfenster **File → Install Extensions** öffnen.
2. Über das grüne **+** das ZIP aus `dist` auswählen, die Installation bestätigen
   und den Dialog schließen.
3. Ghidra neu starten.
4. Ein Programm im **CodeBrowser** öffnen und **File → Configure** wählen.
5. Beim Paket **Miscellaneous** auf **Configure** klicken, nach
   **GhidraAgentPlugin** suchen und dessen Checkbox aktivieren.
   Das Plugin trägt den Status **STABLE**.
6. Das Fenster **GhidraAgent** erscheint rechts. Nach dem Ausblenden lässt es sich
   über **Window → GhidraAgent** erneut öffnen. Die Tool-Konfiguration speichern,
   wenn das Plugin auch beim nächsten Start aktiv sein soll.

Eine Installation wurde während der Implementierung nicht vorgenommen.

## Manuelle Prüfung nach der Installation

- Ohne geöffnetes Programm: „No program open“ und „No function selected“.
- Programm öffnen: Der Programmname erscheint.
- Innerhalb einer definierten Funktion navigieren: Die Einsprungadresse bleibt
  auch beim Navigieren im Funktionskörper konstant.
- Außerhalb einer Funktion navigieren: „No function selected“.
- Zwischen Programmen wechseln und das letzte Programm schließen:
  Programm- und Funktionsanzeige folgen dem aktiven Kontext.
- Fenster schließen, über **Window** erneut öffnen und das Plugin über
  **File → Configure** deaktivieren/reaktivieren: keine doppelten Provider.
- Ausgabe ist scrollbar und schreibgeschützt; das Eingabefeld ist editierbar,
  **Send** bleibt deaktiviert.

Zusätzlich für Phase 1.2:

1. Vor Connect darf kein App-Server-Prozess gestartet werden. Zunächst wird
   „Disconnected“ angezeigt; Disconnect und die leere Modell-ComboBox sind deaktiviert.
2. Den absoluten Pfad der vorhandenen Codex-Installation in **Codex executable** eintragen.
   In der geprüften Umgebung ist `codex` nicht im PATH. Verwendet wurde:

   ```text
   /home/matebook/.cache/JetBrains/IntelliJIdea2026.2/acp-agents/codex-acp/2.4.0/node_modules/@openai/codex-linux-x64/vendor/x86_64-unknown-linux-musl/bin/codex
   ```

   Der Pfad kann sich bei einem IDE-/Codex-Update ändern. Die Datei muss ausführbar
   sein. Keine Shell-Anführungszeichen oder zusätzlichen Argumente eintragen.
3. **Connect** wählen: Die GUI bleibt bedienbar, Connect/Pfad werden deaktiviert,
   Disconnect ist aktiv. Programm- und Funktionsanzeige funktionieren weiter.
4. Nach dem Aufbau müssen Verbindungsstatus, lokale Anmeldung und die tatsächlich
   gelieferten Modellnamen erscheinen. Ein anderes Modell wählen; **Send** bleibt
   deaktiviert und es wird keine Konversation angelegt.
5. **Disconnect** wählen: Modelle werden geleert, der Prozess wird beendet,
   anschließend kann erneut verbunden werden. Mehrfachklicks dürfen keinen
   zusätzlichen Prozess erzeugen. Auch während des Aufbaus trennen.
6. Einen ungültigen Codex-Pfad ausprobieren: verständlicher Fehler, kein hängender
   Verbindungszustand. Danach mit korrektem Pfad erneut verbinden.
7. Fehlende Anmeldung nur in einer separaten, bereits dafür vorbereiteten
   Testumgebung prüfen; die produktive Anmeldung dafür nicht abmelden.
   Erwartet wird der Hinweis auf `codex login`.
8. Den gestarteten App Server extern beenden: Fehleranzeige, leere Modellliste,
   anschließend erneuter Verbindungsaufbau möglich.
9. Verbunden den Fenster-Schließen-Button betätigen bzw. das Plugin deaktivieren:
   Prozess muss beendet werden. Das Fenster über **Window → GhidraAgent** erneut
   öffnen bzw. das Plugin reaktivieren: keine doppelten Provider oder Prozesse.
10. Tool-Konfiguration speichern und neu öffnen: Codex-Pfad bleibt erhalten,
    die Verbindung wird nicht automatisch aufgebaut. Die Modellauswahl wird nicht persistiert.

## Einschränkungen

- Kein `thread/start`, `turn/start`, Chat, Antwortstreaming, GhidraMCP oder Änderungen
  an Ghidra-Datenbanken. Send bleibt deaktiviert.
- `account/read` prüft die von Codex gemeldete Anmeldung. Es erfolgt kein eigener
  Test der Token-Gültigkeit durch eine Modellanfrage und kein erzwungener Token-Refresh.
- Bei Änderungen an Login oder Modellkatalog neu verbinden. Nur der Codex-Pfad
  wird gespeichert, keine Sitzung oder Modellauswahl.
- Die Funktionsadresse folgt der Cursorposition im aktiven Programm,
  nicht einer markierten Adressmenge oder beliebigen Symbolauswahl.
- Änderungen an Funktionsdefinitionen oder Programmnamen werden beim nächsten
  Programm- oder Positionsereignis sichtbar; dafür gibt es noch keinen eigenen Listener.
- Automatisiert geprüft sind Kontext, Client und Transport; zusätzlich wurde der
  reale App Server getestet. Die neue Oberfläche muss noch in einer laufenden
  Ghidra-GUI manuell geprüft werden. Die Phase-1.1-GUI-Prüfung war laut Projektstand erfolgreich.
- Geprüft wurde gegen Ghidra 12.1.4, Java 21 und Codex CLI 0.165.0. Andere
  Codex-Versionen müssen dieselben stabilen Methoden und verwendeten Felder unterstützen.
- Das bereitgestellte `.git`-Verzeichnis enthält hier keine Repository-Metadaten;
  ein Git-Diff war daher weder per Shell noch über die IDE verfügbar.
