package view;

import controller.MachineController;
import controller.MachineStateListener;
import model.decoder.DecodedInstruction;
import model.hardware.Memory;
import model.machine.SICXEMachine;
import model.types.AddressingMode;
import model.types.Register;

import javax.swing.*;
import javax.swing.table.AbstractTableModel;
import javax.swing.table.DefaultTableCellRenderer;
import java.awt.*;

/**
 * Interface Gráfica (View) do Simulador SIC/XE.
 * Construída em Java Swing com foco acadêmico. Implementa o padrão Observer
 * (MachineStateListener) para reagir assincronamente às mudanças da CPU
 * comandadas pelo Controller.
 */
public class SICXEView extends JFrame implements MachineStateListener {

    private final SICXEMachine machine;
    private final MachineController controller;

    private JTable memoryTable;
    private JTable registerTable;
    private MemoryTableModel memoryModel;
    private RegisterTableModel registerModel;
    private JSlider speedSlider;
    private JTextArea instructionDetailsArea; // Painel de Detalhes da Instrução
    private final JPanel machinePanel = new JPanel(new BorderLayout(10, 10));

    /**
     * Constrói e inicializa os componentes visuais do simulador.
     * 
     * @param machine A instância do Model (para leitura de dados).
     * @param controller A instância do Controller (para envio de comandos).
     */
    public SICXEView(SICXEMachine machine, MachineController controller) {
        this.machine = machine;
        this.controller = controller;

        // Assina esta View para receber eventos do Controller
        this.controller.setMachineStateListener(this);

        setTitle("SIC/XE Simulator - UFPel");
        setSize(1050, 700); 
        setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        setLocationRelativeTo(null);
        setLayout(new BorderLayout(10, 10));

        initComponents();
        JTabbedPane tabs = new JTabbedPane();
        tabs.addTab("Montador", new AssemblerPanel(controller, () -> tabs.setSelectedIndex(1)));
        tabs.addTab("Máquina virtual", machinePanel);
        add(tabs, BorderLayout.CENTER);
    }

