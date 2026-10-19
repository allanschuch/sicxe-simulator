import assembler.*;
import loader.ObjectLoader;
import model.machine.SICXEMachine;
import model.types.Opcode;
import model.types.Register;
import model.types.Word24;
import org.junit.jupiter.api.Test;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class AssemblerTest {
    /** Compara o trecho principal da figura 2.5 de Beck (p. 58) com os bytes do livro. */
    @Test
    void beckCopyMainRoutineMatchesPublishedBytes() {
        String source = String.join("\n", "COPY START 0", "FIRST STL RETADR", "LDB #LENGTH", "BASE LENGTH",
                "CLOOP +JSUB RDREC", "LDA LENGTH", "COMP #0", "JEQ ENDFIL", "+JSUB WRREC", "J CLOOP", "ENDFIL LDA EOF",
                "STA BUFFER", "LDA #3", "STA LENGTH", "+JSUB WRREC", "J @RETADR", "EOF BYTE C'EOF'", "RETADR RESW 1",
                "LENGTH RESW 1", "BUFFER RESB 4096", "RDREC CLEAR X", "ORG 0x105D", "WRREC RSUB", "END FIRST");
        AssemblyResult result = good(source);
        List<String> actual = result.getLines().stream().map(ListingLine::getObjectCode).filter(code -> !code.isEmpty())
                .limit(16).toList();
        assertEquals(List.of("17202D", "69202D", "4B101036", "032026", "290000", "332007", "4B10105D", "3F2FEC",
                "032010", "0F2016", "010003", "0F200D", "4B10105D", "3E2003", "454F46", "B410"), actual);
    }

    private final TwoPassAssembler assembler = new TwoPassAssembler();

    private AssemblyResult good(String source) {
        AssemblyResult result = assembler.assemble(source);
        assertTrue(result.isSuccess(), () -> result.getDiagnostics().toString());
        return result;
    }

    private void bad(String source, String message) {
        AssemblyResult result = assembler.assemble(source);
        assertFalse(result.isSuccess());
        assertNull(result.getProgram());
        assertEquals("", result.getObjectCode());
        assertTrue(result.getDiagnostics().stream().anyMatch(d -> d.getMessage().contains(message)),
                () -> result.getDiagnostics().toString());
    }

    private String code(String instruction) {
        return good("P START 0\nFIRST " + instruction + "\nEND FIRST").getLines().get(1).getObjectCode();
    }

    @Test
    void loopMatchesReferenceObjectAndExecutes() throws Exception {
        AssemblyResult result = good(Files.readString(Path.of("test-programs/loop.asm")));
        assertEquals("HDEMO  000000000019\nT00000016B410010000750005190002B8503B2FF80F20033F2FFD\nE000000\n",
                result.getObjectCode().replace("\r\n", "\n"));
        for (int origin : new int[] { 0, 0x4000 }) {
            SICXEMachine vm = new SICXEMachine();
            new ObjectLoader().load(ObjectProgram.parse(result.getObjectCode()), vm, origin);
            for (int i = 0; i < 19; i++) {
                vm.step();
            }
            assertEquals(10, vm.getMemory().readWord(origin + 0x16).toIntUnsigned());
            assertEquals(origin + 0x13, vm.getRegisters().getPC());
        }
    }

    @Test
    void formatTwoReferenceCodes() {
        assertEquals("B410", code("CLEAR X"));
        assertEquals("B850", code("TIXR T"));
        assertEquals("9004", code("ADDR A,S"));
        assertEquals("9404", code("SUBR A,S"));
        assertEquals("9804", code("MULR A,S"));
        assertEquals("9C04", code("DIVR A,S"));
        assertEquals("A004", code("COMPR A,S"));
        assertEquals("AC04", code("RMO A,S"));
        assertEquals("A407", code("SHIFTL A,8"));
        assertEquals("A80F", code("SHIFTR A,16"));
    }

    @Test
    void everyImplementedOpcodeCanBeAssembled() {
        for (Opcode op : Opcode.values()) {
            String operand = op.getFormat() == 2 ? switch (op) {
                case CLEAR, TIXR -> "A";
                case SHIFTL, SHIFTR -> "A,8";
                default -> "A,S";
            } : op == Opcode.RSUB ? "" : "0x100";
            AssemblyResult result = good("P START 0\nFIRST " + op + " " + operand + "\nEND FIRST");
            assertEquals(op.getFormat() * 2, result.getLines().get(1).getObjectCode().length());
            if (op.getFormat() == 3) {
                assertEquals(8, code("+" + op + " " + operand).length());
            }
        }
    }

    @Test
    void immediateAndExtendedConstantsDoNotRelocate() {
        assertEquals("010005", code("LDA #5"));
        assertEquals("010FFF", code("LDA #4095"));
        assertEquals("01101000", code("+LDA #4096"));
        assertEquals("011FFFFF", code("+LDA #1048575"));
        assertEquals("4F0000", code("RSUB"));
        assertEquals("4F100000", code("+RSUB"));
        assertTrue(good("P START 0\nFIRST +LDA #4096\nEND FIRST").getProgram().getModifications().isEmpty());
    }

    @Test
    void forwardBackwardIndirectAndIndexed() {
        AssemblyResult result = good(
                "P START 0\nFIRST LDA @PTR\nLDA DATA,X\nJ FIRST\nPTR WORD DATA\nDATA WORD 7\nEND FIRST");
        assertEquals("022006", result.getLines().get(1).getObjectCode());
        assertEquals("03A006", result.getLines().get(2).getObjectCode());
        assertEquals("3F2FF7", result.getLines().get(3).getObjectCode());
    }

    @Test
    void pcRelativePositiveBoundary() {
        AssemblyResult result = good("P START 0\nFIRST LDA DEST\nRESB 2047\nDEST WORD 1\nEND FIRST");
        assertEquals("0327FF", result.getLines().get(1).getObjectCode());
        bad("P START 0\nFIRST LDA DEST\nRESB 2048\nDEST WORD 1\nEND FIRST", "alcance");
    }

    @Test
    void pcRelativeNegativeBoundary() {
        AssemblyResult result = good("P START 0\nDEST WORD 1\nRESB 2042\nFIRST LDA DEST\nEND FIRST");
        assertEquals("032800", result.getLines().get(3).getObjectCode());
        bad("P START 0\nDEST WORD 1\nRESB 2043\nFIRST LDA DEST\nEND FIRST", "alcance");
    }

    @Test
    void baseRelativeBoundariesAndNoBase() {
        AssemblyResult result = good(
                "P START 0\nBASE DEST\nFIRST LDA DEST\nLDA DEST+4095\nRESB 5000\nDEST WORD 1\nEND FIRST");
        assertEquals("034000", result.getLines().get(2).getObjectCode());
        assertEquals("034FFF", result.getLines().get(3).getObjectCode());
        bad("P START 0\nBASE DEST\nFIRST LDA DEST+4096\nRESB 5000\nDEST WORD 1\nEND FIRST", "alcance");
        bad("P START 0\nBASE DEST\nNOBASE\nFIRST LDA DEST\nRESB 5000\nDEST WORD 1\nEND FIRST", "alcance");
    }

    @Test
    void baseDirectiveDoesNotGenerateBytesOrLoadRegister() {
        AssemblyResult result = good("P START 0\nBASE DEST\nFIRST LDA DEST\nRESB 5000\nDEST WORD 1\nEND FIRST");
        SICXEMachine vm = new SICXEMachine();
        new ObjectLoader().load(result.getProgram(), vm);
        assertEquals(0, vm.getRegisters().get(Register.B).toIntUnsigned());
        assertEquals("", result.getLines().get(1).getObjectCode());
    }

    @Test
    void extendedAddressesAndWordsRelocateFromNonzeroOrigin() {
        AssemblyResult result = good(
                "P START 1000\nFIRST +LDA VALUE\n+LDB #VALUE\nPTR WORD VALUE\nVALUE WORD 42\nEND FIRST");
        assertEquals(List.of(new ObjectProgram.ModificationRecord(1, 5), new ObjectProgram.ModificationRecord(5, 5),
                new ObjectProgram.ModificationRecord(8, 6)), result.getProgram().getModifications());
        for (int origin : new int[] { 0, 0x1000, 0x5000 }) {
            SICXEMachine vm = new SICXEMachine();
            new ObjectLoader().load(ObjectProgram.parse(result.getObjectCode()), vm, origin);
            vm.step();
            assertEquals(42, vm.getRegisters().get(Register.A).toIntUnsigned());
            vm.step();
            assertEquals(origin + 11, vm.getRegisters().get(Register.B).toIntUnsigned());
            assertEquals(origin + 11, vm.getMemory().readWord(origin + 8).toIntUnsigned());
        }
    }

    @Test
    void constantsCommentsAndCase() {
        AssemblyResult result = good(
                "p start 0\nfirst lda #1 ; teste\ntext byte C'Ab;C, x'\nhex byte X'00ff'\nneg word -1\nend first");
        assertEquals("41623B432C2078", result.getLines().get(2).getObjectCode());
        assertEquals("00FF", result.getLines().get(3).getObjectCode());
        assertEquals("FFFFFF", result.getLines().get(4).getObjectCode());
        assertEquals(3, result.getSymbols().get("TEXT").getValue());
    }

    @Test
    void reservationHolesAndTextRecordLimit() {
        AssemblyResult result = good("P START 0\nFIRST LDA #0\nRESW 2\nBYTE C'" + "A".repeat(70) + "'\nEND FIRST");
        var texts = result.getProgram().getTextRecords();
        assertEquals(4, texts.size());
        assertEquals(0, texts.get(0).getAddress());
        assertEquals(9, texts.get(1).getAddress());
        assertEquals(30, texts.get(1).getBytes().length);
        assertEquals(10, texts.get(3).getBytes().length);
        assertEquals(79, result.getProgram().getLength());
    }

    @Test
    void equForwardExpressionsAndCurrentLocation() {
        AssemblyResult result = good(
                "P START 0\nSIZE EQU LAST-FIRST\nN EQU (2+3)*4/2\nFIRST LDA #N\nLAST EQU *\nEND FIRST");
        assertEquals(3, result.getSymbols().get("SIZE").getValue());
        assertFalse(result.getSymbols().get("SIZE").isRelative());
        assertEquals(10, result.getSymbols().get("N").getValue());
        assertEquals("01000A", result.getLines().get(3).getObjectCode());
    }

    @Test
    void cyclicEquAndInvalidRelocationExpression() {
        bad("P START 0\nA EQU B\nB EQU A\nFIRST RSUB\nEND FIRST", "EQU não resolvido");
        bad("P START 0\nFIRST RSUB\nA EQU FIRST+FIRST\nEND FIRST", "combinação");
        bad("P START 0\nFIRST RSUB\nA EQU FIRST*2\nEND FIRST", "valores absolutos");
        bad("P START 0\nFIRST RSUB\nA EQU 1/0\nEND FIRST", "divisão por zero");
    }

    @Test
    void originRestoresAndRejectsOverlaps() {
        AssemblyResult result = good("P START 0\nFIRST RSUB\nORG 0x100\nTABLE WORD 5\nORG\nNEXT WORD 6\nEND FIRST");
        assertEquals(0x100, result.getSymbols().get("TABLE").getValue());
        assertEquals(3, result.getSymbols().get("NEXT").getValue());
        assertEquals(0x103, result.getProgram().getLength());
        bad("P START 0\nFIRST RSUB\nORG 1\nWORD 0\nEND FIRST", "sobreposta");
        bad("P START 0\nFIRST RSUB\nORG\nEND FIRST", "origem anterior");
    }

    @Test
    void literalsDeduplicateAndAllocateAtPoolsAndEnd() {
        AssemblyResult result = good(
                "P START 0\nFIRST LDCH =C'A'\nLDCH =X'41'\nJ NEXT\nLTORG\nNEXT LDCH =C'B'\nRSUB\nEND FIRST");
        assertEquals("532006", result.getLines().get(1).getObjectCode());
        assertEquals("532003", result.getLines().get(2).getObjectCode());
        long literals = result.getLines().stream().filter(l -> l.getSource().startsWith("*       =")).count();
        assertEquals(2, literals);
        assertEquals(17, result.getProgram().getLength());
    }

    @Test
    void undefinedDuplicateAndUnknownOpcodesHaveLineNumbers() {
        AssemblyResult undefined = assembler.assemble("P START 0\nFIRST LDA MISSING\nEND FIRST");
        assertFalse(undefined.isSuccess());
        assertEquals(2, undefined.getDiagnostics().get(0).getLineNumber());
        bad("P START 0\nFIRST RSUB\nFIRST WORD 0\nEND FIRST", "mais de uma vez");
        bad("P START 0\nFIRST FLOAT\nEND FIRST", "desconhecida");
        bad("P START 0\nFIRST HALT\nEND FIRST", "desconhecida");
    }

    @Test
    void rejectsBadOperandsAndModes() {
        for (String instruction : new String[] { "CLEAR", "CLEAR A,X", "ADDR A", "ADDR A,F", "TIXR Q", "SHIFTL A,0",
                "SHIFTR A,17", "LDA", "LDA #1,X", "LDA @A,X", "LDA #=C'A'", "STA #5", "RSUB A", "+CLEAR A" }) {
            assertFalse(assembler.assemble("P START 0\nFIRST " + instruction + "\nEND FIRST").isSuccess(), instruction);
        }
    }

    @Test
    void rejectsConstantOverflowAndMalformedData() {
        for (String operation : new String[] { "WORD -8388609", "WORD 16777216", "BYTE X'F'", "BYTE X'GG'", "BYTE C'á'",
                "BYTE C'unclosed", "RESB -1", "RESW 400000", "LDA #4096", "+LDA #1048576", "LDA #-1" }) {
            assertFalse(assembler.assemble("P START 0\nFIRST RSUB\n" + operation + "\nEND FIRST").isSuccess(),
                    operation);
        }
    }

    @Test
    void programBoundsAndEntryValidation() {
        good("P START FFFFD\nFIRST RSUB\nEND FIRST");
        bad("P START FFFFE\nFIRST RSUB\nEND FIRST", "memória");
        bad("P START 0\nFIRST RSUB\nDATA WORD 1\nEND DATA", "início de uma instrução");
        bad("P START 0\nFIRST RSUB", "END ausente");
        bad("P START 0\nFIRST RSUB\nEND FIRST\nWORD 1", "após END");
        bad("P START 0\nBYTE C'A'\nEND", "executável");
    }

    @Test
    void sameAssemblerCanBeReusedWithoutLeakingSymbols() {
        good("P START 0\nFIRST RSUB\nEND FIRST");
        bad("P START 0\nSECOND LDA FIRST\nEND SECOND", "indefinido");
    }

    @Test
    void allShiftCountsDecodeAndExecute() {
        for (int count = 1; count <= 16; count++) {
            SICXEMachine vm = new SICXEMachine();
            new ObjectLoader().load(good("P START 0\nFIRST LDA #1\nSHIFTL A," + count + "\nEND FIRST").getProgram(),
                    vm);
            vm.step();
            vm.step();
            assertEquals(1 << count, vm.getRegisters().get(Register.A).toIntUnsigned());
            assertEquals(count, vm.getLastExecutedInstruction().getShiftCount());
            assertNull(vm.getLastExecutedInstruction().getR2());
        }
        SICXEMachine vm = new SICXEMachine();
        new ObjectLoader().load(good("P START 0\nFIRST SHIFTR A,16\nEND FIRST").getProgram(), vm);
        vm.getRegisters().set(Register.A, new Word24(-1));
        vm.step();
        assertEquals(-1, vm.getRegisters().get(Register.A).toIntSigned());
    }

    @Test
    void executeAddressingExampleAfterRelocation() throws Exception {
        AssemblyResult result = good(Files.readString(Path.of("test-programs/addressing.asm")));
        for (int origin : new int[] { 0x1000, 0x6000 }) {
            SICXEMachine vm = new SICXEMachine();
            new ObjectLoader().load(ObjectProgram.parse(result.getObjectCode()), vm, origin);
            for (int i = 0; i < 8; i++) {
                vm.step();
            }
            int resultAddress = origin + (int) result.getSymbols().get("RESULT").getValue()
                    - result.getProgram().getStartAddress();
            assertEquals(256, vm.getMemory().readWord(resultAddress).toIntUnsigned());
            assertEquals(1, vm.getRegisters().get(Register.A).toIntUnsigned());
        }
    }

    @Test
    void literalExampleAndIndexedLiteralWithCommaParse() throws Exception {
        good(Files.readString(Path.of("test-programs/literals.asm")));
        good("P START 0\nFIRST LDCH =C'A,B',X\nEND FIRST");
    }

    @Test
    void immediateLdchPreservesUpperBytes() {
        SICXEMachine vm = new SICXEMachine();
        new ObjectLoader().load(good("P START 0\nFIRST LDCH #65\nEND FIRST").getProgram(), vm);
        vm.getRegisters().set(Register.A, new Word24(0x123456));
        vm.step();
        assertEquals(0x123441, vm.getRegisters().get(Register.A).toIntUnsigned());
    }

    @Test
    void absoluteEquAndFixedAddressesStayAbsoluteAfterRelocation() {
        AssemblyResult result = good("P START 0\nFIXED EQU 0x100\nFIRST +LDA FIXED\n+LDA #4096\nEND FIRST");
        assertTrue(result.getProgram().getModifications().isEmpty());
        SICXEMachine vm = new SICXEMachine();
        new ObjectLoader().load(result.getProgram(), vm, 0x5000);
        vm.getMemory().writeWord(0x100, new Word24(99));
        vm.step();
        assertEquals(99, vm.getRegisters().get(Register.A).toIntUnsigned());
        vm.step();
        assertEquals(4096, vm.getRegisters().get(Register.A).toIntUnsigned());
    }

    @Test
    void referenceFlagCombinationsHaveExactCodes() {
        assertEquals("030100", code("LDA 0x100"));
        assertEquals("020100", code("LDA @0x100"));
        assertEquals("038100", code("LDA 0x100,X"));
        assertEquals("03100100", code("+LDA 0x100"));
        assertEquals("03900100", code("+LDA 0x100,X"));
    }
}
