package loader;

import assembler.ObjectProgram;
import model.hardware.Memory;
import model.machine.SICXEMachine;

/**
 * Carregador de programas objeto na máquina virtual SIC/XE.
 * Prepara uma imagem temporária e aplica a relocação antes de modificar
 * a memória e os registradores, preservando o estado anterior se houver erro.
 */
public final class ObjectLoader {

    /**
     * Carrega o programa no endereço de origem indicado pelo registro H.
     */
    public int load(ObjectProgram program, SICXEMachine machine) {
        return load(program, machine, program.getStartAddress());
    }

    /**
     * Carrega os segmentos e ajusta os campos indicados pelos registros M.
     * 
     * @param program Programa objeto previamente validado.
     * @param machine Máquina que receberá o código e os dados.
     * @param loadAddress Endereço inicial de carga, em bytes.
     * @return Ponto de entrada após a relocação.
     * @throws IllegalArgumentException Se a carga ou a relocação ultrapassar os limites.
     */
    public int load(ObjectProgram program, SICXEMachine machine, int loadAddress) {
        if (loadAddress < 0 || (long) loadAddress + program.getLength() > Memory.MAX_MEMORY_SIZE) {
            throw new IllegalArgumentException("Programa não cabe no endereço de carga solicitado.");
        }
        byte[] image = new byte[program.getLength()];
        for (ObjectProgram.TextRecord text : program.getTextRecords()) {
            byte[] bytes = text.getBytes();
            System.arraycopy(bytes, 0, image, text.getAddress() - program.getStartAddress(), bytes.length);
        }
        long delta = (long) loadAddress - program.getStartAddress();
        for (ObjectProgram.ModificationRecord modification : program.getModifications()) {
            int offset = modification.getOffset();
            // M com 5 meios-bytes começa no nibble inferior e preserva as flags x/b/p/e.
            int original = ((image[offset] & 255) << 16) | ((image[offset + 1] & 255) << 8) | (image[offset + 2] & 255);
            int mask = modification.getHalfBytes() == 5 ? 0xFFFFF : 0xFFFFFF;
            long adjusted = (original & mask) + delta;
            if (adjusted < 0 || adjusted > mask) {
                throw new IllegalArgumentException(
                        "Overflow de relocação na posição " + String.format("%06X", modification.getOffset()));
            }
            int result = (original & ~mask) | (int) adjusted;
            image[offset] = (byte) (result >> 16);
            image[offset + 1] = (byte) (result >> 8);
            image[offset + 2] = (byte) result;
        }
        int entry = program.getEntryPoint() + (int) delta;
        machine.reset();
        for (int i = 0; i < image.length; i++) {
            machine.getMemory().writeByte(loadAddress + i, image[i]);
        }
        machine.getRegisters().setPC(entry);
        return entry;
    }
}
