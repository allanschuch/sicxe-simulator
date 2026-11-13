import controller.MachineController;
import model.machine.SICXEMachine;
import view.AssemblerPanel;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Files;
import java.nio.file.Path;
import javax.swing.*;
import java.awt.*;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.jupiter.api.Assertions.*;

class AssemblerPanelTest {
    @TempDir
    Path directory;

    private static <T> List<T> components(Container root, Class<T> type) {
        List<T> result = new ArrayList<>();
        for (Component component : root.getComponents()) {
            if (type.isInstance(component)) {
                result.add(type.cast(component));
            }
            if (component instanceof Container container) {
                result.addAll(components(container, type));
            }
        }
        return result;
    }

    private static JButton button(AssemblerPanel panel, String text) {
        return components(panel, JButton.class).stream().filter(b -> b.getText().equals(text)).findFirst()
                .orElseThrow();
    }

    @Test
    void editingInvalidatesSuccessfulAssemblyAndFailedAssemblyCannotLoad() throws Exception {
        AssemblerPanel panel = createPanel();
        SwingUtilities.invokeAndWait(() -> {
            assertFalse(button(panel, "Carregar na VM").isEnabled());
            button(panel, "Montar").doClick();
        });
        waitForAssembly(panel);
        SwingUtilities.invokeAndWait(() -> {
            assertTrue(button(panel, "Carregar na VM").isEnabled());
            assertTrue(button(panel, "Salvar objeto").isEnabled());
            JTextArea editor = components(panel, JTextArea.class).stream().filter(JTextArea::isEditable).findFirst()
                    .orElseThrow();
            editor.setText("P START 0\nFIRST LDA UNKNOWN\nEND FIRST");
            assertFalse(button(panel, "Carregar na VM").isEnabled());
            assertFalse(button(panel, "Salvar objeto").isEnabled());
            button(panel, "Montar").doClick();
        });
        waitForAssembly(panel);
        SwingUtilities.invokeAndWait(() -> {
            assertFalse(button(panel, "Carregar na VM").isEnabled());
            assertFalse(button(panel, "Salvar objeto").isEnabled());
            assertTrue(button(panel, "Salvar listagem").isEnabled());
            assertTrue(components(panel, JTextArea.class).stream().filter(a -> !a.isEditable())
                    .anyMatch(a -> a.getText().contains("Símbolo indefinido")));
        });
    }

    private AssemblerPanel createPanel() throws Exception {
        MachineController controller = new MachineController(new SICXEMachine());
        controller.setMacroOutputDirectory(directory); // evita gravar MASMAPRG.ASM na pasta do projeto
        AtomicReference<AssemblerPanel> holder = new AtomicReference<>();
        SwingUtilities.invokeAndWait(() -> holder.set(new AssemblerPanel(controller, () -> {
        })));
        return holder.get();
    }

    @Test
    void assemblingMacrosShowsExpandedSourceAndWritesTheFile() throws Exception {
        AssemblerPanel panel = createPanel();
        SwingUtilities.invokeAndWait(() -> {
            components(panel, JTextArea.class).stream().filter(JTextArea::isEditable).findFirst().orElseThrow()
                    .setText("P START 0\nSAVE MACRO &R,&M\nST&R &M\nMEND\nFIRST LDA #1\nSAVE A,RES\nRES RESW 1\nEND FIRST\n");
            button(panel, "Montar").doClick();
        });
        waitForAssembly(panel);
        SwingUtilities.invokeAndWait(() -> {
            assertTrue(button(panel, "Carregar na VM").isEnabled());
            assertTrue(components(panel, JTextArea.class).stream().filter(a -> !a.isEditable())
                    .anyMatch(a -> a.getText().contains("STA RES") && !a.getText().contains("MEND")));
        });
        assertTrue(Files.readString(directory.resolve("MASMAPRG.ASM")).contains("STA RES"));
    }

    @Test
    void macroErrorsAreShownAndBlockLoading() throws Exception {
        AssemblerPanel panel = createPanel();
        SwingUtilities.invokeAndWait(() -> {
            components(panel, JTextArea.class).stream().filter(JTextArea::isEditable).findFirst().orElseThrow()
                    .setText("P START 0\nSAVE MACRO\nFIRST RSUB\nEND FIRST\n");
            button(panel, "Montar").doClick();
        });
        waitForAssembly(panel);
        SwingUtilities.invokeAndWait(() -> {
            assertFalse(button(panel, "Carregar na VM").isEnabled());
            assertTrue(components(panel, JTextArea.class).stream().filter(a -> !a.isEditable())
                    .anyMatch(a -> a.getText().contains("sem MEND")));
        });
        assertFalse(Files.exists(directory.resolve("MASMAPRG.ASM")));
    }

    private void waitForAssembly(AssemblerPanel panel) throws Exception {
        long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(10);
        while (System.nanoTime() < deadline) {
            boolean[] done = { false };
            SwingUtilities.invokeAndWait(() -> done[0] = button(panel, "Montar").isEnabled());
            if (done[0]) {
                return;
            }
            Thread.sleep(20);
        }
        fail("A interface não concluiu a montagem.");
    }
}
