package ghidraagent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;

import ghidra.program.model.address.Address;
import ghidra.program.model.address.AddressSpace;
import ghidra.program.model.address.GenericAddressSpace;
import ghidra.program.model.listing.Function;
import ghidra.program.model.listing.FunctionManager;
import ghidra.program.model.listing.Program;
import ghidra.program.util.ProgramLocation;

class ProgramContextTest {

    private final AddressSpace ram = new GenericAddressSpace("ram", 32, AddressSpace.TYPE_RAM, 0);
    private final AtomicReference<Function> currentFunction = new AtomicReference<>();
    private final AtomicReference<Address> requestedAddress = new AtomicReference<>();

    private final FunctionManager functions = stub(FunctionManager.class, (proxy, method, args) -> {
        if (method.getName().equals("getFunctionContaining")) {
            requestedAddress.set((Address) args[0]);
            return currentFunction.get();
        }
        throw new AssertionError("Unexpected call: " + method);
    });

    @Test
    void noActiveProgramShowsEmptyContext() {
        assertEquals(new ProgramContext(ProgramContext.NO_PROGRAM, ProgramContext.NO_FUNCTION),
                ProgramContext.from(null, null));
    }

    @Test
    void activeProgramWithoutLocationKeepsProgramName() {
        assertEquals(new ProgramContext("sample.bin", ProgramContext.NO_FUNCTION),
                ProgramContext.from(program("sample.bin", false), null));
    }

    @Test
    void locationInsideFunctionDisplaysEntryPointRatherThanCursorAddress() {
        Program program = program("sample.bin", false);
        Address entry = ram.getAddress(0x401000);
        Address cursor = ram.getAddress(0x401024);
        currentFunction.set(function(entry));

        ProgramContext context = ProgramContext.from(program, location(program, cursor));

        assertEquals(new ProgramContext("sample.bin", entry.toString()), context);
        assertSame(cursor, requestedAddress.get());
    }

    @Test
    void locationAtFunctionEntryDisplaysEntryPoint() {
        Program program = program("sample.bin", false);
        Address entry = ram.getAddress(0x401000);
        currentFunction.set(function(entry));

        assertEquals(entry.toString(),
                ProgramContext.from(program, location(program, entry)).functionAddress());
    }

    @Test
    void movingOutsideFunctionClearsPreviousFunctionAddress() {
        Program program = program("sample.bin", false);
        Address entry = ram.getAddress(0x401000);
        currentFunction.set(function(entry));
        ProgramContext previous = ProgramContext.from(program, location(program, entry));

        currentFunction.set(null);
        ProgramContext next = ProgramContext.from(program, location(program, ram.getAddress(0x500000)));

        assertEquals(entry.toString(), previous.functionAddress());
        assertEquals(new ProgramContext("sample.bin", ProgramContext.NO_FUNCTION), next);
    }

    @Test
    void locationFromPreviousProgramIsIgnoredEvenWithSameProgramName() {
        Program active = program("sample.bin", false);
        Program previous = program("sample.bin", false);
        currentFunction.set(function(ram.getAddress(0x401000)));

        assertEquals(new ProgramContext("sample.bin", ProgramContext.NO_FUNCTION),
                ProgramContext.from(active, location(previous, ram.getAddress(0x401024))));
        assertNull(requestedAddress.get());
    }

    @Test
    void missingLocationAddressShowsNoFunction() {
        Program program = program("sample.bin", false);
        ProgramLocation location = new ProgramLocation() {
            @Override
            public Program getProgram() {
                return program;
            }
        };

        assertEquals(new ProgramContext("sample.bin", ProgramContext.NO_FUNCTION),
                ProgramContext.from(program, location));
    }

    @Test
    void closingProgramClearsContextDespiteRemainingLocation() {
        Program closed = program("sample.bin", true);

        assertEquals(new ProgramContext(ProgramContext.NO_PROGRAM, ProgramContext.NO_FUNCTION),
                ProgramContext.from(closed, location(closed, ram.getAddress(0x401000))));
    }

    @Test
    void deactivationIgnoresRemainingLocation() {
        Program previous = program("sample.bin", false);

        assertEquals(new ProgramContext(ProgramContext.NO_PROGRAM, ProgramContext.NO_FUNCTION),
                ProgramContext.from(null, location(previous, ram.getAddress(0x401000))));
    }

    private Program program(String name, boolean closed) {
        return stub(Program.class, (proxy, method, args) -> switch (method.getName()) {
            case "isClosed" -> closed;
            case "getName" -> name;
            case "getFunctionManager" -> functions;
            default -> throw new AssertionError("Unexpected call: " + method);
        });
    }

    private Function function(Address entry) {
        return stub(Function.class, (proxy, method, args) -> {
            if (method.getName().equals("getEntryPoint")) {
                return entry;
            }
            throw new AssertionError("Unexpected call: " + method);
        });
    }

    private ProgramLocation location(Program program, Address cursor) {
        // This constructor accepts the already aligned address without reading a program database.
        return new ProgramLocation(program, cursor, cursor, null, null, 0, 0, 0);
    }

    private static <T> T stub(Class<T> type, InvocationHandler handler) {
        return type.cast(Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type}, handler));
    }
}
