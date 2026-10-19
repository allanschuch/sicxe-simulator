package controller;

import assembler.AssemblyResult;
import assembler.ObjectProgram;
import assembler.TwoPassAssembler;
import loader.ObjectLoader;
import model.machine.SICXEMachine;

/**
 * Controlador da Máquina Virtual SIC/XE e da integração com o montador.
 * Orquestra os comandos do usuário e repassa o trabalho aos modelos.
 * Gerencia a execução em uma Thread separada para manter a View responsiva.
 */
public class MachineController {
    private final SICXEMachine machine;
    // Serializa ciclos da CPU, carga e reset para não modificar a máquina durante uma instrução.
    private final Object stateLock = new Object();
    private volatile MachineStateListener listener;
    private boolean running;
    private Thread executionThread;
    // Intervalo em milissegundos compartilhado com a Thread de execução.
    private volatile int delayMs = 100;
    private int entryPoint;

    /**
     * Constrói o controlador injetando o núcleo da máquina virtual.
     * 
     * @param machine Máquina SIC/XE que será controlada.
     */
    public MachineController(SICXEMachine machine) {
        this.machine = machine;
    }

    /**
     * Define o ouvinte que receberá atualizações de estado e mensagens de erro.
     */
    public void setMachineStateListener(MachineStateListener listener) { this.listener = listener; }

    /**
     * Configura o intervalo entre os ciclos no modo de execução contínua.
     */
    public void setDelayMs(int delayMs) {
        if (delayMs < 1) {
            throw new IllegalArgumentException("Intervalo deve ser positivo.");
        }
        this.delayMs = delayMs;
    }

    /**
     * Consulta a entrada do último programa carregado, usada pelo comando Reset.
     */
    public int getEntryPoint() {
        synchronized (stateLock) {
            return entryPoint;
        }
    }

    /**
     * Encaminha o fonte ao montador sem modificar o estado da máquina virtual.
     */
    public AssemblyResult assemble(String source) {
        return new TwoPassAssembler().assemble(source);
    }

    /**
     * Interrompe a execução contínua e executa exatamente uma instrução.
     */
    public void step() {
        synchronized (stateLock) {
            stopExecution();
            executeStep();
        }
    }

    /**
     * Executa um ciclo da CPU e notifica a View sobre o resultado ou erro.
     */
    private void executeStep() {
        try {
            machine.step();
            notifyStateChanged();
        } catch (Exception e) {
            stopExecution();
            notifyError(
                    String.format("Erro de execução (PC: %06X)%n%s", machine.getRegisters().getPC(), e.getMessage()));
        }
    }

    /**
     * Inicia a execução contínua em uma Thread separada, até pausa ou erro.
     */
    public void run() {
        synchronized (stateLock) {
            if (running) {
                return;
            }
            running = true;
            Thread thread = new Thread(() -> {
                try {
                    while (true) {
                        synchronized (stateLock) {
                            if (!running || Thread.currentThread() != executionThread) {
                                break;
                            }
                            executeStep();
                        }
                        Thread.sleep(delayMs);
                    }
                } catch (InterruptedException ignored) {
                    Thread.currentThread().interrupt();
                } finally {
                    synchronized (stateLock) {
                        if (Thread.currentThread() == executionThread) {
                            running = false;
                            executionThread = null;
                        }
                    }
                }
            }, "sicxe-execution");
            executionThread = thread;
            thread.setDaemon(true);
            thread.start();
        }
    }

    /**
     * Interrompe a execução contínua, preservando o estado atual da máquina.
     */
    public void pause() {
        synchronized (stateLock) {
            stopExecution();
        }
    }

    /**
     * Sinaliza a parada da Thread; deve ser chamado dentro do bloqueio de estado.
     */
    private void stopExecution() {
        running = false;
        if (executionThread != null) {
            executionThread.interrupt();
        }
    }

    /**
     * Zera registradores, CC e última instrução, preservando a memória.
     * Restaura o PC para a entrada do programa carregado.
     */
    public void reset() {
        synchronized (stateLock) {
            stopExecution();
            machine.resetRegisters(entryPoint);
            notifyStateChanged();
        }
    }

    /**
     * Carrega uma montagem bem-sucedida no endereço de origem do programa.
     */
    public boolean loadAssembly(AssemblyResult result) {
        if (!result.isSuccess()) {
            notifyError("Corrija os erros de montagem antes de carregar.");
            return false;
        }
        return loadProgram(result.getProgram(), result.getProgram().getStartAddress());
    }

    /**
     * Lê um objeto textual e o encaminha ao carregador, notificando erros à View.
     */
    public boolean loadObjectCode(String source, Integer address) {
        try {
            ObjectProgram program = ObjectProgram.parse(source);
            return loadProgram(program, address == null ? program.getStartAddress() : address);
        } catch (IllegalArgumentException e) {
            notifyError("Falha ao carregar objeto: " + e.getMessage());
            return false;
        }
    }

    /**
     * Para a CPU, carrega o objeto e guarda seu ponto de entrada para futuros resets.
     */
    public boolean loadProgram(ObjectProgram program, int address) {
        synchronized (stateLock) {
            stopExecution();
            try {
                entryPoint = new ObjectLoader().load(program, machine, address);
                notifyStateChanged();
                return true;
            } catch (IllegalArgumentException e) {
                notifyError("Falha ao carregar objeto: " + e.getMessage());
                return false;
            }
        }
    }

    /**
     * Entrada de bytes hexadecimais utilizada nos testes da primeira entrega.
     * Valida os pares antes da carga em zero; objetos H/T/M/E usam o carregador próprio.
     */
    public void loadHexCode(String hexCode) {
        String clean = hexCode.replaceAll("\\s", "");
        if (clean.isEmpty() || !clean.matches("[0-9a-fA-F]+") || clean.length() % 2 != 0
                || clean.length() / 2 > model.hardware.Memory.MAX_MEMORY_SIZE) {
            notifyError("Informe bytes hexadecimais completos separados por espaços. Para H/T/M/E, use Abrir objeto.");
            return;
        }
        byte[] bytes = java.util.HexFormat.of().parseHex(clean);
        synchronized (stateLock) {
            stopExecution();
            machine.reset();
            entryPoint = 0;
            for (int i = 0; i < bytes.length; i++) {
                machine.getMemory().writeByte(i, bytes[i]);
            }
            notifyStateChanged();
        }
    }

    /**
     * Dispara a notificação de atualização para o ouvinte, quando registrado.
     */
    private void notifyStateChanged() {
        MachineStateListener current = listener;
        if (current != null) {
            current.onMachineStateChanged();
        }
    }

    /**
     * Dispara a notificação de erro para o ouvinte, quando registrado.
     */
    private void notifyError(String message) {
        MachineStateListener current = listener;
        if (current != null) {
            current.onMachineError(message);
        }
    }
}
