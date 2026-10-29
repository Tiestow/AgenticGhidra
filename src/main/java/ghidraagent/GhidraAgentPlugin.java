package ghidraagent;

import ghidra.MiscellaneousPluginPackage;
import ghidra.app.plugin.PluginCategoryNames;
import ghidra.app.plugin.ProgramPlugin;
import ghidra.framework.plugintool.PluginInfo;
import ghidra.framework.plugintool.PluginTool;
import ghidra.framework.options.SaveState;
import ghidra.framework.plugintool.util.PluginStatus;
import ghidra.program.model.listing.Program;
import ghidra.program.util.ProgramLocation;
import ghidra.util.Swing;

/** Owns the provider and forwards Ghidra's active program context to the UI. */
@PluginInfo(
        status = PluginStatus.STABLE,
        packageName = MiscellaneousPluginPackage.NAME,
        category = PluginCategoryNames.COMMON,
        shortDescription = "GhidraAgent",
        description = "Displays the active program and function in the GhidraAgent window. " +
                "Connects to the local Codex App Server to inspect authentication and available models.")
public class GhidraAgentPlugin extends ProgramPlugin {

    // Accessed only on the EDT, including during initialization and disposal.
    private GhidraAgentProvider provider;

    public GhidraAgentPlugin(PluginTool tool) {
        super(tool);
    }

    @Override
    protected void init() {
        super.init();
        Swing.runNow(() -> {
            provider = new GhidraAgentProvider(tool, getName());
            provider.addToTool();
            provider.setVisible(true);
        });
        // Ghidra replays existing program/location events after plugin initialization.
    }

    @Override
    protected void programActivated(Program program) {
        updateContext(program, null);
    }

    @Override
    protected void programDeactivated(Program program) {
        updateContext(null, null);
    }

    @Override
    protected void locationChanged(ProgramLocation location) {
        updateContext(currentProgram, location);
    }

    private void updateContext(Program program, ProgramLocation location) {
        Swing.runNow(() -> {
            if (provider != null) {
                provider.setContext(ProgramContext.from(program, location));
            }
        });
    }

    @Override
    public void readConfigState(SaveState saveState) {
        super.readConfigState(saveState);
        Swing.runNow(() -> {
            if (provider != null) {
                provider.setCodexExecutable(saveState.getString("CodexExecutable", "codex"));
            }
        });
    }

    @Override
    public void writeConfigState(SaveState saveState) {
        super.writeConfigState(saveState);
        Swing.runNow(() -> {
            if (provider != null) {
                saveState.putString("CodexExecutable", provider.codexExecutable());
            }
        });
    }

    @Override
    protected void dispose() {
        Swing.runNow(() -> {
            if (provider != null) {
                provider.dispose();
                provider = null;
            }
        });
        super.dispose();
    }
}
