package assembler;

/**
 * Representação de uma linha do programa assembly.
 * Preserva o texto original e os campos separados pelo analisador de entrada.
 */
public final class SourceLine {

    private final int lineNumber;
    private final String source;
    private final String label;
    private final String operation;
    private final String operand;

    /** Constrói os dados da linha sem alterar o fonte. */
    public SourceLine(int lineNumber, String source, String label, String operation, String operand) {
        this.lineNumber = lineNumber;
        this.source = source;
        this.label = label;
        this.operation = operation;
        this.operand = operand;
    }

    public int getLineNumber() { return lineNumber; }

    public String getSource() { return source; }

    public String getLabel() { return label; }

    public String getOperation() { return operation; }

    public String getOperand() { return operand; }

    public boolean isComment() { return operation.isEmpty(); }
}
