package model.decoder;

import model.hardware.Memory;
import model.types.AddressingMode;
import model.types.Opcode;
import model.types.Register;

/**
 * Decodificador da arquitetura SIC/XE.
 * Responsável por ler os bytes da memória, extrair as flags lógicas
 * através de operações de máscara (bitwise) e instanciar um DTO limpo.
 */
public class InstructionDecoder {

    // --- MÁSCARAS DO PRIMEIRO BYTE ---
    /** Isola a flag 'n' (bit 1 do primeiro byte: 00000010) */
    private static final int FLAG_N_MASK = 0x02;
    /** Isola a flag 'i' (bit 0 do primeiro byte: 00000001) */
    private static final int FLAG_I_MASK = 0x01;

    // --- MÁSCARAS DO SEGUNDO BYTE ---
    /** Isola a flag 'x' (bit 7 do segundo byte: 10000000) */
    private static final int FLAG_X_MASK = 0x80;
    /** Isola a flag 'b' (bit 6 do segundo byte: 01000000) */
    private static final int FLAG_B_MASK = 0x40;
    /** Isola a flag 'p' (bit 5 do segundo byte: 00100000) */
    private static final int FLAG_P_MASK = 0x20;
    /** Isola a flag 'e' (bit 4 do segundo byte: 00010000) */
    private static final int FLAG_E_MASK = 0x10;
    
    /** Máscara para extrair apenas os 4 bits menos significativos do segundo byte (usado no Formato 3 e 4) */
    private static final int DISPLACEMENT_HIGH_NIBBLE_MASK = 0x0F;

    /**
     * Decodifica a instrução apontada pelo endereço atual.
     * 
     * @param memory A memória do sistema.
     * @param address O endereço de memória onde a instrução começa (geralmente PC).
     * @return Um objeto DecodedInstruction preenchido.
     */
    public DecodedInstruction decode(Memory memory, int address) {
        int byte1 = memory.readByte(address);
        Opcode opcode = Opcode.fromMachineByte(byte1);

        if (opcode.getFormat() == 2) {
            return decodeFormat2(memory, address, opcode);
        } else {
            return decodeFormat3Or4(memory, address, byte1, opcode);
        }
    }

    /**
     * Processa instruções de Formato 2.
     * Estrutura: [ opcode: 8 bits ] [ r1: 4 bits ] [ r2: 4 bits ].
     */
    private DecodedInstruction decodeFormat2(Memory memory, int address, Opcode opcode) {
        int byte2 = memory.readByte(address + 1);
        
        int r1Code = (byte2 >> 4) & DISPLACEMENT_HIGH_NIBBLE_MASK;
        int r2Code = byte2 & DISPLACEMENT_HIGH_NIBBLE_MASK;
        if (opcode == Opcode.SHIFTL || opcode == Opcode.SHIFTR) {
            return DecodedInstruction.shift(opcode, Register.fromCode(r1Code), r2Code + 1);
        }
        
        return new DecodedInstruction(
            opcode, 
            Register.fromCode(r1Code), 
            Register.fromCode(r2Code)
        );
    }

    /**
     * Processa instruções de Formato 3 ou 4.
     * O SIC/XE possui a seguinte estrutura para estes formatos:
     * Formato 3: [ op: 6 | n, i ] [ x, b, p, e | disp_high: 4 ] [ disp_low: 8 ] (Total 3 bytes, deslocamento de 12 bits)
     * Formato 4: [ op: 6 | n, i ] [ x, b, p, e | addr_high: 4 ] [ addr_mid: 8 ] [ addr_low: 8 ] (Total 4 bytes, endereço de 20 bits)
     */
    private DecodedInstruction decodeFormat3Or4(Memory memory, int address, int byte1, Opcode opcode) {
        int byte2 = memory.readByte(address + 1);

        // O primeiro byte contém as flags N e I nos seus dois bits menos significativos
        boolean flagN = (byte1 & FLAG_N_MASK) != 0;
        boolean flagI = (byte1 & FLAG_I_MASK) != 0;
        
        // O segundo byte inicia com as flags X, B, P e E nos seus 4 bits mais significativos
        boolean flagX = (byte2 & FLAG_X_MASK) != 0;
        boolean flagB = (byte2 & FLAG_B_MASK) != 0;
        boolean flagP = (byte2 & FLAG_P_MASK) != 0;
        boolean flagE = (byte2 & FLAG_E_MASK) != 0;

        AddressingMode mode = determineAddressingMode(flagN, flagI);
        
        int targetValue;
        int format = flagE ? 4 : 3;

        if (flagE) {
            // FORMATO 4: O operando é um endereço absoluto de 20 bits.
            int byte3 = memory.readByte(address + 2);
            int byte4 = memory.readByte(address + 3);
            
            // CONCATENAÇÃO DE 20 BITS:
            // 1. (byte2 & MASK) isola apenas os 4 bits inferiores do byte 2 (apagando as flags x,b,p,e).
            // 2. << 16 move esses 4 bits para a posição mais alta do endereço de 20 bits.
            // 3. (byte3 << 8) move o byte intermediário para a posição central.
            // 4. byte4 fica na base (bits menos significativos).
            // O operador OR (|) une (soma lógicamente) as três peças num único inteiro.
            targetValue = ((byte2 & DISPLACEMENT_HIGH_NIBBLE_MASK) << 16) | (byte3 << 8) | byte4;
        } else {
            // FORMATO 3: O operando é um deslocamento (displacement) relativo de 12 bits.
            int byte3 = memory.readByte(address + 2);
            
            // CONCATENAÇÃO DE 12 BITS:
            // 1. (byte2 & MASK) isola os 4 bits inferiores do byte 2.
            // 2. << 8 move esses 4 bits para formar a parte alta do deslocamento.
            // 3. byte3 forma os 8 bits inferiores.
            // O operador OR (|) une as peças formando o valor final de 12 bits.
            targetValue = ((byte2 & DISPLACEMENT_HIGH_NIBBLE_MASK) << 8) | byte3;
        }

        // Retorna o DTO preenchido com todas as flags extraídas
        return new DecodedInstruction(
            opcode, format, mode, 
            flagX, flagB, flagP, flagE, targetValue
        );
    }

    /**
     * Determina o modo de endereçamento baseado nas flags n e i.
     */
    private AddressingMode determineAddressingMode(boolean n, boolean i) {
        if (!n && i) return AddressingMode.IMMEDIATE;
        if (n && !i) return AddressingMode.INDIRECT;
        if (n && i) return AddressingMode.SIMPLE;
        return AddressingMode.SIC_STANDARD;
    }
}
