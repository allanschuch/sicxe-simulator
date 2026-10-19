package model.decoder;

import model.types.AddressingMode;
import model.types.Opcode;
import model.types.Register;
import model.types.Word24;

/**
 * Contêiner de Dados (DTO) que representa uma instrução completamente decodificada.
 * Abstrai os bits crus lidos da memória em propriedades fortemente tipadas,
 * prontas para serem avaliadas pela unidade de execução.
 */
public class DecodedInstruction {
    private final Opcode opcode;
    private final int format; // 2, 3 ou 4
    
    // Campos exclusivos do Formato 2
    private final Register r1;
    private final Register r2;
    private int shiftCount;
    
    // Campos exclusivos dos Formatos 3 e 4
    private final AddressingMode addressingMode;
    private final boolean flagX; // Indexado
    private final boolean flagB; // Base-relativo
    private final boolean flagP; // PC-relativo
    private final boolean flagE; // Estendido (Formato 4)
    
    // Valor extraído: pode ser o displacement de 12 bits (F3) ou address de 20 bits (F4)
    private final int displacementOrAddress;

    // Atributos usados na view para exibição de detalhes da instrução na interface visual
    private int fetchAddress;
    private Integer calculatedTargetAddress = null;

    /**
     * Construtor para instruções de Formato 2.
     * 
     * @param opcode A operação a ser executada.
     * @param r1 O primeiro registrador operando.
     * @param r2 O segundo registrador operando.
     */
    public DecodedInstruction(Opcode opcode, Register r1, Register r2) {
        this.opcode = opcode;
        this.format = 2;
        this.r1 = r1;
        this.r2 = r2;
        
        // Inicializa campos de Formato 3/4 com nulos/falsos
        this.addressingMode = null;
        this.flagX = false;
        this.flagB = false;
        this.flagP = false;
        this.flagE = false;
        this.displacementOrAddress = 0;
    }

    /**
     * Construtor para instruções dos Formatos 3 e 4.
     * 
     * @param opcode A operação a ser executada.
     * @param format O formato real detectado (3 ou 4).
     * @param mode O modo de endereçamento (SIMPLE, IMMEDIATE, etc.).
     * @param flagX Flag de indexação.
     * @param flagB Flag de base-relativo.
     * @param flagP Flag de PC-relativo.
     * @param flagE Flag de formato estendido (true = Formato 4).
     * @param displacementOrAddress O valor numérico de endereço ou deslocamento.
     */
    public DecodedInstruction(Opcode opcode, int format, AddressingMode mode, 
                              boolean flagX, boolean flagB, boolean flagP, boolean flagE, 
                              int displacementOrAddress) {
        this.opcode = opcode;
        this.format = format;
        this.addressingMode = mode;
        this.flagX = flagX;
        this.flagB = flagB;
        this.flagP = flagP;
        this.flagE = flagE;
        this.displacementOrAddress = displacementOrAddress;
        
        // Inicializa campos de Formato 2 com nulos
        this.r1 = null;
        this.r2 = null;
    }

    // Getters para encapsulamento
    public Opcode getOpcode() { return opcode; }
    public int getFormat() { return format; }
    public Register getR1() { return r1; }
    public Register getR2() { return r2; }
    public int getShiftCount() { return shiftCount; }

    /** O segundo nibble de SHIFTL/SHIFTR é uma contagem n-1, não um registrador. */
    public static DecodedInstruction shift(Opcode opcode, Register register, int count) {
        if ((opcode != Opcode.SHIFTL && opcode != Opcode.SHIFTR) || count < 1 || count > 16)
            throw new IllegalArgumentException("Instrução ou contagem de shift inválida.");
        DecodedInstruction instruction = new DecodedInstruction(opcode, register, null);
        instruction.shiftCount = count;
        return instruction;
    }
    public AddressingMode getAddressingMode() { return addressingMode; }
    public boolean isIndexed() { return flagX; }
    public boolean isBaseRelative() { return flagB; }
    public boolean isPcRelative() { return flagP; }
    public boolean isExtended() { return flagE; }
    public int getDisplacementOrAddress() { return displacementOrAddress; }
    
    /**
     * Converte o deslocamento lido em um Word24 com sinal estendido,
     * essencial para cálculos de endereço relativo ao PC em Formato 3.
     * 
     * @return O deslocamento envolto na classe Word24.
     */
    public Word24 getSignedDisplacement() {
        return Word24.fromSigned12Bit(displacementOrAddress);
    }

    // Métodos usados pela view para exibição de detalhes da instrução na interface visual
    public int getFetchAddress() { return fetchAddress; }
    public void setFetchAddress(int fetchAddress) { this.fetchAddress = fetchAddress; }

    public Integer getCalculatedTargetAddress() { return calculatedTargetAddress; }
    public void setCalculatedTargetAddress(Integer ta) { this.calculatedTargetAddress = ta; }
}
