package model.execution;

import model.decoder.DecodedInstruction;
import model.hardware.Memory;
import model.hardware.RegisterBank;
import model.types.AddressingMode;
import model.types.ConditionCode;
import model.types.Opcode;
import model.types.Register;
import model.types.Word24;

/**
 * A Unidade Lógica, Aritmética e de Execução da CPU SIC/XE.
 * Responsável por aplicar as alterações de estado nos registradores e na memória
 * de acordo com a semântica de cada Opcode.
 * 
 * Esta classe é desenhada para ser livre de manipulações de bits e cálculos
 * de ponteiros, delegando essas tarefas para a Word24 e para o TargetAddressCalculator.
 */
public class Executor {

    private final Memory memory;
    private final RegisterBank registers;

    /**
     * Instancia o Executor com as dependências de hardware.
     * 
     * @param memory A memória principal do simulador.
     * @param registers O banco de registradores da CPU.
     */
    public Executor(Memory memory, RegisterBank registers) {
        this.memory = memory;
        this.registers = registers;
    }

    /**
     * Executa a instrução decodificada.
     * 
     * @param inst A instrução previamente decodificada.
     * @param targetAddress O endereço efetivo (TA) calculado, se aplicável (Formatos 3 e 4).
     */
    public void execute(DecodedInstruction inst, int targetAddress) {
        Opcode op = inst.getOpcode();

        switch (op) {
            // ==========================================
            // 1. INSTRUÇÕES DE LOAD (Carregamento)
            // ==========================================
            case LDA: executeLoad(Register.A, inst, targetAddress); break;
            case LDX: executeLoad(Register.X, inst, targetAddress); break;
            case LDL: executeLoad(Register.L, inst, targetAddress); break;
            case LDB: executeLoad(Register.B, inst, targetAddress); break;
            case LDS: executeLoad(Register.S, inst, targetAddress); break;
            case LDT: executeLoad(Register.T, inst, targetAddress); break;
            case LDCH:
                // LDCH afeta apenas o byte mais à direita do Acumulador.
                int charToLoad = inst.getAddressingMode() == AddressingMode.IMMEDIATE
                        ? targetAddress & 0xFF : memory.readByte(targetAddress);
                Word24 currentA = registers.get(Register.A);
                registers.set(Register.A, currentA.withLowByte(charToLoad));
                break;
            
            // ==========================================
            // 2. INSTRUÇÕES DE STORE (Armazenamento)
            // ==========================================
            case STA: executeStore(Register.A, targetAddress); break;
            case STX: executeStore(Register.X, targetAddress); break;
            case STL: executeStore(Register.L, targetAddress); break;
            case STB: executeStore(Register.B, targetAddress); break;
            case STS: executeStore(Register.S, targetAddress); break;
            case STT: executeStore(Register.T, targetAddress); break;
            case STCH:
                // STCH grava apenas o byte mais à direita do Acumulador na memória[cite: 3].
                int charToStore = registers.get(Register.A).getLowByte();
                memory.writeByte(targetAddress, charToStore);
                break;

            // ==========================================
            // 3. ARITMÉTICA E LÓGICA COM MEMÓRIA (Formato 3 e 4)
            // ==========================================
            case ADD: 
            case SUB: 
            case MUL: 
            case DIV:
                executeMemoryArithmetic(op, inst, targetAddress); 
                break;
            case AND:
            case OR:
                executeBitwiseLogic(op, inst, targetAddress);
                break;

            // ==========================================
            // 4. INSTRUÇÕES DE FORMATO 2 E REGISTRADORES
            // ==========================================
            case ADDR:
            case SUBR:
            case MULR:
            case DIVR:
                executeRegisterArithmetic(op, inst.getR1(), inst.getR2());
                break;
            case CLEAR:
                registers.set(inst.getR1(), new Word24(0));
                break;
            case RMO:
                registers.set(inst.getR2(), registers.get(inst.getR1()));
                break;
            case SHIFTL:
            case SHIFTR:
                executeShift(op, inst.getR1(), inst.getShiftCount());
                break;

            // ==========================================
            // 5. FLUXO DE CONTROLE E COMPARAÇÃO
            // ==========================================
            case COMP:
                executeCompare(inst, targetAddress);
                break;
            case COMPR:
                executeRegisterCompare(inst.getR1(), inst.getR2());
                break;
            case TIX:
                executeTix(inst, targetAddress);
                break;
            case TIXR:
                executeTixr(inst.getR1());
                break;
            case J:
                registers.setPC(targetAddress);
                break;
            case JEQ:
                if (registers.getConditionCode() == ConditionCode.EQUAL) registers.setPC(targetAddress);
                break;
            case JGT:
                if (registers.getConditionCode() == ConditionCode.GREATER_THAN) registers.setPC(targetAddress);
                break;
            case JLT:
                if (registers.getConditionCode() == ConditionCode.LESS_THAN) registers.setPC(targetAddress);
                break;
            case JSUB:
                registers.set(Register.L, registers.get(Register.PC)); // Salva o endereço de retorno no L
                registers.setPC(targetAddress); // Salta para a subrotina
                break;
            case RSUB:
                registers.set(Register.PC, registers.get(Register.L)); // Restaura o PC a partir do L
                break;

           default:
                throw new IllegalArgumentException("Opcode não suportado para execução: " + op);
        }
    }

    /**
     * Resolve a busca do operando analisando o Modo de Endereçamento.
     * Se for Imediato (#c), o próprio targetAddress é o valor. Caso contrário, lê a memória.
     */
    private Word24 fetchOperand(DecodedInstruction inst, int targetAddress) {
        if (inst.getAddressingMode() == AddressingMode.IMMEDIATE) {
            return new Word24(targetAddress);
        }
        return memory.readWord(targetAddress);
    }

