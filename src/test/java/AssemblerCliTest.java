import assembler.AssemblerCli;
import assembler.ObjectProgram;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Files;
import java.nio.file.Path;
import static org.junit.jupiter.api.Assertions.*;

class AssemblerCliTest {
    @TempDir
    Path directory;

    @Test
    void generatesObjectAndListing() throws Exception {
        Path input = directory.resolve("program.asm");
        Files.writeString(input, "P START 0\nFIRST LDA #5\nEND FIRST");
        assertEquals(0, AssemblerCli.run(new String[] { input.toString() }));
        assertEquals(3, ObjectProgram.parse(Files.readString(directory.resolve("program.obj"))).getLength());
        assertTrue(Files.readString(directory.resolve("program.lst")).contains("010005"));
    }

    @Test
    void invalidSourceOnlyGeneratesErrorListing() throws Exception {
        Path input = directory.resolve("bad.asm");
        Files.writeString(input, "P START 0\nFIRST LDA MISSING\nEND FIRST");
        assertEquals(1, AssemblerCli.run(new String[] { input.toString() }));
        assertFalse(Files.exists(directory.resolve("bad.obj")));
        assertTrue(Files.readString(directory.resolve("bad.lst")).contains("Símbolo indefinido"));
    }

    @Test
    void supportsOutputPrefixAndUsageErrors() throws Exception {
        Path input = directory.resolve("program.asm");
        Files.writeString(input, "P START 0\nFIRST RSUB\nEND FIRST");
        String output = directory.resolve("custom").toString();
        assertEquals(0, AssemblerCli.run(new String[] { input.toString(), output }));
        assertTrue(Files.exists(Path.of(output + ".obj")));
        assertEquals(2, AssemblerCli.run(new String[] {}));
        assertEquals(1, AssemblerCli.run(new String[] { directory.resolve("missing.asm").toString() }));
    }
}
