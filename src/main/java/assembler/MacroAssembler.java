package assembler;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;

/**
 * Módulo principal integrador do macro-montador.
 * Primeiro aciona o processador de macros sobre o fonte informado e grava o resultado em
 * {@value #EXPANDED_FILE_NAME}; depois entrega esse fonte expandido ao montador de duas passagens.
 * Os números de linha da montagem referem-se, portanto, ao arquivo expandido.
 */
public final class MacroAssembler {

    /** Nome do arquivo fonte gerado pelo processador de macros. */
    public static final String EXPANDED_FILE_NAME = "MASMAPRG.ASM";

    private final MacroProcessor processor = new MacroProcessor();
    private final TwoPassAssembler assembler = new TwoPassAssembler();

    /**
     * Expande as macros, grava o fonte expandido e monta o resultado.
     * Se a expansão tiver erros, o arquivo não é gravado (um MASMAPRG.ASM antigo permanece
     * como estava) e a montagem não é executada.
     *
     * @param source Programa fonte, com ou sem macros.
     * @param outputDirectory Pasta onde gravar MASMAPRG.ASM; nulo para não gravar.
     * @throws IOException Se o arquivo expandido não puder ser gravado.
     */
    public MacroAssemblyResult assemble(String source, Path outputDirectory) throws IOException {
        MacroResult expansion = processor.process(source);
        if (!expansion.isSuccess()) {
            return new MacroAssemblyResult(expansion, null, failedAssembly(source, expansion));
        }
        Path file = null;
        if (outputDirectory != null) {
            file = outputDirectory.resolve(EXPANDED_FILE_NAME);
            Files.writeString(file, expansion.getExpandedSource(), StandardCharsets.UTF_8);
        }
        return new MacroAssemblyResult(expansion, file, assembler.assemble(expansion.getExpandedSource()));
    }

    /** Monta um resultado sem objeto que mostra os erros de macro ao lado do fonte original. */
    private static AssemblyResult failedAssembly(String source, MacroResult expansion) {
        List<ListingLine> lines = new ArrayList<>();
        String[] original = source.split("\\R", -1);
        for (int i = 0; i < original.length; i++) {
            lines.add(new ListingLine(i + 1, -1, original[i], ""));
        }
        return new AssemblyResult(null, lines, new LinkedHashMap<>(), expansion.getDiagnostics());
    }
}
