package assembler;

import model.hardware.Memory;
import model.types.Opcode;
import java.io.ByteArrayOutputStream;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Montador de duas passagens para a arquitetura SIC/XE.
 * A primeira passagem calcula endereços e constrói a tabela de símbolos.
 * A segunda resolve os operandos e gera o programa objeto e a listagem.
 * Cada montagem mantém seus próprios dados, sem depender da interface gráfica.
 */
public final class TwoPassAssembler {

    /**
     * Monta um programa completo a partir do texto assembly.
     *
     * @param source Programa fonte, incluindo instruções, diretivas e comentários.
     * @return Resultado contendo objeto, símbolos, listagem e erros por linha.
     */
    public AssemblyResult assemble(String source) {
        if (source == null) {
            throw new IllegalArgumentException("Fonte não pode ser nulo.");
        }
        Session session = new Session();
        session.passOne(source);
        return session.passTwo();
    }

    /** Dados intermediários preservados entre a primeira e a segunda passagem. */
    private static final class Line {
        final SourceLine source;
        int address;
        int size;
        byte[] bytes = new byte[0];
        byte[] literalBytes;

        Line(SourceLine source, int address) {
            this.source = source;
            this.address = address;
        }
    }

    /** EQU que aguarda a definição de símbolos referenciados posteriormente. */
    private static final class PendingEqu {
        final SourceLine line;
        final int address;

        PendingEqu(SourceLine line, int address) {
            this.line = line;
            this.address = address;
        }
    }

    /** Constante literal alocada no próximo LTORG ou no encerramento do fonte. */
    private static final class Literal {
        final String text;
        final byte[] bytes;
        final int lineNumber;
        Integer address;

        Literal(String text, byte[] bytes, int lineNumber) {
            this.text = text;
            this.bytes = bytes;
            this.lineNumber = lineNumber;
        }
    }

    /** Estado de uma única montagem, compartilhado apenas pelas suas duas passagens. */
    private static final class Session {
        // --- TABELAS E REPRESENTAÇÃO INTERMEDIÁRIA ---
        final Map<String, Symbol> symbols = new LinkedHashMap<>();
        final Map<String, PendingEqu> equations = new LinkedHashMap<>();
        final Map<String, Literal> literalTable = new LinkedHashMap<>();
        final Set<String> declared = new LinkedHashSet<>();
        final List<Line> lines = new ArrayList<>();
        final List<Diagnostic> diagnostics = new ArrayList<>();
        final BitSet allocated = new BitSet(Memory.MAX_MEMORY_SIZE);
        final ArrayDeque<Integer> origins = new ArrayDeque<>();
        // --- CONTADOR DE LOCALIZAÇÃO E LIMITES DO PROGRAMA ---
        int location; // LOCCTR: endereço em bytes da próxima instrução ou dado.
        int start;
        int highWater; // Maior endereço alcançado; considera avanços e retornos de ORG.
        int firstInstruction = -1;
        int endLine;
        String endOperand = "";
        String name = "NONAME";
        boolean ended;
        boolean seenStatement;

