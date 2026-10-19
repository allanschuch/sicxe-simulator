package assembler;

import model.types.Opcode;
import java.util.Locale;
import java.util.Set;

/**
 * Analisador das linhas do programa fonte SIC/XE.
 * Separa rótulo, operação e operando, preservando o conteúdo de constantes
 * e o texto original para a listagem e as mensagens de erro.
 */
public final class SourceParser {
    public static final Set<String> DIRECTIVES = Set.of("START", "END", "BYTE", "WORD", "RESB", "RESW", "BASE",
            "NOBASE", "EQU", "ORG", "LTORG");

    /**
     * Analisa uma linha em formato livre: [rótulo[:]] operação [operando] [; comentário].
     *
     * @param original Texto da linha como aparece no arquivo fonte.
     * @param number Número da linha, começando em 1.
     * @return Campos normalizados, mantendo a caixa dos caracteres nas constantes.
     * @throws IllegalArgumentException Se houver aspas, rótulo ou operação inválida.
     */
    public SourceLine parse(String original, int number) {
        String text = original.strip();
        if (text.startsWith("\uFEFF")) {
            text = text.substring(1).strip();
        }
        if (text.isEmpty() || text.startsWith(".")) {
            return new SourceLine(number, original, "", "", "");
        }
        boolean quoted = false;
        for (int i = 0; i < text.length(); i++) {
            if (text.charAt(i) == '\'') {
                quoted = !quoted;
            }
            if (text.charAt(i) == ';' && !quoted) {
                text = text.substring(0, i).strip();
                break;
            }
        }
        if (quoted) {
            throw new IllegalArgumentException("Constante com aspas não fechadas.");
        }
        if (text.isEmpty()) {
            return new SourceLine(number, original, "", "", "");
        }
        String[] first = text.split("\\s+", 2);
        String label = "";
        String op = first[0].toUpperCase(Locale.ROOT);
        String rest = first.length == 2 ? first[1].strip() : "";
        if (op.endsWith(":") || !isOperation(op)) {
            label = op.endsWith(":") ? op.substring(0, op.length() - 1) : op;
            if (!label.matches("[A-Z_][A-Z0-9_]*")) {
                throw new IllegalArgumentException("Rótulo inválido: " + label);
            }
            if (rest.isEmpty()) {
                throw new IllegalArgumentException("Operação ausente após " + label + ".");
            }
            String[] fields = rest.split("\\s+", 2);
            op = fields[0].toUpperCase(Locale.ROOT);
            rest = fields.length == 2 ? fields[1].strip() : "";
        }
        if (!isOperation(op)) {
            throw new IllegalArgumentException("Operação desconhecida ou não suportada: " + op);
        }
        return new SourceLine(number, original, label, op, rest);
    }

    /**
     * Consulta a tabela de diretivas e os opcodes já definidos na máquina virtual.
     */
    public static boolean isOperation(String operation) {
        if (DIRECTIVES.contains(operation)) {
            return true;
        }
        try {
            Opcode.valueOf(operation.startsWith("+") ? operation.substring(1) : operation);
            return true;
        } catch (IllegalArgumentException e) {
            return false;
        }
    }
}
