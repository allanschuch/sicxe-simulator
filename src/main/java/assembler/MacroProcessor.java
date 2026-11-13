package assembler;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Processador de macros em uma única passagem, executado antes da montagem.
 * Segue o algoritmo de Beck (seção 4.3): DEFINE, EXPAND e GETLINE cooperam sobre as
 * tabelas NAMTAB (macros), DEFTAB (corpos) e ARGTAB (argumentos da expansão em curso).
 *
 * <p>Definições aninhadas funcionam porque o corpo de uma macro é copiado para DEFTAB
 * sem interpretação: a macro interna só é definida quando a externa é expandida e o
 * GETLINE entrega a linha MACRO ao PROCESSLINE. Chamadas aninhadas funcionam porque
 * PROCESSLINE também é usado sobre as linhas lidas de DEFTAB, o que permite chamar EXPAND
 * recursivamente; cada expansão em curso mantém o seu próprio ARGTAB e ponteiro.
 *
 * <p>Sintaxe: {@code NOME MACRO &P1,&P2,...} abre a definição e {@code MEND} a fecha.
 * Na chamada, {@code [RÓTULO] NOME arg1,arg2,...}. Os parâmetros são trocados por
 * notação posicional ao entrar em DEFTAB e pelos argumentos ao sair dela.
 */
public final class MacroProcessor {

    /** Limite de linhas geradas, para interromper expansões que crescem sem controle. */
    public static final int MAX_OUTPUT_LINES = 100_000;

    private static final Pattern PARAMETER = Pattern.compile("&[A-Za-z_][A-Za-z0-9_]*");
    private static final Pattern POSITIONAL = Pattern.compile("\u0001(\\d+)\u0002");
    private static final Pattern NAME = Pattern.compile("[A-Z_][A-Z0-9_]*");

    /**
     * Expande todas as macros do fonte.
     *
     * @param source Programa fonte, possivelmente com definições e chamadas de macro.
     * @return Fonte expandido, tabelas finais e erros por linha do fonte original.
     */
    public MacroResult process(String source) {
        if (source == null) {
            throw new IllegalArgumentException("Fonte não pode ser nulo.");
        }
        Session session = new Session(source);
        session.run();
        return session.result();
    }

    /** Entrada de NAMTAB: aponta para o protótipo e para o MEND final dentro de DEFTAB. */
    private record Macro(String name, List<String> parameters, int start, int end) {
    }

    /** Expansão em curso: macro, ARGTAB e ponteiro para a próxima linha de DEFTAB. */
    private static final class Frame {
        final Macro macro;
        final List<String> arguments;
        int pointer;

        Frame(Macro macro, List<String> arguments) {
            this.macro = macro;
            this.arguments = arguments;
            this.pointer = macro.start() + 1;
        }
    }

    /** Linha separada em campos; {@code code} é a linha sem comentário e sem espaços à direita. */
    private record Statement(String code, String label, String operation, String operand) {
        boolean isComment() {
            return code.isBlank() || code.stripLeading().startsWith(".");
        }
    }

    /** Estado de um processamento, independente de qualquer outro. */
    private static final class Session {
        // --- TABELAS ---
        final List<String> deftab = new ArrayList<>();
        final Map<String, Macro> namtab = new LinkedHashMap<>();
        final Deque<Frame> expansions = new ArrayDeque<>(); // topo = expansão corrente (ARGTAB)
        // --- ENTRADA E SAÍDA ---
        final List<String> input;
        final boolean trailingNewline;
        final List<String> output = new ArrayList<>();
        final List<Diagnostic> diagnostics = new ArrayList<>();
        int next; // índice da próxima linha do arquivo fonte
        int currentLine; // linha do arquivo fonte que originou o que está sendo processado
        boolean ended;
        boolean overflow;

        Session(String source) {
            trailingNewline = source.endsWith("\n") || source.endsWith("\r");
            List<String> lines = new ArrayList<>(List.of(source.split("\\R", -1)));
            if (trailingNewline) {
                lines.remove(lines.size() - 1);
            }
            input = lines;
        }

