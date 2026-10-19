package assembler;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Ponto de entrada do montador pelo terminal, sem iniciar a interface gráfica.
 * Uso: assembler.AssemblerCli fonte.asm [prefixo-de-saida].
 */
public final class AssemblerCli {

    private AssemblerCli() {
    }

    /**
     * Executa a montagem e informa ao sistema operacional o código de erro, se houver.
     */
    public static void main(String[] args) {
        int status = run(args);
        if (status != 0) {
            System.exit(status);
        }
    }

    /**
     * Lê o fonte e salva a listagem e, em caso de sucesso, o programa objeto.
     *
     * @param args Caminho do fonte e prefixo opcional dos arquivos de saída.
     * @return 0 para sucesso, 1 para falha de montagem/arquivo e 2 para uso inválido.
     */
    public static int run(String[] args) {
        if (args.length < 1 || args.length > 2) {
            System.err.println("Uso: assembler.AssemblerCli fonte.asm [prefixo-de-saida]");
            return 2;
        }
        try {
            Path input = Path.of(args[0]);
            String prefix = args.length == 2 ? args[1] : input.toString().replaceFirst("\\.[^.\\\\/]+$", "");
            AssemblyResult result = new TwoPassAssembler().assemble(Files.readString(input, StandardCharsets.UTF_8));
            Path listing = Path.of(prefix + ".lst");
            Files.writeString(listing, result.getListing(), StandardCharsets.UTF_8);
            if (!result.isSuccess()) {
                result.getDiagnostics().forEach(d -> System.err.println(d));
                System.err.println("Montagem falhou. Listagem: " + listing + ". Nenhum objeto novo foi emitido.");
                return 1;
            }
            Path object = Path.of(prefix + ".obj");
            Files.writeString(object, result.getObjectCode(), StandardCharsets.UTF_8);
            System.out.println("Montagem concluída: " + object + " e " + listing);
            return 0;
        } catch (IOException | IllegalArgumentException e) {
            System.err.println("Falha: " + e.getMessage());
            return 1;
        }
    }
}
