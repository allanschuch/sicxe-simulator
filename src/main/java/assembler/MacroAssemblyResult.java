package assembler;

import java.nio.file.Path;

/**
 * Resultado do macro-montador: a expansão de macros e a montagem do fonte expandido.
 * Quando a expansão falha, não há montagem; o resultado de montagem então apenas
 * apresenta os erros de macro sobre o fonte original, para que a interface os exiba como os demais.
 */
public final class MacroAssemblyResult {

    private final MacroResult expansion;
    private final Path expandedFile;
    private final AssemblyResult assembly;

    /** Reúne os resultados; {@code expandedFile} é nulo quando nenhum arquivo foi gravado. */
    public MacroAssemblyResult(MacroResult expansion, Path expandedFile, AssemblyResult assembly) {
        this.expansion = expansion;
        this.expandedFile = expandedFile;
        this.assembly = assembly;
    }

    public MacroResult getExpansion() { return expansion; }

    /** Arquivo MASMAPRG.ASM gravado, ou nulo se a expansão falhou ou não houve gravação. */
    public Path getExpandedFile() { return expandedFile; }

    /**
     * Resultado da montagem. Se a expansão falhou, não há programa objeto e as linhas e os
     * erros referem-se ao fonte original; caso contrário referem-se ao fonte expandido.
     */
    public AssemblyResult getAssembly() { return assembly; }

    public String getExpandedSource() { return expansion.getExpandedSource(); }

    /** Verdadeiro quando a expansão e a montagem terminaram sem erros. */
    public boolean isSuccess() { return expansion.isSuccess() && assembly.isSuccess(); }
}
