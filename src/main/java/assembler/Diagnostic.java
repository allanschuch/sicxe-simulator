package assembler;

/**
 * Erro de montagem associado à linha original do arquivo.
 * Permite apresentar a mesma mensagem na listagem, na interface e no terminal.
 */
public final class Diagnostic {

    private final int lineNumber;
    private final String message;

    /** Constrói os dados da linha sem alterar o fonte. */
    public Diagnostic(int lineNumber, String message) {
        this.lineNumber = lineNumber;
        this.message = message;
    }

    public int getLineNumber() { return lineNumber; }

    public String getMessage() { return message; }

    /** Compara conteúdo e linha para evitar mensagens duplicadas nas duas passagens. */

    @Override
    public boolean equals(Object other) {
        if (!(other instanceof Diagnostic diagnostic)) {
            return false;
        }
        return lineNumber == diagnostic.lineNumber && java.util.Objects.equals(message, diagnostic.message);
    }

    @Override
    public int hashCode() {
        return java.util.Objects.hash(lineNumber, message);
    }

    @Override
    public String toString() {
        return "Linha " + lineNumber + ": " + message;
    }
}