        /** Atribui endereços, valida declarações e registra símbolos e literais. */
        void passOne(String source) {
            SourceParser parser = new SourceParser();
            String[] rawLines = source.split("\\R", -1);
            for (int i = 0; i < rawLines.length; i++) {
                SourceLine sourceLine;
                try {
                    sourceLine = parser.parse(rawLines[i], i + 1);
                } catch (IllegalArgumentException e) {
                    error(i + 1, e.getMessage());
                    lines.add(new Line(new SourceLine(i + 1, rawLines[i], "", "", ""), -1));
                    continue;
                }
                Line line = new Line(sourceLine, sourceLine.isComment() ? -1 : location);
                if (sourceLine.isComment()) {
                    lines.add(line);
                    continue;
                }
                if (ended) {
                    lines.add(line);
                    error(i + 1, "Instrução após END.");
                    continue;
                }
                String op = sourceLine.getOperation();
                try {
                    if (op.equals("END")) {
                        if (!sourceLine.getLabel().isEmpty()) {
                            throw new IllegalArgumentException("END não recebe rótulo.");
                        }
                        flushLiterals();
                        line.address = location;
                        lines.add(line);
                        ended = true;
                        endOperand = sourceLine.getOperand();
                        endLine = sourceLine.getLineNumber();
                        continue;
                    }
                    lines.add(line);
                    if (op.equals("START")) {
                        if (seenStatement) {
                            throw new IllegalArgumentException("START deve ser a primeira operação.");
                        }
                        seenStatement = true;
                        String address = sourceLine.getOperand();
                        if (!address.matches("(?i)(0x)?[0-9a-f]{1,6}")) {
                            throw new IllegalArgumentException("START exige endereço hexadecimal.");
                        }
                        long value = Long.parseLong(address.replaceFirst("(?i)^0x", ""), 16);
                        if (value >= Memory.MAX_MEMORY_SIZE) {
                            throw new IllegalArgumentException("START fora da memória.");
                        }
                        start = location = highWater = (int) value;
                        line.address = start;
                        if (!sourceLine.getLabel().isEmpty()) {
                            name = sourceLine.getLabel();
                            if (name.length() > 6) {
                                throw new IllegalArgumentException("Nome em START deve ter até 6 caracteres.");
                            }
                            define(sourceLine, start, true);
                        }
                        continue;
                    }
                    seenStatement = true;
                    resolveEquations(false);
                    if (op.equals("EQU")) {
                        if (sourceLine.getLabel().isEmpty()) {
                            throw new IllegalArgumentException("EQU exige rótulo.");
                        }
                        claim(sourceLine);
                        equations.put(sourceLine.getLabel(), new PendingEqu(sourceLine, location));
                        resolveEquations(false);
                        continue;
                    }
                    if (!sourceLine.getLabel().isEmpty()) {
                        define(sourceLine, location, true);
                    }
                    switch (op) {
                        case "ORG" -> {
                            if (sourceLine.getOperand().isEmpty()) {
                                if (origins.isEmpty()) {
                                    throw new IllegalArgumentException("ORG sem origem anterior para restaurar.");
                                }
                                location = origins.pop();
                            } else {
                                long value = evaluate(sourceLine.getOperand(), location).getNumber();
                                if (value < start || value >= Memory.MAX_MEMORY_SIZE) {
                                    throw new IllegalArgumentException("ORG fora da área de montagem.");
                                }
                                origins.push(location);
                                location = (int) value;
                            }
                            line.address = location;
                        }
                        case "BASE" -> requireOperand(sourceLine);
                        case "NOBASE" -> requireEmpty(sourceLine);
                        case "LTORG" -> {
                            requireEmpty(sourceLine);
                            flushLiterals();
                        }
                        case "BYTE" -> {
                            line.size = Constants.bytes(sourceLine.getOperand()).length;
                            allocate(line.size);
                        }
                        case "WORD" -> {
                            requireOperand(sourceLine);
                            line.size = 3;
                            allocate(3);
                        }
                        case "RESB", "RESW" -> {
                            ExpressionEvaluator.Value quantity = evaluate(sourceLine.getOperand(), location);
                            long bytes = quantity.getNumber();
                            if (quantity.isRelative() || bytes < 0 || bytes > Memory.MAX_MEMORY_SIZE) {
                                throw new IllegalArgumentException(
                                        "Reserva exige quantidade absoluta não negativa dentro da memória.");
                            }
                            if (op.equals("RESW")) {
                                bytes *= 3;
                            }
                            if (bytes > Memory.MAX_MEMORY_SIZE) {
                                throw new IllegalArgumentException("Reserva excede a memória.");
                            }
                            line.size = (int) bytes;
                            allocate(line.size);
                        }
                        default -> {
                            boolean extended = op.startsWith("+");
                            Opcode opcode = Opcode.valueOf(extended ? op.substring(1) : op);
                            if (extended && opcode.getFormat() == 2) {
                                throw new IllegalArgumentException("Formato 4 não permitido para " + opcode);
                            }
                            line.size = extended ? 4 : opcode.getFormat();
                            if (opcode.getFormat() == 3 && opcode != Opcode.RSUB) {
                                InstructionEncoder.Operand operand = InstructionEncoder
                                        .operand(sourceLine.getOperand());
                                if (operand.getExpression().startsWith("=")) {
                                    registerLiteral(operand.getExpression(), sourceLine.getLineNumber());
                                }
                            }
                            allocate(line.size);
                            if (firstInstruction < 0) {
                                firstInstruction = line.address;
                            }
                        }
                    }
                } catch (IllegalArgumentException e) {
                    error(sourceLine.getLineNumber(), e.getMessage());
                }
            }
            if (!ended) {
                endLine = rawLines.length;
                error(endLine, "Diretiva END ausente.");
                flushLiterals();
            }
            resolveEquations(true);
            if (firstInstruction < 0) {
                error(endLine, "Programa não contém instrução executável.");
            }
        }

