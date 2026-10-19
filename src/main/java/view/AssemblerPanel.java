package view;

import assembler.AssemblyResult;
import assembler.ObjectProgram;
import controller.MachineController;
import javax.swing.*;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.filechooser.FileNameExtensionFilter;
import java.awt.*;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.ExecutionException;

/**
 * Painel de edição e montagem do programa assembly.
 * Apresenta fonte, listagem, símbolos e diagnósticos e encaminha os comandos
 * ao controlador. A montagem ocorre fora da Thread gráfica do Swing.
 */
public final class AssemblerPanel extends JPanel {
    private final MachineController controller;
    private final JTextArea source = createTextArea(true);
    private final JTextArea listing = createTextArea(false);
    private final JTextArea object = createTextArea(false);
    private final JTextArea diagnostics = createTextArea(false);
    private final JLabel status = new JLabel("Abra um arquivo ou edite o exemplo e clique em Montar.");
    private final JButton assemble = new JButton("Montar");
    private final JButton load = new JButton("Carregar na VM");
    private final JButton exportObject = new JButton("Salvar objeto");
    private final JButton exportListing = new JButton("Salvar listagem");
    // A revisão impede a exibição de um resultado calculado para um fonte já alterado.
    private AssemblyResult result;
    private long revision;
    private Path sourcePath;
    private boolean dirty;
    private final Runnable showMachine;

    /**
     * Inicializa o painel com o mesmo controlador utilizado pela aba da máquina.
     */
    public AssemblerPanel(MachineController controller, Runnable showMachine) {
        super(new BorderLayout(8, 8));
        this.controller = controller;
        this.showMachine = showMachine;
        initComponents();
    }

