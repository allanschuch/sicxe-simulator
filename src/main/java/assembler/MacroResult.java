package assembler;

import java.util.List;

/**
 * Resultado do processamento de macros.
 * Reúne o fonte expandido (conteúdo de MASMAPRG.ASM), os erros encontrados e um
 * resumo das tabelas NAMTAB e DEFTAB para consulta e apresentação.
 */
public final class MacroResult {

    private final String expandedSource;
    private final List<Diagnostic> diagnostics;
    private final String tables;
    private final int macroCount;

    /**
     * Reúne os dados do processamento, copiando os erros para impedir alterações externas.
     */
    public MacroResult(String expandedSource, List<Diagnostic> diagnostics, String tables, int macroCount) {
        this.expandedSource = expandedSource;
        this.diagnostics = List.copyOf(diagnostics);
        this.tables = tables;
        this.macroCount = macroCount;
    }

    /** Fonte sem definições de macro e com as chamadas substituídas pelos seus corpos. */
    public String getExpandedSource() { return expandedSource; }

    /** Erros de macro; as linhas referem-se ao arquivo fonte original. */
    public List<Diagnostic> getDiagnostics() { return diagnostics; }

    /** Texto com o conteúdo final de NAMTAB e DEFTAB. */
    public String getTables() { return tables; }

    /** Quantidade de macros presentes em NAMTAB ao final do processamento. */
    public int getMacroCount() { return macroCount; }

    /** Indica que o fonte expandido pode ser entregue ao montador. */
    public boolean isSuccess() { return diagnostics.isEmpty(); }
}
