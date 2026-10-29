package ghidraagent;

import java.awt.BorderLayout;
import java.awt.GridLayout;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.JTextField;

import docking.WindowPosition;
import ghidra.framework.plugintool.ComponentProviderAdapter;
import ghidra.framework.plugintool.PluginTool;

/** Swing UI only; construction and updates are performed by the plugin on the EDT. */
final class GhidraAgentProvider extends ComponentProviderAdapter {

    private final JPanel panel;
    private final JLabel programName = new JLabel();
    private final JLabel functionAddress = new JLabel();
    private final CodexConnectionPanel connection = new CodexConnectionPanel();

    GhidraAgentProvider(PluginTool tool, String owner) {
        super(tool, "GhidraAgent", owner);
        setDefaultWindowPosition(WindowPosition.RIGHT);

        // Program names are plain text, even if a binary's name starts with "<html>".
        programName.putClientProperty("html.disable", Boolean.TRUE);
        functionAddress.putClientProperty("html.disable", Boolean.TRUE);

        JPanel context = new JPanel(new GridLayout(2, 2, 8, 4));
        context.add(new JLabel("Program:"));
        context.add(programName);
        context.add(new JLabel("Function address:"));
        context.add(functionAddress);
        functionAddress.setToolTipText("Entry address of the function at the current cursor position");

        JTextArea output = new JTextArea(
                "GhidraAgent – Phase 1.2\n\n" +
                "Codex messages will appear here in a future phase.\n" +
                "Use Connect to check authentication and available models.\n" +
                "Chat messages and GhidraMCP will be added in later phases.",
                14, 40);
        output.setEditable(false);
        output.setLineWrap(true);
        output.setWrapStyleWord(true);
        output.setCaretPosition(0);
        output.getAccessibleContext().setAccessibleName("GhidraAgent output");

        JTextField input = new JTextField();
        JLabel inputLabel = new JLabel("Message:");
        inputLabel.setLabelFor(input);
        input.setToolTipText("Input for future Codex messages");

        JButton send = new JButton("Send");
        send.setEnabled(false);
        send.setToolTipText("Sending is not available in phase 1.2");

        JPanel composer = new JPanel(new BorderLayout(8, 4));
        composer.add(inputLabel, BorderLayout.NORTH);
        composer.add(input, BorderLayout.CENTER);
        composer.add(send, BorderLayout.EAST);

        panel = new JPanel(new BorderLayout(8, 8));
        panel.setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));
        JPanel header = new JPanel(new BorderLayout(8, 8));
        header.add(context, BorderLayout.NORTH);
        header.add(connection, BorderLayout.CENTER);
        panel.add(header, BorderLayout.NORTH);
        panel.add(new JScrollPane(output), BorderLayout.CENTER);
        panel.add(composer, BorderLayout.SOUTH);
        setContext(ProgramContext.from(null, null));
    }

    void setContext(ProgramContext context) {
        programName.setText(context.programName());
        programName.setToolTipText(context.programName());
        functionAddress.setText(context.functionAddress());
    }

    String codexExecutable() {
        return connection.executable();
    }

    void setCodexExecutable(String path) {
        connection.setExecutable(path);
    }

    void dispose() {
        connection.close();
        removeFromTool();
    }

    @Override
    public void closeComponent() {
        connection.disconnect();
        super.closeComponent();
    }

    @Override
    public JComponent getComponent() {
        return panel;
    }
}