    /**
     * Inicializa e posiciona os painéis (Memória, Registradores e Controles).
     */
    private void initComponents() {
        // --- 1. PAINEL ESQUERDO (Registradores + Instruction Details) ---
        JPanel leftPanel = new JPanel(new BorderLayout(0, 10));
        leftPanel.setPreferredSize(new Dimension(380, 0));

        // 1A. Tabela de Registradores
        registerModel = new RegisterTableModel(machine);
        registerTable = new JTable(registerModel);
        registerTable.setRowHeight(30);
        registerTable.setFont(new Font("Monospaced", Font.BOLD, 14));
        
        JScrollPane registerScroll = new JScrollPane(registerTable);
        // Ajuste do "meio termo": 310 pixels acomodam as 8 linhas de 30px + o cabeçalho + as bordas,
        // eliminando a barra de rolagem e mantendo um bom espaço para o painel de detalhes.
        registerScroll.setPreferredSize(new Dimension(380, 310)); 
        registerScroll.setBorder(BorderFactory.createTitledBorder("Registers"));
        leftPanel.add(registerScroll, BorderLayout.NORTH);

        // 1B. Detalhes da Instrução
        instructionDetailsArea = new JTextArea(8, 30);
        instructionDetailsArea.setFont(new Font("Monospaced", Font.BOLD, 12));
        instructionDetailsArea.setEditable(false);
        instructionDetailsArea.setBackground(new Color(30, 30, 30));
        instructionDetailsArea.setForeground(new Color(200, 255, 200)); // Verde terminal
        
        JScrollPane detailsScroll = new JScrollPane(instructionDetailsArea);
        detailsScroll.setBorder(BorderFactory.createTitledBorder("Instruction Details (Last Executed)"));
        leftPanel.add(detailsScroll, BorderLayout.CENTER); // Preenche automaticamente o restante do espaço

        machinePanel.add(leftPanel, BorderLayout.WEST);

        // --- 2. PAINEL CENTRAL (Memória com Legenda) ---
        JPanel centerPanel = new JPanel(new BorderLayout());
        
        // Legenda de Cores
        JPanel legendPanel = new JPanel(new FlowLayout(FlowLayout.LEFT));
        JLabel lblNext = new JLabel("■ Next Instruction (PC)   ");
        lblNext.setForeground(new Color(0, 100, 200));
        JLabel lblLast = new JLabel("■ Last Executed");
        lblLast.setForeground(new Color(0, 150, 0));
        legendPanel.add(lblNext);
        legendPanel.add(lblLast);
        centerPanel.add(legendPanel, BorderLayout.NORTH);

        // Tabela de Memória
        memoryModel = new MemoryTableModel(machine.getMemory());
        memoryTable = new JTable(memoryModel);
        memoryTable.setFont(new Font("Monospaced", Font.PLAIN, 14));
        memoryTable.setDefaultRenderer(Object.class, new MemoryCellRenderer()); // Injeta Cores Dinâmicas
        
        // Ajuste fino das colunas para melhor distribuição visual
        memoryTable.getColumnModel().getColumn(0).setPreferredWidth(100); // Address (Dec)
        memoryTable.getColumnModel().getColumn(1).setPreferredWidth(100); // Address (Hex)
        memoryTable.getColumnModel().getColumn(2).setPreferredWidth(80);  // Byte (Hex)
        memoryTable.getColumnModel().getColumn(3).setPreferredWidth(80);  // Byte (Dec)
        memoryTable.getColumnModel().getColumn(4).setPreferredWidth(100); // Byte (Bin)

        JScrollPane memoryScroll = new JScrollPane(memoryTable);
        memoryScroll.setBorder(BorderFactory.createTitledBorder("Main Memory (1MB)"));
        centerPanel.add(memoryScroll, BorderLayout.CENTER);
        
        machinePanel.add(centerPanel, BorderLayout.CENTER);

        // --- 3. PAINEL DE CONTROLES (Inferior) ---
        JPanel controlPanel = new JPanel(new FlowLayout(FlowLayout.CENTER, 15, 10));
        
        JButton btnLoadHex = new JButton("Load Hex (Test)");
        JButton btnStep = new JButton("Step");
        JButton btnRun = new JButton("Run");
        JButton btnPause = new JButton("Pause");
        JButton btnReset = new JButton("Reset");

        // Slider para controlar a velocidade da simulação (Escala de 0 a 100)
        speedSlider = new JSlider(0, 100, 50);
        speedSlider.setToolTipText("Execution Speed");
        
        // Listener que converte o valor visual (0 a 100) para Delay em milissegundos
        speedSlider.addChangeListener(e -> {
            int speedValue = speedSlider.getValue();
            int delayMs = 20 + (100 - speedValue) * 10;
            controller.setDelayMs(delayMs);
        });
        
        // Sincroniza o Controller com o valor inicial do Slider no momento da criação
        controller.setDelayMs(20 + (100 - speedSlider.getValue()) * 10);

        // Vinculando os botões aos métodos do Controller
        btnLoadHex.addActionListener(e -> openPseudoLoaderDialog());
        btnStep.addActionListener(e -> controller.step());
        btnRun.addActionListener(e -> controller.run());
        btnPause.addActionListener(e -> controller.pause());
        btnReset.addActionListener(e -> controller.reset());

        controlPanel.add(btnLoadHex);
        controlPanel.add(btnStep);
        controlPanel.add(btnRun);
        controlPanel.add(btnPause);
        controlPanel.add(btnReset);
        controlPanel.add(new JLabel("Speed:"));
        controlPanel.add(speedSlider);

        machinePanel.add(controlPanel, BorderLayout.SOUTH);
        
        updateInstructionDetails(); // Renderiza texto vazio inicialmente
    }

    /**
     * Abre uma janela de diálogo para o usuário colar o código de máquina
     * em formato hexadecimal. O input é repassado ao Controller.
     */
    private void openPseudoLoaderDialog() {
        JTextArea textArea = new JTextArea(10, 30);
        textArea.setFont(new Font("Monospaced", Font.PLAIN, 14));
        textArea.setLineWrap(true);
        JScrollPane scrollPane = new JScrollPane(textArea);

        int result = JOptionPane.showConfirmDialog(
                this, scrollPane, "Paste Object Code (Hexadecimal)",
                JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE
        );

        if (result == JOptionPane.OK_OPTION) {
            String hexCode = textArea.getText();
            controller.loadHexCode(hexCode);
            // Faz a tabela de memória voltar para o endereço 0x0000
            memoryTable.scrollRectToVisible(memoryTable.getCellRect(0, 0, true));
        }
    }

