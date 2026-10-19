package assembler;

import model.hardware.Memory;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.HexFormat;
import java.util.List;

/**
 * Programa objeto SIC/XE de uma única seção de controle.
 * Os registros H, T e E usam endereços de montagem. A posição de cada
 * registro M é um deslocamento em bytes a partir do início do programa.
 */
public final class ObjectProgram {
    /** Limite de bytes do campo de dados de um registro T. */
    public static final int MAX_TEXT_RECORD_BYTES = 30;

    private final String name;
    private final int startAddress;
    private final int length;
    private final int entryPoint;
    private final List<TextRecord> textRecords;
    private final List<ModificationRecord> modifications;

    /** Registro T: endereço de montagem e até 30 bytes de código ou dados. */
    public static final class TextRecord {
        private final int address;
        private final byte[] bytes;

        public TextRecord(int address, byte[] bytes) {
            this.address = address;
            this.bytes = bytes.clone();
        }

        public int getAddress() { return address; }

        public byte[] getBytes() { return bytes.clone(); }
    }

    /** Registro M: posição relativa do campo e tamanho em meios-bytes. */
    public static final class ModificationRecord {
        private final int offset;
        private final int halfBytes;

        public ModificationRecord(int offset, int halfBytes) {
            this.offset = offset;
            this.halfBytes = halfBytes;
        }

        public int getOffset() { return offset; }

        public int getHalfBytes() { return halfBytes; }

        @Override
        public boolean equals(Object other) {
            if (!(other instanceof ModificationRecord record)) {
                return false;
            }
            return offset == record.offset && halfBytes == record.halfBytes;
        }

        @Override
        public int hashCode() {
            return java.util.Objects.hash(offset, halfBytes);
        }
    }

    /** Valida os limites, as áreas inicializadas e os campos que serão relocados. */
    public ObjectProgram(String name, int startAddress, int length, int entryPoint, List<TextRecord> textRecords,
            List<ModificationRecord> modifications) {
        textRecords = List.copyOf(textRecords);
        modifications = List.copyOf(modifications);
        if (name == null || !name.matches("[A-Z_][A-Z0-9_]{0,5}")) {
            throw new IllegalArgumentException("Nome de programa deve ter de 1 a 6 caracteres (letras, números ou _).");
        }
        if (startAddress < 0 || length <= 0 || (long) startAddress + length > Memory.MAX_MEMORY_SIZE) {
            throw new IllegalArgumentException("Área do programa fora da memória ou vazia.");
        }
        if (entryPoint < startAddress || entryPoint >= startAddress + length) {
            throw new IllegalArgumentException("Ponto de entrada fora do programa.");
        }
        BitSet initialized = new BitSet(length);
        for (TextRecord record : textRecords) {
            int offset = record.address - startAddress;
            if (record.bytes.length < 1 || record.bytes.length > MAX_TEXT_RECORD_BYTES || offset < 0
                    || (long) offset + record.bytes.length > length) {
                throw new IllegalArgumentException("Registro T inválido ou fora da área do programa.");
            }
            int collision = initialized.nextSetBit(offset);
            if (collision >= 0 && collision < offset + record.bytes.length) {
                throw new IllegalArgumentException("Registros T sobrepostos.");
            }
            initialized.set(offset, offset + record.bytes.length);
        }
        if (!initialized.get(entryPoint - startAddress)) {
            throw new IllegalArgumentException("Entrada não aponta para bytes inicializados.");
        }
        BitSet modified = new BitSet(length * 2);
        for (ModificationRecord record : modifications) {
            if (record.halfBytes != 5 && record.halfBytes != 6) {
                throw new IllegalArgumentException("Registro M deve modificar 5 ou 6 meios-bytes.");
            }
            int offset = record.offset;
            if (offset < 0 || (long) offset + 3 > length || initialized.nextClearBit(offset) < offset + 3) {
                throw new IllegalArgumentException("Registro M aponta para bytes inexistentes.");
            }
            int nibble = offset * 2 + (record.halfBytes == 5 ? 1 : 0);
            int collision = modified.nextSetBit(nibble);
            if (collision >= 0 && collision < nibble + record.halfBytes) {
                throw new IllegalArgumentException("Registros M sobrepostos.");
            }
            modified.set(nibble, nibble + record.halfBytes);
        }
        this.name = name;
        this.startAddress = startAddress;
        this.length = length;
        this.entryPoint = entryPoint;
        this.textRecords = textRecords;
        this.modifications = modifications;
    }

