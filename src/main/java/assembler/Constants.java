package assembler;

import java.nio.charset.StandardCharsets;
import java.util.HexFormat;

/**
 * Conversão das constantes BYTE, WORD e dos literais do montador.
 * Mantém a ordem de bytes utilizada pela memória SIC/XE: mais significativo primeiro.
 */
final class Constants {

    private Constants() {
    }

    /**
     * Converte C'...' ASCII ou X'...' hexadecimal em uma sequência de bytes.
     */
    static byte[] bytes(String text) {
        if (text.length() < 3 || text.charAt(1) != '\'' || text.charAt(text.length() - 1) != '\'') {
            throw new IllegalArgumentException("Use C'caracteres' ou X'hexadecimal'.");
        }
        String content = text.substring(2, text.length() - 1);
        if (content.indexOf('\'') >= 0) {
            throw new IllegalArgumentException("Aspas internas não são permitidas em constantes.");
        }
        if (Character.toUpperCase(text.charAt(0)) == 'C') {
            if (content.chars().anyMatch(c -> c > 127)) {
                throw new IllegalArgumentException("Constantes C devem conter caracteres ASCII.");
            }
            return content.getBytes(StandardCharsets.US_ASCII);
        }
        if (Character.toUpperCase(text.charAt(0)) == 'X' && content.matches("(?:[0-9a-fA-F]{2})*")) {
            return HexFormat.of().parseHex(content);
        }
        throw new IllegalArgumentException("Constante hexadecimal inválida; cada byte exige dois dígitos.");
    }

    /**
     * Converte um inteiro de 24 bits em três bytes, incluindo complemento de dois.
     */
    static byte[] word(long number) {
        if (number < -0x800000L || number > 0xFFFFFFL) {
            throw new IllegalArgumentException("WORD fora dos 24 bits: " + number);
        }
        return new byte[] { (byte) (number >> 16), (byte) (number >> 8), (byte) number };
    }

    /**
     * Cria uma chave pelo conteúdo em bytes para evitar duplicação na tabela de literais.
     */
    static String literalKey(String operand) {
        String text = operand.strip();
        if (!text.startsWith("=")) {
            throw new IllegalArgumentException("Literal inválido.");
        }
        String constant = text.substring(1);
        byte[] bytes = bytes(constant);
        // C e X equivalentes compartilham o mesmo literal, preservando o conteúdo em bytes.
        return "=" + HexFormat.of().withUpperCase().formatHex(bytes);
    }
}