        /** Laço principal: lê e processa linhas até END ou o fim do arquivo. */
        void run() {
            while (!ended && !overflow) {
                String line = getLine();
                if (line == null) {
                    break;
                }
                processLine(line);
            }
            // Depois de END nada é interpretado; o montador acusará o que sobrar.
            while (next < input.size() && !overflow) {
                emit(input.get(next++));
            }
        }

        MacroResult result() {
            String text = String.join("\n", output) + (trailingNewline && !output.isEmpty() ? "\n" : "");
            return new MacroResult(text, diagnostics, describeTables(), namtab.size());
        }

        // --- GETLINE ---

        /** Próxima linha lógica: do corpo em expansão (com argumentos) ou do arquivo; null no fim. */
        String getLine() {
            Frame frame = expansions.peek();
            if (frame != null) {
                if (frame.pointer >= frame.macro.end()) {
                    return null;
                }
                return substituteArguments(deftab.get(frame.pointer++), frame.arguments);
            }
            if (next >= input.size()) {
                return null;
            }
            currentLine = next + 1;
            return input.get(next++);
        }

        // --- PROCESSLINE ---

        void processLine(String raw) {
            Statement statement = parse(raw);
            if (statement.isComment()) {
                if (expansions.isEmpty()) {
                    emit(raw);
                }
                return;
            }
            String op = statement.operation();
            Macro macro = op.isEmpty() ? null : namtab.get(op);
            if (macro != null) {
                expand(statement, macro);
            } else if (op.equals("MACRO")) {
                define(statement);
            } else if (op.equals("MEND")) {
                error("MEND sem MACRO correspondente.");
            } else {
                emit(raw);
                if (op.equals("END") && expansions.isEmpty()) {
                    ended = true;
                }
            }
        }

        // --- DEFINE ---

        /** Copia o corpo para DEFTAB em notação posicional, contando o aninhamento de MACRO/MEND. */
        void define(Statement prototype) {
            int definitionLine = currentLine;
            String name = prototype.label().toUpperCase(Locale.ROOT);
            List<String> parameters = new ArrayList<>();
            boolean valid = checkName(name, definitionLine) & parseParameters(prototype.operand(), parameters, true);
            int start = deftab.size();
            deftab.add(name + " " + String.join(",", parameters));
            Deque<Set<String>> shadows = new ArrayDeque<>(); // parâmetros das macros internas em curso
            int level = 1;
            boolean closed = false;
            while (true) {
                String raw = getLine();
                if (raw == null) {
                    break;
                }
                Statement line = parse(raw);
                if (line.isComment()) {
                    continue; // comentários não entram em DEFTAB
                }
                if (line.operation().equals("MACRO")) {
                    level++;
                    List<String> inner = new ArrayList<>();
                    parseParameters(line.operand(), inner, false);
                    shadows.push(Set.copyOf(inner));
                }
                deftab.add(toPositional(line.code(), parameters, shadows));
                if (line.operation().equals("MEND")) {
                    level--;
                    if (level == 0) {
                        closed = true;
                        break;
                    }
                    shadows.pop();
                }
            }
            if (!closed) {
                error(definitionLine, "MACRO sem MEND correspondente: " + name);
            }
            // Redefinir é permitido durante uma expansão: uma macro interna é definida de novo a cada uso da externa.
            if (valid && closed && namtab.containsKey(name) && expansions.isEmpty()) {
                error(definitionLine, "Macro definida mais de uma vez: " + name);
                valid = false;
            }
            if (valid && closed) {
                namtab.put(name, new Macro(name, List.copyOf(parameters), start, deftab.size() - 1));
            } else {
                while (deftab.size() > start) {
                    deftab.remove(deftab.size() - 1);
                }
            }
        }

        boolean checkName(String name, int line) {
            if (name.isEmpty()) {
                error(line, "MACRO exige o nome da macro no rótulo (NOME MACRO &P1,&P2).");
                return false;
            }
            if (!NAME.matcher(name).matches()) {
                error(line, "Nome de macro inválido: " + name);
                return false;
            }
            if (name.equals("MACRO") || name.equals("MEND") || SourceParser.isOperation(name)) {
                error(line, "Nome de macro reservado para instrução ou diretiva: " + name);
                return false;
            }
            return true;
        }

