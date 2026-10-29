package ghidraagent;

import ghidra.program.model.listing.Function;
import ghidra.program.model.listing.Program;
import ghidra.program.util.ProgramLocation;

/** Immutable display snapshot; retains no references to Ghidra program resources. */
record ProgramContext(String programName, String functionAddress) {

    static final String NO_PROGRAM = "No program open";
    static final String NO_FUNCTION = "No function selected";

    static ProgramContext from(Program program, ProgramLocation location) {
        if (program == null || program.isClosed()) {
            return new ProgramContext(NO_PROGRAM, NO_FUNCTION);
        }

        String address = NO_FUNCTION;
        // Locations may still refer to the previous program during a program switch.
        if (location != null && location.getProgram() == program && location.getAddress() != null) {
            Function function = program.getFunctionManager().getFunctionContaining(location.getAddress());
            if (function != null) {
                address = function.getEntryPoint().toString();
            }
        }
        return new ProgramContext(program.getName(), address);
    }
}