    /**
     * Executa qualquer instrução de carregamento (LDA, LDX, etc).
     */
    private void executeLoad(Register reg, DecodedInstruction inst, int targetAddress) {
        Word24 operand = fetchOperand(inst, targetAddress);
        registers.set(reg, operand);
    }

    /**
     * Executa qualquer instrução de armazenamento (STA, STX, etc).
     * O modo Imediato não faz sentido lógico para Store na arquitetura SIC/XE.
     */
    private void executeStore(Register reg, int targetAddress) {
        Word24 valueToStore = registers.get(reg);
        memory.writeWord(targetAddress, valueToStore);
    }

    /**
     * Executa operações aritméticas onde o operando 1 é o Acumulador (A) 
     * e o operando 2 vem da Memória (ou é imediato).
     */
    private void executeMemoryArithmetic(Opcode op, DecodedInstruction inst, int targetAddress) {
        Word24 valA = registers.get(Register.A);
        Word24 operand = fetchOperand(inst, targetAddress);
        Word24 result = computeArithmetic(op, valA, operand);
        registers.set(Register.A, result);
    }

    /**
     * Executa operações aritméticas exclusivamente entre dois registradores (Formato 2).
     * O resultado é sempre armazenado no registrador 2 (r2).
     */
    private void executeRegisterArithmetic(Opcode op, Register r1, Register r2) {
        Word24 val1 = registers.get(r1);
        Word24 val2 = registers.get(r2);
        Word24 result = computeArithmetic(op, val2, val1); // A instrução é r2 <- r2 (op) r1
        registers.set(r2, result);
    }

    /**
     * Aplica operações lógicas AND / OR entre o Acumulador e a memória.
     */
    private void executeBitwiseLogic(Opcode op, DecodedInstruction inst, int targetAddress) {
        Word24 valA = registers.get(Register.A);
        Word24 operand = fetchOperand(inst, targetAddress);
        
        Word24 result = (op == Opcode.AND) ? valA.bitwiseAnd(operand) : valA.bitwiseOr(operand);
        registers.set(Register.A, result);
    }

    /**
     * Executa deslocamentos de bits. 
     * Pela especificação, o número de bits (n) é guardado em formato n-1 no lugar do registrador r2.
     */
    private void executeShift(Opcode op, Register r1, int n) {
        Word24 val1 = registers.get(r1);
        
        Word24 result = (op == Opcode.SHIFTL) ? val1.shiftLeftCircular(n) : val1.shiftRightArithmetic(n);
        registers.set(r1, result);
    }

    /**
     * Compara o valor do Acumulador (A) com um valor da memória (ou imediato) 
     * e atualiza o Código de Condição (CC).
     */
    private void executeCompare(DecodedInstruction inst, int targetAddress) {
        Word24 valA = registers.get(Register.A);
        Word24 operand = fetchOperand(inst, targetAddress);
        updateConditionCode(valA, operand);
    }

    /**
     * Compara o valor de dois registradores e atualiza o Código de Condição (CC).
     */
    private void executeRegisterCompare(Register r1, Register r2) {
        Word24 val1 = registers.get(r1);
        Word24 val2 = registers.get(r2);
        updateConditionCode(val1, val2);
    }

    /**
     * Executa a instrução TIX: Incrementa o Registrador de Índice (X) em 1 e 
     * o compara contra um valor em memória, atualizando o Código de Condição.
     */
    private void executeTix(DecodedInstruction inst, int targetAddress) {
        Word24 x = registers.get(Register.X).add(new Word24(1));
        registers.set(Register.X, x);
        
        Word24 operand = fetchOperand(inst, targetAddress);
        updateConditionCode(x, operand);
    }

    /**
     * Executa a instrução TIXR: Incrementa o Registrador de Índice (X) em 1 e 
     * o compara contra o registrador r1, atualizando o Código de Condição.
     */
    private void executeTixr(Register r1) {
        Word24 x = registers.get(Register.X).add(new Word24(1));
        registers.set(Register.X, x);
        
        Word24 operand = registers.get(r1);
        updateConditionCode(x, operand);
    }

    /**
     * Lógica central de comparação com sinal (complemento de 2).
     * Define o estado (LESS_THAN, EQUAL, GREATER_THAN) para uso futuro por saltos condicionais[cite: 3, 4].
     */
    private void updateConditionCode(Word24 op1, Word24 op2) {
        int v1 = op1.toIntSigned();
        int v2 = op2.toIntSigned();

        if (v1 < v2) {
            registers.setConditionCode(ConditionCode.LESS_THAN);
        } else if (v1 > v2) {
            registers.setConditionCode(ConditionCode.GREATER_THAN);
        } else {
            registers.setConditionCode(ConditionCode.EQUAL);
        }
    }

    /**
     * Centraliza a lógica matemática de 24 bits.
     * Utiliza a conversão para inteiro com sinal (complemento de 2) da Word24 para garantir 
     * que a JVM do Java calcule valores negativos corretamente.
     */
    private Word24 computeArithmetic(Opcode op, Word24 dest, Word24 src) {
        int vDest = dest.toIntSigned();
        int vSrc = src.toIntSigned();
        
        return switch (op) {
            case ADD, ADDR -> new Word24(vDest + vSrc);
            case SUB, SUBR -> new Word24(vDest - vSrc);
            case MUL, MULR -> new Word24(vDest * vSrc);
            case DIV, DIVR -> {
                if (vSrc == 0) throw new ArithmeticException("Divisão por zero detectada na CPU.");
                yield new Word24(vDest / vSrc);
            }
            default -> throw new IllegalArgumentException("Operação aritmética inválida: " + op);
        };
    }
}