    // ==========================================
    // RENDERIZADOR DE CORES DA MEMÓRIA
    // ==========================================
    private class MemoryCellRenderer extends DefaultTableCellRenderer {
        @Override
        public Component getTableCellRendererComponent(JTable table, Object value, boolean isSelected, boolean hasFocus, int row, int column) {
            Component c = super.getTableCellRendererComponent(table, value, isSelected, hasFocus, row, column);
            
            int currentPC = machine.getRegisters().getPC();
            DecodedInstruction lastInst = machine.getLastExecutedInstruction();
            
            if (row == currentPC) {
                c.setBackground(new Color(173, 216, 230)); // Azul Claro (PC)
                c.setForeground(Color.BLACK);
            } else if (lastInst != null && row >= lastInst.getFetchAddress() && row < lastInst.getFetchAddress() + lastInst.getFormat()) {
                c.setBackground(new Color(144, 238, 144)); // Verde Claro (Última Instrução)
                c.setForeground(Color.BLACK);
            } else {
                c.setBackground(table.getBackground());
                c.setForeground(table.getForeground());
            }
            
            if (isSelected) {
                c.setBackground(table.getSelectionBackground());
                c.setForeground(table.getSelectionForeground());
            }
            return c;
        }
    }

    // ==========================================
    // FORMATADOR DE ASCII ART (INSTRUCTION DETAILS)
    // ==========================================
    private void updateInstructionDetails() {
        DecodedInstruction inst = machine.getLastExecutedInstruction();
        if (inst == null) {
            instructionDetailsArea.setText("> No instruction executed yet.");
            return;
        }
        
        StringBuilder sb = new StringBuilder();
        sb.append(String.format("> Last Executed: %s (Format %d)\n\n", inst.getOpcode().name(), inst.getFormat()));
        
        int fetchAddr = inst.getFetchAddress();
        
        if (inst.getFormat() == 2) {
            // Detalhes Formato 2
            sb.append("[OPCODE  ] [r1        ] [r2        ]\n");
            
            // Lê o byte cru da memória para exibir o opcode bit a bit exato
            int rawOpcode = machine.getMemory().readByte(fetchAddr);
            String opBin = String.format("%8s", Integer.toBinaryString(rawOpcode)).replace(' ', '0');
            
            int r1Num = getRegisterNumber(inst.getR1());
            int r2Num = inst.getShiftCount() > 0 ? inst.getShiftCount() - 1 : getRegisterNumber(inst.getR2());
            String r1Bin = String.format("%4s", Integer.toBinaryString(r1Num)).replace(' ', '0');
            String r2Bin = String.format("%4s", Integer.toBinaryString(r2Num)).replace(' ', '0');
            
            String r1Str = String.format("%s (%s)", r1Bin, inst.getR1() != null ? inst.getR1().name() : "-");
            String r2Str = String.format("%s (%s)", r2Bin, inst.getShiftCount() > 0 ? "n=" + inst.getShiftCount() : inst.getR2() != null ? inst.getR2().name() : "-");
            
            sb.append(String.format("[%8s] [%-10s] [%-10s]\n", opBin, r1Str, r2Str));
            
        } else {
            // Detalhes Formatos 3 e 4
            sb.append(String.format("[OPCODE] [n i x b p e] [%s]\n", inst.isExtended() ? "ADDRESS" : "DISPLACEMENT"));
            
            // Extrai o primeiro byte cru da memória para pegar o opcode de 6 bits e as flags n, i
            int byte1 = machine.getMemory().readByte(fetchAddr);
            int rawOpcode = (byte1 & 0xFC) >> 2; 
            String opBin = String.format("%6s", Integer.toBinaryString(rawOpcode)).replace(' ', '0');
            
            int nBit = (byte1 >> 1) & 1;
            int iBit = byte1 & 1;
            String flags = String.format("%d %d %d %d %d %d", nBit, iBit, (inst.isIndexed() ? 1 : 0), (inst.isBaseRelative() ? 1 : 0), (inst.isPcRelative() ? 1 : 0), (inst.isExtended() ? 1 : 0));
            
            int val = inst.getDisplacementOrAddress();
            String valBin;
            if (inst.isExtended()) {
                valBin = String.format("%20s", Integer.toBinaryString(val & 0xFFFFF)).replace(' ', '0');
                valBin = valBin.substring(0,4) + " " + valBin.substring(4,8) + " " + valBin.substring(8,12) + " " + valBin.substring(12,16) + " " + valBin.substring(16,20);
            } else {
                valBin = String.format("%12s", Integer.toBinaryString(val & 0xFFF)).replace(' ', '0');
                valBin = valBin.substring(0,4) + " " + valBin.substring(4,8) + " " + valBin.substring(8,12);
            }
            
            sb.append(String.format("[%6s] [%11s] [%s]\n", opBin, flags, valBin));
            
            if (inst.getCalculatedTargetAddress() != null) {
                int ta = inst.getCalculatedTargetAddress();
                sb.append(String.format("\n> Target Address (TA): 0x%05X (%d)\n", ta, ta));
            }
        }
        
        instructionDetailsArea.setText(sb.toString());
    }
    