        /** Reserva um nome antes de inserir um símbolo ou uma definição EQU pendente. */
        void claim(SourceLine line) {
            if (!declared.add(line.getLabel())) {
                throw new IllegalArgumentException("Símbolo definido mais de uma vez: " + line.getLabel());
            }
        }

        /** Associa um rótulo ao valor e à linha em que foi declarado. */
        void define(SourceLine line, long value, boolean relative) {
            claim(line);
            symbols.put(line.getLabel(), new Symbol(line.getLabel(), value, relative, line.getLineNumber()));
        }

        ExpressionEvaluator.Value evaluate(String text, int address) {
            return ExpressionEvaluator.evaluate(text, symbols, address);
        }

        /** Resolve dependências EQU conhecidas e acusa ciclos ao terminar a passagem. */
        void resolveEquations(boolean finish) {
            boolean progress;
            do {
                progress = false;
                var iterator = equations.entrySet().iterator();
                while (iterator.hasNext()) {
                    var entry = iterator.next();
                    PendingEqu equation = entry.getValue();
                    try {
                        ExpressionEvaluator.Value value = evaluate(equation.line.getOperand(), equation.address);
                        symbols.put(entry.getKey(), new Symbol(entry.getKey(), value.getNumber(), value.isRelative(),
                                equation.line.getLineNumber()));
                        iterator.remove();
                        progress = true;
                    } catch (ExpressionEvaluator.UndefinedSymbol e) {
                        // As definições posteriores podem resolver a expressão antes da passagem 2.
                    } catch (IllegalArgumentException e) {
                        error(equation.line.getLineNumber(), e.getMessage());
                        iterator.remove();
                    }
                }
            } while (progress);
            if (finish) {
                equations.values().forEach(e -> error(e.line.getLineNumber(),
                        "EQU não resolvido (símbolo indefinido ou dependência circular): " + e.line.getOperand()));
                equations.clear();
            }
        }

        /** Avança LOCCTR e impede que reservas ou ORG ocupem regiões sobrepostas. */
        void allocate(int size) {
            if (size < 0 || location < start || (long) location + size > Memory.MAX_MEMORY_SIZE) {
                throw new IllegalArgumentException("Programa excede os limites de memória.");
            }
            if (size != 0) {
                int collision = allocated.nextSetBit(location);
                if (collision >= 0 && collision < location + size) {
                    throw new IllegalArgumentException("Área de memória sobreposta por ORG.");
                }
                allocated.set(location, location + size);
            }
            location += size;
            highWater = Math.max(highWater, location);
        }

        /** Registra uma constante literal, reutilizando constantes com os mesmos bytes. */
        void registerLiteral(String text, int number) {
            String key = Constants.literalKey(text);
            byte[] bytes = Constants.bytes(text.substring(1));
            if (bytes.length == 0) {
                throw new IllegalArgumentException("Literal vazio não é permitido.");
            }
            literalTable.putIfAbsent(key, new Literal(text, bytes, number));
        }

        /** Atribui endereço aos literais pendentes e acrescenta suas linhas à listagem. */
        void flushLiterals() {
            for (Literal literal : literalTable.values()) {
                if (literal.address != null) {
                    continue;
                }
                try {
                    int address = location;
                    allocate(literal.bytes.length);
                    literal.address = address;
                    Line line = new Line(new SourceLine(literal.lineNumber, "*       " + literal.text, "", "BYTE", ""),
                            address);
                    line.size = literal.bytes.length;
                    line.literalBytes = literal.bytes;
                    lines.add(line);
                } catch (IllegalArgumentException e) {
                    error(literal.lineNumber, e.getMessage());
                }
            }
        }

