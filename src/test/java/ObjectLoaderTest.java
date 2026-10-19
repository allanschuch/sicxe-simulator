import assembler.*;
import controller.MachineController;
import controller.MachineStateListener;
import loader.ObjectLoader;
import model.machine.SICXEMachine;
import model.types.ConditionCode;
import model.types.Register;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class ObjectLoaderTest {
    /** O campo de posição de M é relativo ao início do programa (Beck, seção 2.2.2). */
    @Test
    void modificationPositionUsesProgramOffsetWithNonzeroStart() {
        String object = "HP     001000000007\n" + "T001000070310100400002A\n" + "M00000105\n" + "E001000\n";
        SICXEMachine vm = new SICXEMachine();
        new ObjectLoader().load(ObjectProgram.parse(object), vm, 0x5000);
        vm.step();
        assertEquals(42, vm.getRegisters().get(Register.A).toIntUnsigned());

        ObjectProgram assembled = program("P START 1000\nFIRST +LDA VALUE\nVALUE WORD 42\nEND FIRST");
        assertTrue(assembled.serialize().contains("M00000105"));
    }

    private ObjectProgram program(String source) {
        AssemblyResult result = new TwoPassAssembler().assemble(source);
        assertTrue(result.isSuccess(), () -> result.getDiagnostics().toString());
        return result.getProgram();
    }

    @Test
    void objectRoundTripAndCaretNotation() {
        ObjectProgram program = program("P START 1000\nFIRST +LDA VALUE\nVALUE WORD 1\nEND FIRST");
        assertEquals(program.serialize(), ObjectProgram.parse(program.serialize()).serialize());
        assertEquals("HP     000000000003\nT000000034F0000\nE000000\n", ObjectProgram
                .parse("H^P     ^000000^000003\nT^000000^03^4F0000\nE^000000").serialize().replace("\r\n", "\n"));
    }

    @Test
    void malformedObjectsAreRejected() {
        for (String object : List.of("", "HP     000000000003", "T000000034F0000\nE000000",
                "HP     000000000003\nT000000024F0000\nE000000",
                "HP     000000000003\nT000000034F0000\nM00000105\nE000000",
                "HP     000000000003\nT000000034F0000\nE000000\nE000000",
                "HP     000000000003\nT000000034F0000\nT000000034F0000\nE000000",
                "HP     000000000003\nT000000034F0000\nE000002X")) {
            assertThrows(IllegalArgumentException.class, () -> ObjectProgram.parse(object), object);
        }
    }

    @Test
    void overlappingAndUnsupportedModificationsAreRejected() {
        assertThrows(IllegalArgumentException.class, () -> new ObjectProgram("P", 0, 4, 0,
                List.of(new ObjectProgram.TextRecord(0, new byte[4])),
                List.of(new ObjectProgram.ModificationRecord(1, 5), new ObjectProgram.ModificationRecord(1, 5))));
        assertThrows(IllegalArgumentException.class,
                () -> new ObjectProgram("P", 0, 4, 0, List.of(new ObjectProgram.TextRecord(0, new byte[4])),
                        List.of(new ObjectProgram.ModificationRecord(1, 4))));
    }

    @Test
    void invalidLoadIsAtomic() {
        SICXEMachine vm = new SICXEMachine();
        vm.getMemory().writeByte(0, 99);
        vm.getRegisters().setPC(123);
        ObjectProgram program = program("P START 0\nFIRST RSUB\nEND FIRST");
        assertThrows(IllegalArgumentException.class, () -> new ObjectLoader().load(program, vm, 0xFFFFF));
        assertEquals(99, vm.getMemory().readByte(0));
        assertEquals(123, vm.getRegisters().getPC());
    }

    @Test
    void relocationOverflowDoesNotClearVm() {
        ObjectProgram program = new ObjectProgram("P", 0, 4, 0,
                List.of(new ObjectProgram.TextRecord(0, new byte[] { 3, 0x1F, (byte) 0xFF, (byte) 0xFF })),
                List.of(new ObjectProgram.ModificationRecord(1, 5)));
        SICXEMachine vm = new SICXEMachine();
        vm.getMemory().writeByte(0, 99);
        assertThrows(IllegalArgumentException.class, () -> new ObjectLoader().load(program, vm, 1));
        assertEquals(99, vm.getMemory().readByte(0));
    }

    @Test
    void resetPreservesLoadedEntryAndMemoryAndClearsConditionCode() {
        SICXEMachine vm = new SICXEMachine();
        MachineController controller = new MachineController(vm);
        ObjectProgram program = program("P START 1000\nDATA WORD 7\nFIRST LDA DATA\nEND FIRST");
        assertTrue(controller.loadProgram(program, 0x5000));
        assertEquals(0x5003, vm.getRegisters().getPC());
        controller.step();
        vm.getRegisters().setConditionCode(ConditionCode.EQUAL);
        controller.reset();
        assertEquals(0x5003, vm.getRegisters().getPC());
        assertEquals(7, vm.getMemory().readWord(0x5000).toIntUnsigned());
        assertEquals(0, vm.getRegisters().get(Register.A).toIntUnsigned());
        assertEquals(ConditionCode.NONE, vm.getRegisters().getConditionCode());
        assertNull(vm.getLastExecutedInstruction());
    }

    @Test
    void rawHexRejectsObjectAndInvalidDataWithoutChangingMemory() {
        SICXEMachine vm = new SICXEMachine();
        MachineController controller = new MachineController(vm);
        int[] errors = { 0 };
        controller.setMachineStateListener(new MachineStateListener() {

            public void onMachineStateChanged() {
            }

            public void onMachineError(String message) {
                errors[0]++;
            }
        });
        controller.loadHexCode("01 00 05");
        assertEquals(1, vm.getMemory().readByte(0));
        for (String text : List.of("", "ABC", "01 QQ 05", "HP     000000000003\nT000000034F0000\nE000000")) {
            controller.loadHexCode(text);
        }
        assertEquals(4, errors[0]);
        assertEquals(1, vm.getMemory().readByte(0));
    }

    @Test
    void objectByteArraysAreDefensivelyCopied() {
        byte[] source = { 0x4F, 0, 0 };
        var text = new ObjectProgram.TextRecord(0, source);
        source[0] = 0;
        assertEquals(0x4F, text.getBytes()[0]);
        byte[] copy = text.getBytes();
        copy[0] = 0;
        assertEquals(0x4F, text.getBytes()[0]);
    }

    @Test
    void failedAssemblyCannotBeLoaded() {
        SICXEMachine vm = new SICXEMachine();
        MachineController controller = new MachineController(vm);
        vm.getMemory().writeByte(0, 99);
        assertFalse(controller.loadAssembly(new TwoPassAssembler().assemble("FIRST LDA UNKNOWN\nEND FIRST")));
        assertEquals(99, vm.getMemory().readByte(0));
    }

    @Test
    void loadingWhileRunningStopsOldExecution() throws Exception {
        SICXEMachine vm = new SICXEMachine();
        MachineController controller = new MachineController(vm);
        java.util.concurrent.CountDownLatch executed = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.atomic.AtomicInteger errors = new java.util.concurrent.atomic.AtomicInteger();
        controller.setMachineStateListener(new MachineStateListener() {

            public void onMachineStateChanged() {
                if (vm.getLastExecutedInstruction() != null) {
                    executed.countDown();
                }
            }

            public void onMachineError(String message) {
                errors.incrementAndGet();
            }
        });
        controller.setDelayMs(1);
        assertTrue(controller.loadProgram(program("P START 0\nFIRST J FIRST\nEND FIRST"), 0));
        controller.run();
        assertTrue(executed.await(2, java.util.concurrent.TimeUnit.SECONDS));
        assertTrue(controller.loadProgram(program("P START 0\nFIRST LDA #7\nEND FIRST"), 0x5000));
        Thread.sleep(30);
        assertEquals(0x5000, vm.getRegisters().getPC());
        assertEquals(0, errors.get());
        controller.step();
        assertEquals(7, vm.getRegisters().get(Register.A).toIntUnsigned());
        controller.pause();
    }
}
