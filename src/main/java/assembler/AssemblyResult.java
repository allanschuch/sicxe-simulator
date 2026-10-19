package assembler;

import java.util.List;
import java.util.Map;
import java.util.Collections;
import java.util.LinkedHashMap;

/**
 * Resultado das duas passagens do montador.
 * Reúne programa objeto, listagem, tabela de símbolos e erros encontrados.
 * As coleções são copiadas para preservar os dados da montagem concluída.
 */
public final class AssemblyResult {

    private final ObjectProgram program;
    private final List<ListingLine> lines;
    private final Map<String, Symbol> symbols;
    private final List<Diagnostic> diagnostics;

    /**
     * Reúne os dados da montagem, copiando as coleções para impedir alterações externas.
     */
    public AssemblyResult(ObjectProgram program, List<ListingLine> lines, Map<String, Symbol> symbols,
            List<Diagnostic> diagnostics) {
        this.program = program;
        this.lines = List.copyOf(lines);
        this.symbols = Collections.unmodifiableMap(new LinkedHashMap<>(symbols));
        this.diagnostics = List.copyOf(diagnostics);
    }

    public ObjectProgram getProgram() { return program; }

    public List<ListingLine> getLines() { return lines; }

    public Map<String, Symbol> getSymbols() { return symbols; }

    public List<Diagnostic> getDiagnostics() { return diagnostics; }

    /**
     * Indica se há um programa objeto válido e nenhum diagnóstico de erro.
     */
    public boolean isSuccess() { return program != null && diagnostics.isEmpty(); }

    /**
     * Retorna o objeto serializado; em caso de erro, retorna texto vazio.
     */
    public String getObjectCode() { return isSuccess() ? program.serialize() : ""; }

    /**
     * Produz a listagem de endereços e bytes, seguida da tabela de símbolos e dos erros.
     */
    public String getListing() {
        StringBuilder out = new StringBuilder(
                String.format("%5s %6s %-24s %s%n", "LINHA", "ENDEREÇO", "OBJETO", "FONTE"));
        for (ListingLine line : lines) {
            String address = line.getAddress() < 0 ? "      " : String.format("%06X", line.getAddress());
            out.append(String.format("%5d %s %-24s %s%n", line.getLineNumber(), address, line.getObjectCode(),
                    line.getSource()));
            diagnostics.stream().filter(d -> d.getLineNumber() == line.getLineNumber())
                    .forEach(d -> out.append("      ERRO: ").append(d.getMessage()).append('\n'));
        }
        out.append("\nTABELA DE SÍMBOLOS\nNOME                 VALOR    TIPO      LINHA\n");
        symbols.values().stream().sorted(java.util.Comparator.comparing(Symbol::getName))
                .forEach(s -> out.append(String.format("%-20s %08X %-9s %d%n", s.getName(), s.getValue(),
                        s.isRelative() ? "RELATIVO" : "ABSOLUTO", s.getLineNumber())));
        if (!diagnostics.isEmpty()) {
            out.append("\nERROS DE MONTAGEM\n");
            diagnostics.forEach(d -> out.append(d).append('\n'));
        }
        return out.toString();
    }
}