        /** Resolve operandos e emite bytes; o objeto só é liberado quando não há erros. */
        AssemblyResult passTwo() {
            List<ObjectProgram.ModificationRecord> modifications = new ArrayList<>();
            Map<String, Integer> literals = new LinkedHashMap<>();
            literalTable.forEach((key, literal) -> {
                if (literal.address != null) {
                    literals.put(key, literal.address);
                }
            });
            ExpressionEvaluator.Value base = null;
            for (Line line : lines) {
                SourceLine source = line.source;
                if (line.literalBytes != null) {
                    line.bytes = line.literalBytes;
                    continue;
                }
                if (source.isComment()) {
                    continue;
                }
                try {
                    switch (source.getOperation()) {
                        case "START", "END", "RESB", "RESW", "EQU", "ORG", "LTORG" -> {
                        }
                        case "NOBASE" -> base = null;
                        case "BASE" -> {
                            base = null;
                            ExpressionEvaluator.Value value = evaluate(source.getOperand(), line.address);
                            if (value.getNumber() < 0 || value.getNumber() >= Memory.MAX_MEMORY_SIZE) {
                                throw new IllegalArgumentException("BASE fora da memória.");
                            }
                            base = value;
                        }
                        case "BYTE" -> line.bytes = Constants.bytes(source.getOperand());
                        case "WORD" -> {
                            ExpressionEvaluator.Value value = evaluate(source.getOperand(), line.address);
                            line.bytes = Constants.word(value.getNumber());
                            if (value.isRelative()) {
                                modifications.add(new ObjectProgram.ModificationRecord(line.address - start, 6));
                            }
                        }
                        default -> {
                            InstructionEncoder.Encoding encoding = InstructionEncoder.encode(source, line.address,
                                    start, symbols, literals, base);
                            line.bytes = encoding.getBytes();
                            modifications.addAll(encoding.getModifications());
                        }
                    }
                } catch (IllegalArgumentException e) {
                    error(source.getLineNumber(), e.getMessage());
                }
            }
            int entry = firstInstruction;
            if (!endOperand.isEmpty()) {
                try {
                    long value = evaluate(endOperand, location).getNumber();
                    if (value < 0 || value >= Memory.MAX_MEMORY_SIZE) {
                        throw new IllegalArgumentException("Entrada fora da memória.");
                    }
                    entry = (int) value;
                    final int expected = entry;
                    if (lines.stream()
                            .noneMatch(l -> l.address == expected && isInstruction(l.source.getOperation()))) {
                        throw new IllegalArgumentException("END deve apontar para o início de uma instrução.");
                    }
                } catch (IllegalArgumentException e) {
                    error(endLine, e.getMessage());
                }
            }
            ObjectProgram program = null;
            if (diagnostics.isEmpty()) {
                try {
                    program = new ObjectProgram(name, start, highWater - start, entry, textRecords(), modifications);
                } catch (IllegalArgumentException e) {
                    error(endLine, e.getMessage());
                }
            }
            List<ListingLine> listing = lines.stream().map(l -> new ListingLine(l.source.getLineNumber(), l.address,
                    l.source.getSource(), ListingLine.hex(l.bytes))).toList();
            diagnostics.sort(Comparator.comparingInt(Diagnostic::getLineNumber));
            return new AssemblyResult(program, listing, symbols, diagnostics);
        }

        /** Agrupa bytes contíguos em registros T de até 30 bytes, respeitando reservas. */
        List<ObjectProgram.TextRecord> textRecords() {
            List<ObjectProgram.TextRecord> records = new ArrayList<>();
            ByteArrayOutputStream buffer = new ByteArrayOutputStream(ObjectProgram.MAX_TEXT_RECORD_BYTES);
            int beginning = -1;
            List<Line> emitted = lines.stream().filter(l -> l.bytes.length > 0)
                    .sorted(Comparator.comparingInt(l -> l.address)).toList();
            for (Line line : emitted) {
                for (int i = 0; i < line.bytes.length; i++) {
                    int address = line.address + i;
                    if (buffer.size() > 0 && (buffer.size() == ObjectProgram.MAX_TEXT_RECORD_BYTES
                            || beginning + buffer.size() != address)) {
                        records.add(new ObjectProgram.TextRecord(beginning, buffer.toByteArray()));
                        buffer.reset();
                    }
                    if (buffer.size() == 0) {
                        beginning = address;
                    }
                    buffer.write(line.bytes[i]);
                }
            }
            if (buffer.size() > 0) {
                records.add(new ObjectProgram.TextRecord(beginning, buffer.toByteArray()));
            }
            return records;
        }

        static boolean isInstruction(String op) {
            return !op.isEmpty() && !SourceParser.DIRECTIVES.contains(op);
        }

        static void requireOperand(SourceLine line) {
            if (line.getOperand().isEmpty()) {
                throw new IllegalArgumentException(line.getOperation() + " exige operando.");
            }
        }

        static void requireEmpty(SourceLine line) {
            if (!line.getOperand().isEmpty()) {
                throw new IllegalArgumentException(line.getOperation() + " não recebe operandos.");
            }
        }

        /** Guarda o diagnóstico uma única vez, mesmo quando as duas passagens o detectam. */
        void error(int number, String message) {
            Diagnostic diagnostic = new Diagnostic(number, message);
            if (!diagnostics.contains(diagnostic)) {
                diagnostics.add(diagnostic);
            }
        }
    }
}
