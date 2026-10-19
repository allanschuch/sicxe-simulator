package assembler;

import java.util.Locale;
import java.util.Map;

/**
 * Avaliador de expressões utilizadas nos operandos e nas diretivas.
 * Calcula o valor numérico e acompanha o coeficiente de relocação para distinguir
 * constantes absolutas de endereços relativos ao programa.
 */
public final class ExpressionEvaluator {

    /** Resultado numérico e quantidade líquida de termos relativos da expressão. */
    public static final class Value {
        private final long number;
        private final int relocation;

        public Value(long number, int relocation) {
            this.number = number;
            this.relocation = relocation;
        }

        public long getNumber() { return number; }

        public int getRelocation() { return relocation; }

        public boolean isRelative() { return relocation == 1; }
    }

    /** Diferencia uma referência futura de uma expressão sintaticamente inválida. */
    public static final class UndefinedSymbol extends IllegalArgumentException {

        public UndefinedSymbol(String symbol) {
            super("Símbolo indefinido: " + symbol);
        }
    }

    private final Map<String, Symbol> symbols;
    private final int location;
    private final String expression;
    private int position;

    private ExpressionEvaluator(String expression, Map<String, Symbol> symbols, int location) {
        this.expression = expression;
        this.symbols = symbols;
        this.location = location;
    }

    /**
     * Resolve uma expressão com os símbolos conhecidos no momento da chamada.
     *
     * @param expression Expressão contendo números, símbolos e operadores.
     * @param symbols Tabela de símbolos construída pela primeira passagem.
     * @param location Endereço da linha, utilizado para resolver o símbolo '*'.
     * @return Valor absoluto (coeficiente 0) ou relativo (coeficiente 1).
     * @throws IllegalArgumentException Se a expressão ou sua relocação for inválida.
     */
    public static Value evaluate(String expression, Map<String, Symbol> symbols, int location) {
        try {
            ExpressionEvaluator parser = new ExpressionEvaluator(expression, symbols, location);
            Value value = parser.sum();
            parser.space();
            if (parser.position != expression.length()) {
                throw new IllegalArgumentException("Expressão inválida: " + expression);
            }
            if (value.relocation != 0 && value.relocation != 1) {
                throw new IllegalArgumentException(
                        "Expressão com combinação de símbolos relativos inválida: " + expression);
            }
            return value;
        } catch (ArithmeticException e) {
            throw new IllegalArgumentException("Overflow ou divisão por zero na expressão: " + expression);
        }
    }

    /** Processa somas e subtrações, preservando a contagem de termos relativos. */
    private Value sum() {
        Value left = product();
        while (true) {
            if (take('+')) {
                Value right = product();
                left = new Value(Math.addExact(left.number, right.number),
                        Math.addExact(left.relocation, right.relocation));
            } else if (take('-')) {
                Value right = product();
                left = new Value(Math.subtractExact(left.number, right.number),
                        Math.subtractExact(left.relocation, right.relocation));
            } else {
                return left;
            }
        }
    }

    /** Processa multiplicação e divisão apenas entre termos absolutos. */
    private Value product() {
        Value left = primary();
        while (true) {
            boolean multiply = take('*');
            if (!multiply && !take('/')) {
                return left;
            }
            Value right = primary();
            if (left.relocation != 0 || right.relocation != 0) {
                throw new IllegalArgumentException("Multiplicação e divisão exigem valores absolutos.");
            }
            if (!multiply && left.number == Long.MIN_VALUE && right.number == -1) {
                throw new ArithmeticException();
            }
            left = new Value(multiply ? Math.multiplyExact(left.number, right.number) : left.number / right.number, 0);
        }
    }

    /** Resolve sinais, parênteses, localização corrente, números e símbolos. */
    private Value primary() {
        space();
        if (take('+')) {
            return primary();
        }
        if (take('-')) {
            Value value = primary();
            return new Value(Math.negateExact(value.number), Math.negateExact(value.relocation));
        }
        if (take('(')) {
            Value value = sum();
            if (!take(')')) {
                throw new IllegalArgumentException("Parêntese não fechado.");
            }
            return value;
        }
        if (take('*')) {
            return new Value(location, 1);
        }
        int start = position;
        while (position < expression.length()
                && (Character.isLetterOrDigit(expression.charAt(position)) || expression.charAt(position) == '_')) {
            position++;
        }
        if (start == position) {
            throw new IllegalArgumentException("Operando/expressão ausente ou inválido: " + expression);
        }
        String token = expression.substring(start, position);
        if (Character.isDigit(token.charAt(0))) {
            try {
                return new Value(token.matches("(?i)0x[0-9a-f]+") ? Long.parseLong(token.substring(2), 16)
                        : Long.parseLong(token), 0);
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException("Número inválido: " + token);
            }
        }
        Symbol symbol = symbols.get(token.toUpperCase(Locale.ROOT));
        if (symbol == null) {
            throw new UndefinedSymbol(token);
        }
        return new Value(symbol.getValue(), symbol.isRelative() ? 1 : 0);
    }

    private boolean take(char token) {
        space();
        if (position < expression.length() && expression.charAt(position) == token) {
            position++;
            return true;
        }
        return false;
    }

    private void space() {
        while (position < expression.length() && Character.isWhitespace(expression.charAt(position))) {
            position++;
        }
    }
}
