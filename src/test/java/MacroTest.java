import assembler.AssemblyResult;
import assembler.Diagnostic;
import assembler.MacroAssembler;
import assembler.MacroAssemblyResult;
import assembler.MacroProcessor;
import assembler.MacroResult;
import assembler.ObjectProgram;
import assembler.TwoPassAssembler;
import loader.ObjectLoader;
import model.machine.SICXEMachine;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class MacroTest {
    @TempDir
    Path directory;

    private final MacroProcessor processor = new MacroProcessor();

    private static String text(String... lines) {
        return String.join("\n", lines);
    }

    private String expand(String... lines) {
        MacroResult result = processor.process(text(lines));
        assertTrue(result.isSuccess(), () -> result.getDiagnostics().toString());
        return result.getExpandedSource();
    }

    private void fails(String source, int line, String message) {
        MacroResult result = processor.process(source);
        assertFalse(result.isSuccess());
        assertTrue(result.getDiagnostics().stream().anyMatch(d -> d.getLineNumber() == line
                && d.getMessage().contains(message)), () -> result.getDiagnostics().toString());
    }

    @Test
    void sourceWithoutMacrosIsCopiedUnchanged() throws Exception {
        for (String name : new String[] { "loop.asm", "addressing.asm", "literals.asm" }) {
            String source = Files.readString(Path.of("test-programs/" + name)).replace("\r\n", "\n");
            assertEquals(source, processor.process(source).getExpandedSource(), name);
        }
    }

    @Test
    void simpleMacroIsExpandedAndItsDefinitionRemoved() {
        assertEquals(text("P START 0", "FIRST LDA #1", ". SAVE A,RESULT", "STA RESULT", "RESULT RESW 1", "END FIRST"),
                expand("P START 0", "SAVE MACRO &REG,&MEM", "ST&REG &MEM", "MEND", "FIRST LDA #1", "SAVE A,RESULT",
                        "RESULT RESW 1", "END FIRST"));
    }

    @Test
    void commentsAndInlineCommentsOfTheBodyAreNotCopied() {
        assertEquals(text("P START 0", ". M", "LDA #1", "END"),
                expand("P START 0", "M MACRO", ". comentário", "", "LDA #1 ; outro", "MEND", "M ; chamada", "END"));
    }

    @Test
    void nestedCallPassesArgumentsDownTheChain() {
        assertEquals(text("P START 0", ". B1 1,2", ".   A1 1", "LDA #1", ".   A1 2", "LDA #2", "END"),
                expand("P START 0", "A1 MACRO &X", "LDA #&X", "MEND", "B1 MACRO &Y,&Z", "A1 &Y", "A1 &Z", "MEND",
                        "B1 1,2", "END"));
    }

    @Test
    void callsThreeLevelsDeep() {
        assertEquals(text("P START 0", ". C", ".   B", ".     A", "RSUB", "END"),
                expand("P START 0", "A MACRO", "RSUB", "MEND", "B MACRO", "A", "MEND", "C MACRO", "B", "MEND", "C",
                        "END"));
    }

    @Test
    void nestedDefinitionTakesEffectOnlyWhenOuterMacroIsExpanded() {
        assertEquals(text("P START 0", "FIRST RSUB", "INNER", ". OUTER", ". INNER", "RSUB", "END FIRST"),
                expand("P START 0", "OUTER MACRO", "INNER MACRO", "RSUB", "MEND", "MEND", "FIRST RSUB", "INNER",
                        "OUTER", "INNER", "END FIRST"));
    }

    @Test
    void innerMacroCapturesParametersOfTheOuterOne() {
        assertEquals(text("P START 0", ". MKADD 10", ". ADDK 4", "ADD #10", "ADD #4", "END"),
                expand("P START 0", "MKADD MACRO &K", "ADDK MACRO &N", "ADD #&K", "ADD #&N", "MEND", "MEND",
                        "MKADD 10", "ADDK 4", "END"));
    }

    @Test
    void innerMacroIsRedefinedEachTimeTheOuterOneIsExpanded() {
        assertEquals(
                text("P START 0", ". OUT 5", ".   IN", "ADD #5", ". OUT 1", ".   IN", "ADD #1", ". IN", "ADD #1", "END"),
                expand("P START 0", "OUT MACRO &A", "IN MACRO", "ADD #&A", "MEND", "IN", "MEND", "OUT 5", "OUT 1",
                        "IN", "END"));
    }

    @Test
    void innerParameterWithTheSameNameShadowsTheOuterOne() {
        assertEquals(text("P START 0", ". OUT 7", ".   IN 7", "LDA #7", "END"),
                expand("P START 0", "OUT MACRO &A", "IN MACRO &A", "LDA #&A", "MEND", "IN &A", "MEND", "OUT 7",
                        "END"));
    }

    @Test
    void definitionsNestedThreeLevelsDeep() {
        assertEquals(text("P START 0", ". L1 1", ". L2 2", ". L3 3", "LDA #1", "LDA #2", "LDA #3", "END"),
                expand("P START 0", "L1 MACRO &A", "L2 MACRO &B", "L3 MACRO &C", "LDA #&A", "LDA #&B", "LDA #&C",
                        "MEND", "MEND", "MEND", "L1 1", "L2 2", "L3 3", "END"));
    }

    @Test
    void argumentsMayContainCommasInsideQuotesAndParentheses() {
        assertEquals(text("P START 0", ". M C'A,B',(1+2)", "BYTE C'A,B'", "WORD (1+2)", "END"),
                expand("P START 0", "M MACRO &S,&E", "BYTE &S", "WORD &E", "MEND", "M C'A,B',(1+2)", "END"));
    }

    @Test
    void missingArgumentsAreEmptyAndExtraOnesAreRejected() {
        assertEquals(text("P START 0", ". M 1", "WORD 1", "BYTE ", "END"),
                expand("P START 0", "M MACRO &A,&B", "WORD &A", "BYTE &B", "MEND", "M 1", "END"));
        fails(text("P START 0", "M MACRO &A", "MEND", "M 1,2"), 4, "no máximo 1");
    }

    @Test
    void macroNamesAndParametersAreCaseInsensitive() {
        assertEquals(text("P START 0", ". m 4", "lda #4", "END"),
                expand("P START 0", "M macro &Val", "lda #&VAL", "mend", "m 4", "END"));
    }

    @Test
    void labelOnACallMarksTheStartOfTheExpansion() {
        AssemblyResult result = new TwoPassAssembler().assemble(expand("P START 0", "GO MACRO", "LDA #1", "MEND",
                "FIRST CLEAR X", "L2 GO", "STA R", "R RESW 1", "END FIRST"));
        assertTrue(result.isSuccess(), () -> result.getDiagnostics().toString());
        assertEquals(2, result.getSymbols().get("L2").getValue());
        assertTrue(result.getSymbols().get("L2").isRelative());
    }

    @Test
    void tablesShowNamtabAndDeftabInPositionalNotation() {
        String tables = processor.process(text("P START 0", "SAVE MACRO &REG,&MEM", "ST&REG &MEM", "MEND", "END"))
                .getTables();
        assertTrue(tables.contains("SAVE"), tables);
        assertTrue(tables.contains("ST?1 ?2"), tables);
    }

    @Test
    void definitionErrorsAreReportedWithTheirSourceLine() {
        fails(text("P START 0", "MEND", "END"), 2, "MEND sem MACRO");
        fails(text("P START 0", "A MACRO", "RSUB", "END"), 2, "sem MEND");
        fails(text("P START 0", "A MACRO", "MEND", "A MACRO", "MEND"), 4, "mais de uma vez");
        fails(text("ADD MACRO", "MEND"), 1, "reservado");
        fails(text("MACRO &A", "MEND"), 1, "exige o nome");
        fails(text("M MACRO X", "MEND"), 1, "Parâmetro inválido");
        fails(text("M MACRO &A,&a", "MEND"), 1, "repetido");
    }

    @Test
    void recursiveCallsAreRejectedWithTheCallSiteLine() {
        fails(text("R MACRO", "R", "MEND", "R"), 4, "recursiva");
        fails(text("A MACRO", "B", "MEND", "B MACRO", "A", "MEND", "A"), 7, "recursiva");
    }

    @Test
    void errorInsideAnExpansionNamesTheChainOfMacros() {
        fails(text("A MACRO", "MEND", "B MACRO", "A 1", "MEND", "B"), 6, "[expansão de B]");
    }

    @Test
    void runawayExpansionStopsAtTheLineLimit() {
        StringBuilder source = new StringBuilder("M0 MACRO\nRSUB\nMEND\n");
        for (int i = 1; i <= 20; i++) {
            source.append("M").append(i).append(" MACRO\nM").append(i - 1).append("\nM").append(i - 1)
                    .append("\nMEND\n");
        }
        source.append("M20\n");
        fails(source.toString(), 84, "excede");
    }

    @Test
    void linesAfterEndAreCopiedForTheAssemblerToReport() {
        assertEquals(text("P START 0", "END", "M", "RSUB"), expand("P START 0", "END", "M", "RSUB"));
    }

    @Test
    void macroProgramAssemblesRunsAndWritesTheExpandedFile() throws Exception {
        String source = Files.readString(Path.of("test-programs/macros.asm"));
        MacroAssemblyResult result = new MacroAssembler().assemble(source, directory);
        assertTrue(result.isSuccess(), () -> result.getAssembly().getDiagnostics().toString());
        Path expanded = directory.resolve("MASMAPRG.ASM");
        assertEquals(expanded, result.getExpandedFile());
        assertEquals(result.getExpandedSource(), Files.readString(expanded));
        assertFalse(Files.readString(expanded).contains("MEND"));
        assertFalse(Files.readString(expanded).contains(" MACRO "));
        SICXEMachine vm = new SICXEMachine();
        new ObjectLoader().load(ObjectProgram.parse(result.getAssembly().getObjectCode()), vm, 0);
        for (int i = 0; i < 5; i++) {
            vm.step();
        }
        int resultAddress = (int) result.getAssembly().getSymbols().get("RESULT").getValue();
        assertEquals(17, vm.getMemory().readWord(resultAddress).toIntUnsigned());
    }

    @Test
    void expandedAndDirectAssemblyProduceTheSameObject() throws Exception {
        String source = Files.readString(Path.of("test-programs/loop.asm"));
        assertEquals(new TwoPassAssembler().assemble(source).getObjectCode(),
                new MacroAssembler().assemble(source, directory).getAssembly().getObjectCode());
    }

    @Test
    void macroErrorsSkipAssemblyAndDoNotReplaceTheExpandedFile() throws Exception {
        Path expanded = directory.resolve("MASMAPRG.ASM");
        Files.writeString(expanded, "anterior");
        MacroAssemblyResult result = new MacroAssembler().assemble("P START 0\nA MACRO\nFIRST RSUB\nEND FIRST",
                directory);
        assertFalse(result.isSuccess());
        assertNull(result.getExpandedFile());
        assertNull(result.getAssembly().getProgram());
        assertEquals("anterior", Files.readString(expanded));
        List<Diagnostic> errors = result.getAssembly().getDiagnostics();
        assertEquals(2, errors.get(0).getLineNumber());
        assertTrue(result.getAssembly().getListing().contains("FIRST RSUB"));
    }

    @Test
    void assemblyErrorsAfterExpansionStillWriteTheExpandedFile() throws Exception {
        MacroAssemblyResult result = new MacroAssembler().assemble(
                "P START 0\nM MACRO &A\nFIRST LDA &A\nMEND\nM MISSING\nEND FIRST", directory);
        assertFalse(result.isSuccess());
        assertTrue(result.getExpansion().isSuccess());
        assertTrue(Files.readString(directory.resolve("MASMAPRG.ASM")).contains("LDA MISSING"));
        assertTrue(result.getAssembly().getDiagnostics().stream()
                .anyMatch(d -> d.getMessage().contains("Símbolo indefinido")));
    }
}
