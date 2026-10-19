package assembler;

import model.types.Opcode;
import model.types.Register;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Codificador das instruções SIC/XE suportadas pela máquina virtual.
 * Reutiliza os opcodes e os registradores da primeira entrega e produz
 * os bytes dos formatos 2, 3 e 4, incluindo as flags de endereçamento.
 */
final class InstructionEncoder {
    // --- FLAGS DOS FORMATOS 3 E 4 ---
    private static final int SIMPLE_NI = 0x03;
    private static final int IMMEDIATE_NI = 0x01;
    private static final int INDIRECT_NI = 0x02;
    private static final int INDEXED_FLAG = 0x08;
    private static final int BASE_RELATIVE_FLAG = 0x04;
    private static final int PC_RELATIVE_FLAG = 0x02;
    private static final int EXTENDED_FLAG = 0x01;

    /** Bytes da instrução e campos de endereço que precisam de relocação. */
    static final class Encoding {
        private final byte[] bytes;
        private final List<ObjectProgram.ModificationRecord> modifications;

        Encoding(byte[] bytes, List<ObjectProgram.ModificationRecord> modifications) {
            this.bytes = bytes;
            this.modifications = modifications;
        }

        byte[] getBytes() { return bytes; }

        List<ObjectProgram.ModificationRecord> getModifications() { return modifications; }
    }

    /** Expressão do operando, bits n/i e indicação de indexação pelo registrador X. */
    static final class Operand {
        private final String expression;
        private final int ni;
        private final boolean indexed;

        Operand(String expression, int ni, boolean indexed) {
            this.expression = expression;
            this.ni = ni;
            this.indexed = indexed;
        }

        String getExpression() { return expression; }
    }

    /** Separa os prefixos #/@ e o sufixo ,X sem modificar constantes entre aspas. */
    static Operand operand(String text) {
        String expression = text.strip();
        int ni = SIMPLE_NI;
        if (expression.startsWith("#")) {
            ni = IMMEDIATE_NI;
            expression = expression.substring(1).strip();
        } else if (expression.startsWith("@")) {
            ni = INDIRECT_NI;
            expression = expression.substring(1).strip();
        }
        boolean indexed = false;
        // Uma vírgula dentro de C'...' pertence à constante, não à indexação.
        boolean quoted = false;
        int comma = -1;
        for (int i = 0; i < expression.length(); i++) {
            if (expression.charAt(i) == '\'') {
                quoted = !quoted;
            }
            if (expression.charAt(i) == ',' && !quoted) {
                if (comma >= 0) {
                    throw new IllegalArgumentException("Operandos de memória inválidos.");
                }
                comma = i;
            }
        }
        if (comma >= 0) {
            if (!expression.substring(comma + 1).strip().equalsIgnoreCase("X")) {
                throw new IllegalArgumentException("A indexação exige ,X.");
            }
            indexed = true;
            expression = expression.substring(0, comma).strip();
        }
        if (expression.isEmpty()) {
            throw new IllegalArgumentException("Operando ausente.");
        }
        if (indexed && ni != SIMPLE_NI) {
            throw new IllegalArgumentException("Indexação não pode ser combinada com # ou @.");
        }
        if (expression.startsWith("=") && ni != SIMPLE_NI) {
            throw new IllegalArgumentException("Literais usam endereçamento simples.");
        }
        return new Operand(expression, ni, indexed);
    }