    public String getName() { return name; }

    public int getStartAddress() { return startAddress; }

    public int getLength() { return length; }

    public int getEntryPoint() { return entryPoint; }

    public List<TextRecord> getTextRecords() { return textRecords; }

    public List<ModificationRecord> getModifications() { return modifications; }

    /**
     * Escreve os registros H/T/M/E nos campos hexadecimais do formato objeto.
     */
    public String serialize() {
        StringBuilder out = new StringBuilder(String.format("H%-6s%06X%06X%n", name, startAddress, length));
        for (TextRecord text : textRecords) {
            out.append(String.format("T%06X%02X%s%n", text.address, text.bytes.length, ListingLine.hex(text.bytes)));
        }
        for (ModificationRecord modification : modifications) {
            out.append(String.format("M%06X%02X%n", modification.offset, modification.halfBytes));
        }
        return out.append(String.format("E%06X%n", entryPoint)).toString();
    }

    /**
     * Lê registros H/T/M/E, aceitando também campos separados por ^.
     * Valida a ordem dos registros antes de construir o programa objeto.
     *
     * @param source Conteúdo textual do arquivo .obj.
     * @return Programa validado para o carregador.
     * @throws IllegalArgumentException Se os registros ou seus endereços forem inválidos.
     */
    public static ObjectProgram parse(String source) {
        String name = null;
        int start = 0, length = 0, entry = -1;
        boolean ended = false, sawModification = false;
        List<TextRecord> texts = new ArrayList<>();
        List<ModificationRecord> modifications = new ArrayList<>();
        int number = 0;
        for (String raw : source.split("\\R")) {
            number++;
            if (raw.isBlank()) {
                continue;
            }
            String line = raw.strip().replace("^", "");
            try {
                if (ended) {
                    throw new IllegalArgumentException("Há registros após E.");
                }
                switch (line.charAt(0)) {
                    case 'H' -> {
                        if (name != null || line.length() != 19) {
                            throw new IllegalArgumentException("Cabeçalho H inválido ou repetido.");
                        }
                        name = line.substring(1, 7).strip();
                        start = hex(line.substring(7, 13));
                        length = hex(line.substring(13, 19));
                    }
                    case 'T' -> {
                        if (name == null || sawModification || line.length() < 9) {
                            throw new IllegalArgumentException("Registro T fora de ordem.");
                        }
                        int size = hex(line.substring(7, 9));
                        if (line.length() != 9 + size * 2) {
                            throw new IllegalArgumentException("Tamanho do registro T não corresponde aos bytes.");
                        }
                        texts.add(
                                new TextRecord(hex(line.substring(1, 7)), HexFormat.of().parseHex(line.substring(9))));
                    }
                    case 'M' -> {
                        if (name == null || line.length() != 9) {
                            throw new IllegalArgumentException("Registro M inválido.");
                        }
                        sawModification = true;
                        modifications.add(new ModificationRecord(hex(line.substring(1, 7)), hex(line.substring(7, 9))));
                    }
                    case 'E' -> {
                        if (name == null || line.length() != 7) {
                            throw new IllegalArgumentException("Registro E inválido.");
                        }
                        entry = hex(line.substring(1, 7));
                        ended = true;
                    }
                    default -> throw new IllegalArgumentException("Registro desconhecido: " + line.charAt(0));
                }
            } catch (IllegalArgumentException | IndexOutOfBoundsException e) {
                throw new IllegalArgumentException("Objeto, linha " + number + ": " + e.getMessage(), e);
            }
        }
        if (name == null || !ended) {
            throw new IllegalArgumentException("Objeto exige registros H e E.");
        }
        return new ObjectProgram(name, start, length, entry, texts, modifications);
    }

    private static int hex(String text) {
        if (!text.matches("[0-9A-Fa-f]+")) {
            throw new IllegalArgumentException("Campo hexadecimal inválido.");
        }
        return Integer.parseInt(text, 16);
    }
}