        /** Lê a lista de parâmetros do protótipo; {@code report} controla se os erros são registrados. */
        boolean parseParameters(String operand, List<String> out, boolean report) {
            boolean valid = true;
            if (operand.isBlank()) {
                return true;
            }
            for (String piece : operand.split(",", -1)) {
                String parameter = piece.strip().toUpperCase(Locale.ROOT);
                if (!PARAMETER.matcher(parameter).matches()) {
                    valid = false;
                    if (report) {
                        error("Parâmetro inválido (use &NOME): '" + piece.strip() + "'");
                    }
                } else if (out.contains(parameter)) {
                    valid = false;
                    if (report) {
                        error("Parâmetro repetido: " + parameter);
                    }
                } else {
                    out.add(parameter);
                }
            }
            return valid;
        }

        /**
         * Troca &PARAM pela marca posicional. Parâmetros de macros internas, que ainda serão
         * definidas, ficam intactos: eles pertencem à definição interna e não à externa.
         */
        static String toPositional(String code, List<String> parameters, Deque<Set<String>> shadows) {
            return replaceParameters(code, name -> {
                for (Set<String> shadow : shadows) {
                    if (shadow.contains(name)) {
                        return null;
                    }
                }
                int index = parameters.indexOf(name);
                return index < 0 ? null : "\u0001" + (index + 1) + "\u0002";
            });
        }

        static String replaceParameters(String code, Function<String, String> replacement) {
            Matcher matcher = PARAMETER.matcher(code);
            StringBuilder out = new StringBuilder();
            while (matcher.find()) {
                String value = replacement.apply(matcher.group().toUpperCase(Locale.ROOT));
                matcher.appendReplacement(out, Matcher.quoteReplacement(value == null ? matcher.group() : value));
            }
            matcher.appendTail(out);
            return out.toString();
        }

        /** Troca a notação posicional pelos argumentos; argumentos ausentes ficam vazios. */
        static String substituteArguments(String line, List<String> arguments) {
            Matcher matcher = POSITIONAL.matcher(line);
            StringBuilder out = new StringBuilder();
            while (matcher.find()) {
                int index = Integer.parseInt(matcher.group(1)) - 1;
                matcher.appendReplacement(out,
                        Matcher.quoteReplacement(index < arguments.size() ? arguments.get(index) : ""));
            }
            matcher.appendTail(out);
            return out.toString();
        }

        // --- EXPAND ---

        /** Prepara ARGTAB, registra a chamada como comentário e processa o corpo linha a linha. */
        void expand(Statement call, Macro macro) {
            for (Frame frame : expansions) {
                if (frame.macro.name().equals(macro.name())) {
                    error("Chamada recursiva de " + macro.name()
                            + " não é permitida: não há montagem condicional para terminá-la.");
                    return;
                }
            }
            List<String> arguments = splitArguments(call.operand());
            if (arguments.size() > macro.parameters().size()) {
                error("Macro " + macro.name() + " recebe no máximo " + macro.parameters().size()
                        + " argumento(s), mas a chamada tem " + arguments.size() + ".");
                return;
            }
            emit("." + "  ".repeat(expansions.size()) + " " + call.code().strip());
            if (!call.label().isEmpty()) {
                emit(call.label() + " EQU *"); // o rótulo da chamada marca o início da expansão
            }
            Frame frame = new Frame(macro, arguments);
            expansions.push(frame);
            try {
                while (frame.pointer < macro.end() && !overflow) {
                    String line = getLine();
                    if (line == null) {
                        break;
                    }
                    processLine(line);
                }
            } finally {
                expansions.pop();
            }
        }

        /** Separa os argumentos por vírgula, ignorando vírgulas dentro de aspas ou parênteses. */
        static List<String> splitArguments(String operand) {
            List<String> arguments = new ArrayList<>();
            if (operand.isBlank()) {
                return arguments;
            }
            StringBuilder current = new StringBuilder();
            boolean quoted = false;
            int depth = 0;
            for (char c : operand.toCharArray()) {
                if (c == '\'') {
                    quoted = !quoted;
                } else if (!quoted && c == '(') {
                    depth++;
                } else if (!quoted && c == ')') {
                    depth--;
                }
                if (c == ',' && !quoted && depth <= 0) {
                    arguments.add(current.toString().strip());
                    current.setLength(0);
                } else {
                    current.append(c);
                }
            }
            arguments.add(current.toString().strip());
            return arguments;
        }