    /**
     * Traduz uma instrução com os símbolos e literais resolvidos.
     * Tenta PC-relativo antes de base-relativo e exige '+' quando o formato 4
     * for necessário. As posições M são deslocamentos relativos ao início do programa.
     */
    static Encoding encode(SourceLine line, int address, int startAddress, Map<String, Symbol> symbols,
            Map<String, Integer> literals, ExpressionEvaluator.Value base) {
        boolean extended = line.getOperation().startsWith("+");
        Opcode opcode = Opcode.valueOf(extended ? line.getOperation().substring(1) : line.getOperation());
        if (opcode.getFormat() == 2) {
            if (extended) {
                throw new IllegalArgumentException("Formato 4 não permitido para " + opcode);
            }
            return new Encoding(format2(opcode, line.getOperand(), symbols, address), List.of());
        }
        if (opcode == Opcode.RSUB) {
            if (!line.getOperand().isEmpty()) {
                throw new IllegalArgumentException("RSUB não recebe operandos.");
            }
            return new Encoding(extended ? new byte[] { 0x4F, 0x10, 0, 0 } : new byte[] { 0x4F, 0, 0 }, List.of());
        }
        Operand operand = operand(line.getOperand());
        if (operand.ni == IMMEDIATE_NI && opcode.name().startsWith("ST")) {
            throw new IllegalArgumentException("Armazenamento não aceita operando imediato.");
        }
        ExpressionEvaluator.Value target;
        if (operand.expression.startsWith("=")) {
            Integer literal = literals.get(Constants.literalKey(operand.expression));
            if (literal == null) {
                throw new IllegalArgumentException("Literal sem endereço.");
            }
            target = new ExpressionEvaluator.Value(literal, 1);
        } else {
            target = ExpressionEvaluator.evaluate(operand.expression, symbols, address);
        }
        if (target.getNumber() < 0 || target.getNumber() > 0xFFFFF) {
            throw new IllegalArgumentException("Operando fora dos 20 bits (0 a 1048575).");
        }
        // Os quatro bits de flags são organizados na ordem x, b, p, e.
        int flags = operand.indexed ? INDEXED_FLAG : 0;
        int field = (int) target.getNumber();
        List<ObjectProgram.ModificationRecord> modifications = new ArrayList<>();
        if (extended) {
            flags |= EXTENDED_FLAG;
            if (target.isRelative()) {
                modifications.add(new ObjectProgram.ModificationRecord(address + 1 - startAddress, 5));
            }
        } else if (target.isRelative()) {
            long displacement = target.getNumber() - (address + 3L);
            if (displacement >= -2048 && displacement <= 2047) {
                flags |= PC_RELATIVE_FLAG;
                field = (int) displacement & 0xFFF;
            } else if (base != null && base.getRelocation() == target.getRelocation()
                    && target.getNumber() - base.getNumber() >= 0 && target.getNumber() - base.getNumber() <= 4095) {
                flags |= BASE_RELATIVE_FLAG;
                field = (int) (target.getNumber() - base.getNumber());
            } else {
                throw new IllegalArgumentException(
                        "Destino fora do alcance de PC/BASE; use +" + opcode + " ou uma BASE válida.");
            }
        } else if (field > 4095) {
            if (operand.ni != IMMEDIATE_NI && base != null && base.getRelocation() == 0
                    && target.getNumber() - base.getNumber() >= 0 && target.getNumber() - base.getNumber() <= 4095) {
                flags |= BASE_RELATIVE_FLAG;
                field = (int) (target.getNumber() - base.getNumber());
            } else {
                throw new IllegalArgumentException("Valor absoluto não cabe no formato 3; use +" + opcode + ".");
            }
        }
        // Une o opcode de 6 bits às flags n/i e distribui o campo nos bytes finais.
        int first = opcode.getCode() | operand.ni;
        byte[] bytes = extended
                ? new byte[] { (byte) first, (byte) ((flags << 4) | (field >> 16)), (byte) (field >> 8), (byte) field }
                : new byte[] { (byte) first, (byte) ((flags << 4) | (field >> 8)), (byte) field };
        return new Encoding(bytes, modifications);
    }

    /** Codifica registradores e a contagem n-1 específica de SHIFTL/SHIFTR. */
    private static byte[] format2(Opcode opcode, String operand, Map<String, Symbol> symbols, int address) {
        String[] args = operand.split(",", -1);
        boolean single = opcode == Opcode.CLEAR || opcode == Opcode.TIXR;
        if (args.length != (single ? 1 : 2)) {
            throw new IllegalArgumentException(opcode + " exige " + (single ? "um operando." : "dois operandos."));
        }
        int r1 = register(args[0]);
        int r2 = 0;
        if (opcode == Opcode.SHIFTL || opcode == Opcode.SHIFTR) {
            ExpressionEvaluator.Value count = ExpressionEvaluator.evaluate(args[1], symbols, address);
            if (count.isRelative() || count.getNumber() < 1 || count.getNumber() > 16) {
                throw new IllegalArgumentException("Contagem de shift deve ser absoluta entre 1 e 16.");
            }
            r2 = (int) count.getNumber() - 1;
        } else if (!single) {
            r2 = register(args[1]);
        }
        return new byte[] { (byte) opcode.getCode(), (byte) ((r1 << 4) | r2) };
    }

    private static int register(String text) {
        try {
            Register register = Register.valueOf(text.strip().toUpperCase(Locale.ROOT));
            if (register == Register.F) {
                throw new IllegalArgumentException("Registrador F não é suportado pela VM.");
            }
            return register.getCode();
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Registrador inválido ou não suportado: " + text.strip());
        }
    }
}
