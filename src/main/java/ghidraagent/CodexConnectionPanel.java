package ghidraagent;

import java.awt.BorderLayout;
import java.awt.GridLayout;
import java.util.List;

import javax.swing.DefaultComboBoxModel;
import javax.swing.DefaultListCellRenderer;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.SwingUtilities;

import ghidraagent.codex.CodexClient;
import ghidraagent.codex.CodexClient.Model;
import ghidraagent.codex.CodexClient.Snapshot;
import ghidraagent.codex.CodexClient.State;

/** Connection controls. Every Swing access, including disposal, belongs on the EDT. */
final class CodexConnectionPanel extends JPanel implements AutoCloseable {
    private final JTextField executable = new JTextField("codex", 24);
    private final JButton connect = new JButton("Connect");
    private final JButton disconnect = new JButton("Disconnect");
    private final JLabel status = new JLabel();
    private final JLabel account = new JLabel();
    private final JComboBox<Model> models = new JComboBox<>();
    private final JTextArea message = new JTextArea(2, 40);
    private final CodexClient client;
    private List<Model> displayedModels = List.of();
    private boolean disposed;

    CodexConnectionPanel() {
        super(new BorderLayout(4, 4));
        client = new CodexClient(update -> SwingUtilities.invokeLater(() -> {
            if (!disposed) {
                apply(update);
            }
        }));

        JLabel executableLabel = new JLabel("Codex executable:");
        executableLabel.setLabelFor(executable);
        executable.setToolTipText("codex on PATH or the absolute path to the Codex executable; no arguments");
        JPanel path = new JPanel(new BorderLayout(4, 4));
        path.add(executableLabel, BorderLayout.WEST);
        path.add(executable, BorderLayout.CENTER);

        JPanel buttons = new JPanel(new GridLayout(1, 2, 4, 0));
        buttons.add(connect);
        buttons.add(disconnect);
        JPanel controls = new JPanel(new BorderLayout(4, 4));
        controls.add(path, BorderLayout.NORTH);
        controls.add(buttons, BorderLayout.CENTER);

        JPanel details = new JPanel(new GridLayout(3, 1, 4, 4));
        details.add(status);
        details.add(account);
        JPanel modelRow = new JPanel(new BorderLayout(4, 4));
        JLabel modelLabel = new JLabel("Model:");
        modelLabel.setLabelFor(models);
        modelRow.add(modelLabel, BorderLayout.WEST);
        modelRow.add(models, BorderLayout.CENTER);
        DefaultListCellRenderer renderer = new DefaultListCellRenderer();
        renderer.putClientProperty("html.disable", Boolean.TRUE);
        models.setRenderer(renderer);
        details.add(modelRow);

        message.setEditable(false);
        message.setLineWrap(true);
        message.setWrapStyleWord(true);
        message.setOpaque(false);
        message.getAccessibleContext().setAccessibleName("Codex connection message");

        add(controls, BorderLayout.NORTH);
        add(details, BorderLayout.CENTER);
        add(message, BorderLayout.SOUTH);
        connect.addActionListener(event -> {
            if (executable.getText().isBlank()) {
                message.setText("Enter the path to the Codex executable.");
                return;
            }
            client.connect(executable.getText().trim());
        });
        disconnect.addActionListener(event -> client.disconnect());
        apply(client.snapshot());
    }

    private void apply(Snapshot snapshot) {
        boolean idle = snapshot.state() == State.DISCONNECTED || snapshot.state() == State.ERROR;
        connect.setEnabled(idle);
        executable.setEnabled(idle);
        disconnect.setEnabled(snapshot.state() == State.CONNECTED || snapshot.state() == State.CONNECTING);
        status.setText("Connection: " + switch (snapshot.state()) {
            case DISCONNECTED -> "Disconnected";
            case CONNECTING -> "Connecting …";
            case CONNECTED -> "Connected";
            case DISCONNECTING -> "Disconnecting …";
            case ERROR -> "Error";
        });
        account.setText("Account: " + switch (snapshot.account()) {
            case UNKNOWN -> "Not checked";
            case CHATGPT -> "ChatGPT (local)";
            case API_KEY -> "API key (local)";
            case EXTERNAL_PROVIDER -> "External provider";
            case LOGIN_REQUIRED -> "Required – run codex login";
            case UNSUPPORTED -> "Unsupported authentication type";
        });
        message.setText(snapshot.message());
        message.setCaretPosition(0);
        if (!displayedModels.equals(snapshot.models())) {
            String selectedId = selectedModel() == null ? null : selectedModel().id();
            displayedModels = snapshot.models();
            models.setModel(new DefaultComboBoxModel<>(displayedModels.toArray(Model[]::new)));
            Model selected = displayedModels.stream().filter(model -> model.id().equals(selectedId))
                    .findFirst().orElseGet(() -> displayedModels.stream().filter(Model::isDefault)
                            .findFirst().orElse(displayedModels.isEmpty() ? null : displayedModels.getFirst()));
            models.setSelectedItem(selected);
        }
        models.setEnabled(snapshot.state() == State.CONNECTED && !displayedModels.isEmpty());
    }

    String executable() {
        return executable.getText();
    }

    void setExecutable(String path) {
        executable.setText(path);
    }

    Model selectedModel() {
        return (Model) models.getSelectedItem();
    }

    void disconnect() {
        client.disconnect();
    }

    @Override
    public void close() {
        disposed = true;
        client.closeAsync();
    }
}