        // --- ANÁLISE DE LINHA ---

        /**
         * Separa [rótulo[:]] operação [operando] sem validar a operação, pois o nome de uma macro
         * também ocupa o campo de operação. O primeiro termo é rótulo quando não é operação,
         * macro conhecida, MACRO ou MEND, ou quando o termo seguinte é MACRO (nome da macro).
         */
        Statement parse(String raw) {
            String code = stripComment(raw).stripTrailing();
            String text = code.strip();
            if (text.isEmpty() || text.startsWith(".")) {
                return new Statement(code, "", "", "");
            }
            String[] first = text.split("\\s+", 2);
            String head = first[0];
            String rest = first.length == 2 ? first[1].strip() : "";
            String label = "";
            if (head.endsWith(":")) {
                label = head.substring(0, head.length() - 1);
            } else if (!rest.isEmpty() && rest.split("\\s+", 2)[0].equalsIgnoreCase("MACRO")) {
                label = head;
            } else if (isKnown(head.toUpperCase(Locale.ROOT))) {
                return new Statement(code, "", head.toUpperCase(Locale.ROOT), rest);
            } else {
                label = head;
            }
            if (rest.isEmpty()) {
                return new Statement(code, label, "", "");
            }
            String[] fields = rest.split("\\s+", 2);
            return new Statement(code, label, fields[0].toUpperCase(Locale.ROOT),
                    fields.length == 2 ? fields[1].strip() : "");
        }

        boolean isKnown(String word) {
            return word.equals("MACRO") || word.equals("MEND") || namtab.containsKey(word)
                    || SourceParser.isOperation(word);
        }

        /** Remove o comentário iniciado por ';' fora de aspas, como faz o analisador do montador. */
        static String stripComment(String line) {
            boolean quoted = false;
            for (int i = 0; i < line.length(); i++) {
                char c = line.charAt(i);
                if (c == '\'') {
                    quoted = !quoted;
                } else if (c == ';' && !quoted) {
                    return line.substring(0, i);
                }
            }
            return line;
        }

        // --- SAÍDA, ERROS E TABELAS ---

        void emit(String line) {
            if (output.size() >= MAX_OUTPUT_LINES) {
                if (!overflow) {
                    overflow = true;
                    error("A expansão excede " + MAX_OUTPUT_LINES + " linhas; verifique chamadas que se multiplicam.");
                }
                return;
            }
            output.add(line);
        }

        void error(String message) {
            error(currentLine, message);
        }

        /** Registra o erro uma única vez; dentro de expansões, indica a cadeia de macros. */
        void error(int line, String message) {
            if (!expansions.isEmpty()) {
                List<String> chain = new ArrayList<>();
                expansions.descendingIterator().forEachRemaining(f -> chain.add(f.macro.name()));
                message += " [expansão de " + String.join(" > ", chain) + "]";
            }
            Diagnostic diagnostic = new Diagnostic(line, message);
            if (!diagnostics.contains(diagnostic)) {
                diagnostics.add(diagnostic);
            }
        }

        /** Texto de NAMTAB e DEFTAB; a notação posicional é exibida como ?n. */
        String describeTables() {
            StringBuilder out = new StringBuilder("NAMTAB\nNOME       PARÂMETROS                  DEFTAB\n");
            namtab.values().forEach(m -> out.append(String.format("%-10s %-27s %d..%d%n", m.name(),
                    String.join(",", m.parameters()), m.start(), m.end())));
            out.append("\nDEFTAB\n");
            for (int i = 0; i < deftab.size(); i++) {
                out.append(String.format("%4d  %s%n", i, POSITIONAL.matcher(deftab.get(i)).replaceAll("?$1")));
            }
            return out.toString();
        }
    }
}