    /**
     * Mapeador seguro para converter Registradores em IDs Numéricos (Tabela SIC/XE)
     * independente de como o enum interno foi implementado pelo aluno.
     */
    private int getRegisterNumber(Register r) {
        if (r == null) return 0;
        return switch(r) {
            case A -> 0; case X -> 1; case L -> 2; case B -> 3;
            case S -> 4; case T -> 5; case PC -> 8; case SW -> 9;
            default -> 0;
        };
    }

    // ==========================================
    // MÉTODOS DO MACHINE_STATE_LISTENER
    // ==========================================

    @Override
    public void onMachineStateChanged() {
        // As Threads em background não podem alterar componentes visuais no Swing.
        // O invokeLater injeta a atualização na Thread correta de Eventos da Interface Gráfica.
        SwingUtilities.invokeLater(() -> {
            registerModel.fireTableDataChanged();
            memoryModel.fireTableDataChanged();
            updateInstructionDetails(); // Atualiza o texto formatado
            
            // Foco automático na tabela de memória seguindo o Program Counter (PC)
            int currentPC = machine.getRegisters().getPC();
            if (currentPC >= 0 && currentPC < Memory.MAX_MEMORY_SIZE) {
                memoryTable.setRowSelectionInterval(currentPC, currentPC);
                memoryTable.scrollRectToVisible(memoryTable.getCellRect(currentPC, 0, true));
            }
        });
    }

    @Override
    public void onMachineError(String errorMessage) {
        SwingUtilities.invokeLater(() -> {
            JOptionPane.showMessageDialog(this, errorMessage, "Execution Error", JOptionPane.ERROR_MESSAGE);
        });
    }

    // ==========================================
    // MODELOS DE DADOS OTIMIZADOS
    // ==========================================

    /**
     * Modelo de tabela para ler a memória sem alocar componentes visuais
     * para 1 Milhão de endereços, evitando estouro de memória (OutOfMemory).
     */
    private static class MemoryTableModel extends AbstractTableModel {
        private final Memory memory;
        // Nova coluna 'Address (Dec)' adicionada na posição 0
        private final String[] columns = {"Address (Dec)", "Address (Hex)", "Byte (Hex)", "Byte (Dec)", "Byte (Bin)"};

        public MemoryTableModel(Memory memory) {
            this.memory = memory;
        }

        @Override
        public int getRowCount() { return Memory.MAX_MEMORY_SIZE; }

        @Override
        public int getColumnCount() { return columns.length; }

        @Override
        public String getColumnName(int column) { return columns[column]; }

        @Override
        public Object getValueAt(int rowIndex, int columnIndex) {
            int byteValue = memory.readByte(rowIndex);
            return switch (columnIndex) {
                case 0 -> String.valueOf(rowIndex);
                case 1 -> String.format("0x%05X", rowIndex);
                case 2 -> String.format("%02X", byteValue);
                case 3 -> String.valueOf(byteValue);
                case 4 -> String.format("%8s", Integer.toBinaryString(byteValue)).replace(' ', '0');
                default -> "";
            };
        }
    }

    /**
     * Modelo de tabela para listar os registradores atuais da CPU.
     */
    private static class RegisterTableModel extends AbstractTableModel {
        private final SICXEMachine machine;
        private final Register[] registers = {
            Register.A, Register.X, Register.L, Register.B, Register.S, Register.T, Register.PC
        };
        private final String[] columns = {"Register", "Value (Hex)", "Value (Dec)"};

        public RegisterTableModel(SICXEMachine machine) {
            this.machine = machine;
        }

        @Override
        public int getRowCount() { return registers.length + 1; } // +1 para a Status Word (Condições)

        @Override
        public int getColumnCount() { return columns.length; }

        @Override
        public String getColumnName(int column) { return columns[column]; }

        @Override
        public Object getValueAt(int rowIndex, int columnIndex) {
            if (rowIndex == registers.length) {
                return switch (columnIndex) {
                    case 0 -> "SW (CC)";
                    case 1, 2 -> machine.getRegisters().getConditionCode().name();
                    default -> "";
                };
            }

            Register reg = registers[rowIndex];
            int valueUnsigned = machine.getRegisters().get(reg).toIntUnsigned();
            int valueSigned = machine.getRegisters().get(reg).toIntSigned();

            return switch (columnIndex) {
                case 0 -> reg.name();
                case 1 -> String.format("%06X", valueUnsigned);
                case 2 -> String.valueOf(valueSigned);
                default -> "";
            };
        }
    }
}