    /** Constrói os painéis, os botões e os ouvintes da aba de montagem. */
    private void initComponents() {
        // --- EDITOR E BARRA DE AÇÕES ---
        setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));
        source.setText(
                "DEMO    START 0\nFIRST   CLEAR X\n        LDA #0\n        LDT #5\nLOOP    ADD #2\n        TIXR T\n        JLT LOOP\n        STA RESULT\nSTOP    J STOP\nRESULT  RESW 1\n        END FIRST\n");
        JPanel actions = new JPanel(new FlowLayout(FlowLayout.LEFT));
        JButton open = new JButton("Abrir fonte");
        JButton save = new JButton("Salvar fonte");
        JButton openObject = new JButton("Abrir objeto");
        for (JButton button : new JButton[] { open, save, assemble, load, exportObject, exportListing, openObject }) {
            actions.add(button);
        }
        add(actions, BorderLayout.NORTH);
        // --- SAÍDAS DA MONTAGEM ---
        JTabbedPane output = new JTabbedPane();
        output.addTab("Listagem e símbolos", new JScrollPane(listing));
        output.addTab("Objeto", new JScrollPane(object));
        output.addTab("Erros", new JScrollPane(diagnostics));
        JScrollPane editor = new JScrollPane(source);
        editor.setBorder(BorderFactory.createTitledBorder("Fonte assembly"));
        JSplitPane split = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, editor, output);
        split.setResizeWeight(0.45);
        split.setDividerLocation(440);
        editor.setMinimumSize(new Dimension(220, 100));
        output.setMinimumSize(new Dimension(250, 100));
        add(split, BorderLayout.CENTER);
        add(status, BorderLayout.SOUTH);
        // --- EVENTOS DE EDIÇÃO, MONTAGEM E ARQUIVOS ---
        source.getDocument().addDocumentListener(new DocumentListener() {

            @Override
            public void insertUpdate(DocumentEvent e) {
                invalidateResult();
            }

            @Override
            public void removeUpdate(DocumentEvent e) {
                invalidateResult();
            }

            @Override
            public void changedUpdate(DocumentEvent e) {
                invalidateResult();
            }
        });
        open.addActionListener(e -> openSource());
        save.addActionListener(e -> saveSource());
        assemble.addActionListener(e -> assemble());
        load.addActionListener(e -> load());
        exportObject.addActionListener(e -> {
            if (result != null && result.isSuccess()) {
                saveText(result.getObjectCode(), "obj");
            }
        });
        exportListing.addActionListener(e -> {
            if (result != null) {
                saveText(result.getListing(), "lst");
            }
        });
        openObject.addActionListener(e -> openObject());
        updateButtons();
    }

    /**
     * Cria uma área monoespaçada para fonte ou saída da montagem.
     */
    private static JTextArea createTextArea(boolean editable) {
        JTextArea area = new JTextArea();
        area.setEditable(editable);
        area.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 13));
        area.setTabSize(8);
        return area;
    }

    /**
     * Invalida o resultado anterior sempre que o conteúdo do editor mudar.
     */
    private void invalidateResult() {
        revision++;
        dirty = true;
        result = null;
        updateButtons();
        listing.setText("");
        object.setText("");
        diagnostics.setText("");
        status.setText("Fonte alterado. Monte novamente para carregar ou exportar.");
    }

    /**
     * Habilita carga e exportação somente quando o resultado atual permitir.
     */
    private void updateButtons() {
        load.setEnabled(result != null && result.isSuccess());
        exportObject.setEnabled(result != null && result.isSuccess());
        exportListing.setEnabled(result != null);
    }

    /**
     * Executa as duas passagens em segundo plano e atualiza a View ao concluir.
     */
    private void assemble() {
        String text = source.getText();
        long version = revision;
        result = null;
        updateButtons();
        assemble.setEnabled(false);
        status.setText("Montando...");
        new SwingWorker<AssemblyResult, Void>() {

            @Override
            protected AssemblyResult doInBackground() {
                return controller.assemble(text);
            }

            @Override
            protected void done() {
                assemble.setEnabled(true);
                if (revision != version) {
                    status.setText("O fonte mudou durante a montagem. Monte novamente.");
                    return;
                }
                try {
                    result = get();
                    listing.setText(result.getListing());
                    object.setText(result.getObjectCode());
                    diagnostics.setText(result.getDiagnostics().stream().map(Object::toString)
                            .collect(java.util.stream.Collectors.joining("\n")));
                    if (result.isSuccess()) {
                        status.setText(
                                String.format("Montagem concluída. %d bytes; entrada %06X. Pronto para carregar.",
                                        result.getProgram().getLength(), result.getProgram().getEntryPoint()));
                    } else {
                        status.setText("Montagem falhou: " + result.getDiagnostics().size()
                                + " erro(s). Consulte Erros e Listagem.");
                    }
                    listing.setCaretPosition(0);
                    object.setCaretPosition(0);
                    diagnostics.setCaretPosition(0);
                    updateButtons();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    showError("Montagem interrompida.");
                } catch (ExecutionException e) {
                    showError("Falha de montagem: " + e.getCause().getMessage());
                }
            }
        }.execute();
    }

    /**
     * Solicita a origem de carga e encaminha o objeto válido ao controlador.
     */
    private void load() {
        if (result == null || !result.isSuccess()) {
            return;
        }
        Integer address = loadAddress(result.getProgram().getStartAddress());
        if (address != null && controller.loadProgram(result.getProgram(), address)) {
            status.setText(String.format("Programa carregado; entrada %06X.", controller.getEntryPoint()));
            showMachine.run();
        }
    }

    /**
     * Lê e valida o endereço hexadecimal informado na janela de carga.
     */
    private Integer loadAddress(int defaultAddress) {
        String text = JOptionPane.showInputDialog(this, "Endereço de carga (hexadecimal):",
                String.format("%06X", defaultAddress));
        if (text == null) {
            return null;
        }
        try {
            String value = text.strip().replaceFirst("(?i)^0x", "");
            if (!value.matches("[0-9a-fA-F]{1,6}")) {
                throw new NumberFormatException();
            }
            return Integer.parseInt(value, 16);
        } catch (NumberFormatException e) {
            showError("Endereço hexadecimal inválido.");
            return null;
        }
    }

    /**
     * Cria o seletor com filtro para a extensão e a pasta do fonte atual.
     */
    private JFileChooser chooser(String extension) {
        JFileChooser chooser = new JFileChooser(
                sourcePath == null ? "test-programs" : sourcePath.toAbsolutePath().getParent().toString());
        chooser.setFileFilter(new FileNameExtensionFilter("Arquivo ." + extension, extension));
        return chooser;
    }

    /**
     * Abre um arquivo assembly no editor, verificando alterações não salvas.
     */
    private void openSource() {
        if (dirty && JOptionPane.showConfirmDialog(this, "Descartar as alterações no fonte?", "Abrir fonte",
                JOptionPane.OK_CANCEL_OPTION) != JOptionPane.OK_OPTION) {
            return;
        }
        JFileChooser chooser = chooser("asm");
        if (chooser.showOpenDialog(this) == JFileChooser.APPROVE_OPTION) {
            try {
                Path path = chooser.getSelectedFile().toPath();
                String text = Files.readString(path, StandardCharsets.UTF_8);
                source.setText(text);
                sourcePath = path;
                source.setCaretPosition(0);
                dirty = false;
                status.setText("Fonte aberto: " + path.getFileName());
            } catch (IOException e) {
                showError(e.getMessage());
            }
        }
    }

    /**
     * Salva o fonte em UTF-8 e atualiza o caminho do arquivo em edição.
     */
    private void saveSource() {
        JFileChooser chooser = chooser("asm");
        if (sourcePath != null) {
            chooser.setSelectedFile(sourcePath.toFile());
        }
        if (chooser.showSaveDialog(this) != JFileChooser.APPROVE_OPTION) {
            return;
        }
        Path path = withExtension(chooser.getSelectedFile().toPath(), "asm");
        if (!confirmOverwrite(path)) {
            return;
        }
        try {
            Files.writeString(path, source.getText(), StandardCharsets.UTF_8);
            sourcePath = path;
            dirty = false;
            status.setText("Fonte salvo: " + path.getFileName());
        } catch (IOException e) {
            showError(e.getMessage());
        }
    }

    /**
     * Exporta o objeto ou a listagem produzidos pela montagem atual.
     */
    private void saveText(String text, String extension) {
        JFileChooser chooser = chooser(extension);
        if (sourcePath != null) {
            chooser.setSelectedFile(
                    Path.of(sourcePath.toString().replaceFirst("\\.[^.\\\\/]+$", "") + "." + extension).toFile());
        }
        if (chooser.showSaveDialog(this) != JFileChooser.APPROVE_OPTION) {
            return;
        }
        Path path = withExtension(chooser.getSelectedFile().toPath(), extension);
        if (!confirmOverwrite(path)) {
            return;
        }
        try {
            Files.writeString(path, text, StandardCharsets.UTF_8);
            status.setText("Arquivo salvo: " + path.getFileName());
        } catch (IOException e) {
            showError(e.getMessage());
        }
    }

    private static Path withExtension(Path path, String extension) {
        return path.toString().toLowerCase(java.util.Locale.ROOT).endsWith("." + extension) ? path
                : Path.of(path + "." + extension);
    }

    private boolean confirmOverwrite(Path path) {
        return !Files.exists(path) || JOptionPane.showConfirmDialog(this, "Substituir " + path.getFileName() + "?",
                "Salvar arquivo", JOptionPane.OK_CANCEL_OPTION) == JOptionPane.OK_OPTION;
    }

    /**
     * Abre um objeto H/T/M/E existente e solicita sua carga na máquina.
     */
    private void openObject() {
        JFileChooser chooser = chooser("obj");
        if (chooser.showOpenDialog(this) != JFileChooser.APPROVE_OPTION) {
            return;
        }
        try {
            String text = Files.readString(chooser.getSelectedFile().toPath(), StandardCharsets.UTF_8);
            ObjectProgram program = ObjectProgram.parse(text);
            Integer address = loadAddress(program.getStartAddress());
            if (address != null && controller.loadProgram(program, address)) {
                showMachine.run();
            }
        } catch (IOException | IllegalArgumentException e) {
            showError(e.getMessage());
        }
    }

    /**
     * Apresenta uma mensagem de falha na barra de estado e em uma janela.
     */
    private void showError(String message) {
        status.setText(message);
        JOptionPane.showMessageDialog(this, message, "Montador", JOptionPane.ERROR_MESSAGE);
    }
}
