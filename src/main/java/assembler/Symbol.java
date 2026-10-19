package assembler;

/**
 * Símbolo definido no programa assembly.
 * Armazena o valor, a linha de definição e o tipo absoluto ou relativo.
 */
public final class Symbol {

    private final String name;
    private final long value;
    private final boolean relative;
    private final int lineNumber;

    /** Constrói os dados da definição sem alterar o fonte. */
    public Symbol(String name, long value, boolean relative, int lineNumber) {
        this.name = name;
        this.value = value;
        this.relative = relative;
        this.lineNumber = lineNumber;
    }

    public String getName() { return name; }

    public long getValue() { return value; }

    public boolean isRelative() { return relative; }

    public int getLineNumber() { return lineNumber; }
}
