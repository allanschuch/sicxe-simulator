package assembler;

import java.util.HexFormat;

/**
 * Linha da listagem produzida pelo montador.
 * Relaciona o texto original ao endereço e aos bytes gerados na segunda passagem.
 */
public final class ListingLine {

    private final int lineNumber;
    private final int address;
    private final String source;
    private final String objectCode;

    /** Constrói os dados da linha sem alterar o fonte. */
    public ListingLine(int lineNumber, int address, String source, String objectCode) {
        this.lineNumber = lineNumber;
        this.address = address;
        this.source = source;
        this.objectCode = objectCode;
    }

    public int getLineNumber() { return lineNumber; }

    public int getAddress() { return address; }

    public String getSource() { return source; }

    public String getObjectCode() { return objectCode; }

    /** Converte bytes para a representação hexadecimal usada na listagem e no objeto. */
    public static String hex(byte[] bytes) {
        return HexFormat.of().withUpperCase().formatHex(bytes);
    }
}
