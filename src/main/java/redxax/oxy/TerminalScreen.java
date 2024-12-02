package redxax.oxy;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.text.Text;
import net.minecraft.text.OrderedText;
import org.lwjgl.glfw.GLFW;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class TerminalScreen extends Screen {
    private static final String COMMAND_HISTORY_FILE = "command_history.log";
    private static final String TERMINAL_OUTPUT_FILE = "terminal_output.log";
    private final MinecraftClient minecraftClient;
    public static Process terminalProcess;
    private BufferedReader reader;
    private BufferedReader errorReader;
    private Writer writer;
    private static StringBuilder terminalOutput = new StringBuilder();
    private StringBuilder inputBuffer = new StringBuilder();
    private ExecutorService executorService = Executors.newFixedThreadPool(2);
    private List<String> commandHistory = new ArrayList<>();
    private int historyIndex = -1;
    private int scrollOffset = 0;
    private int cursorPosition = 0;
    private long lastBlinkTime = 0;
    private boolean cursorVisible = true;

    public TerminalScreen(MinecraftClient minecraftClient, Process terminalProcess) {
        super(Text.literal("Terminal"));
        this.minecraftClient = minecraftClient;
        TerminalScreen.terminalProcess = terminalProcess;

        loadCommandHistory();
        loadTerminalOutput();

        try {
            reader = new BufferedReader(new InputStreamReader(terminalProcess.getInputStream(), StandardCharsets.UTF_8));
            errorReader = new BufferedReader(new InputStreamReader(terminalProcess.getErrorStream(), StandardCharsets.UTF_8));
            writer = new OutputStreamWriter(terminalProcess.getOutputStream(), StandardCharsets.UTF_8);
        } catch (Exception e) {
            e.printStackTrace();
        }

        executorService.submit(this::readTerminalOutput);
        executorService.submit(this::readErrorOutput);
    }

    private void loadCommandHistory() {
        try {
            List<String> lines = Files.readAllLines(Paths.get(COMMAND_HISTORY_FILE), StandardCharsets.UTF_8);
            commandHistory.addAll(lines);
            historyIndex = commandHistory.size();
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    private void saveCommandHistory() {
        try (BufferedWriter writer = Files.newBufferedWriter(Paths.get(COMMAND_HISTORY_FILE), StandardCharsets.UTF_8)) {
            for (String command : commandHistory) {
                writer.write(command);
                writer.newLine();
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    private void loadTerminalOutput() {
        try {
            List<String> lines = Files.readAllLines(Paths.get(TERMINAL_OUTPUT_FILE), StandardCharsets.UTF_8);
            for (String line : lines) {
                terminalOutput.append(line).append("\n");
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    private void saveTerminalOutput() {
        try (BufferedWriter writer = Files.newBufferedWriter(Paths.get(TERMINAL_OUTPUT_FILE), StandardCharsets.UTF_8)) {
            writer.write(terminalOutput.toString());
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    private void readTerminalOutput() {
        try {
            String line;
            while ((line = reader.readLine()) != null) {
                line = line.replace("\u0000", ""); // Remove NULL symbols
                synchronized (terminalOutput) {
                    terminalOutput.append(line).append("\n");
                }
                saveTerminalOutput();
                this.minecraftClient.execute(this::refreshScreen);
            }
        } catch (Exception e) {
            e.printStackTrace();
        } finally {
            try {
                reader.close();
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
    }

    private void readErrorOutput() {
        try {
            String line;
            while ((line = errorReader.readLine()) != null) {
                line = line.replace("\u0000", ""); // Remove NULL symbols
                synchronized (terminalOutput) {
                    terminalOutput.append("ERROR: ").append(line).append("\n");
                }
                saveTerminalOutput();
                this.minecraftClient.execute(this::refreshScreen);
            }
        } catch (Exception e) {
            e.printStackTrace();
        } finally {
            try {
                errorReader.close();
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
    }

    private void refreshScreen() {
        if (this.minecraftClient.currentScreen == this) {
            this.minecraftClient.currentScreen.init(this.minecraftClient, this.width, this.height);
            scrollOffset = 0;
        }
    }

    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        this.renderBackground(context, mouseX, mouseY, delta);

        int x = 10;
        int y = 10 - scrollOffset;
        int maxWidth = this.width - 20;
        int inputFieldHeight = 20;

        synchronized (terminalOutput) {
            Text terminalText = Text.literal(terminalOutput.toString());
            for (OrderedText line : this.textRenderer.wrapLines(terminalText, maxWidth)) {
                if (y + this.textRenderer.fontHeight >= this.height - inputFieldHeight) {
                    break;
                }
                context.drawText(this.textRenderer, line, x, y, 0xFFFFFF, true);
                y += this.textRenderer.fontHeight;
            }
        }

        // Draw the input buffer and cursor
        String inputText = "> " + inputBuffer.toString();
        context.drawText(this.textRenderer, inputText, x, this.height - inputFieldHeight, 0x4AF626, true);

        // Draw the cursor
        if (System.currentTimeMillis() - lastBlinkTime > 500) {
            cursorVisible = !cursorVisible;
            lastBlinkTime = System.currentTimeMillis();
        }
        if (cursorVisible) {
            int cursorX = x + this.textRenderer.getWidth("> " + inputBuffer.substring(0, cursorPosition));
            context.fill(cursorX, this.height - inputFieldHeight, cursorX + 1, this.height - inputFieldHeight + this.textRenderer.fontHeight, 0x4AF626);
        }

        super.render(context, mouseX, mouseY, delta);
    }

    @Override
    public boolean charTyped(char chr, int keyCode) {
        if (chr >= 32 && chr != 127) {
            inputBuffer.insert(cursorPosition, chr);
            cursorPosition++;
        }
        return true;
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (keyCode == GLFW.GLFW_KEY_ENTER) {
            try {
                String command = inputBuffer.toString().trim();
                if (command.equals("clear")) {
                    terminalOutput.setLength(0);
                } else if (command.equals("!exit")) {
                    terminalProcess.destroy();
                    terminalProcess = null;
                } else {
                    writer.write(command + "\n");
                    writer.flush();
                }
                if (!command.isEmpty() && (commandHistory.isEmpty() || !command.equals(commandHistory.get(commandHistory.size() - 1)))) {
                    commandHistory.add(command);
                    historyIndex = commandHistory.size();
                    saveCommandHistory();
                }
                inputBuffer.setLength(0);
                cursorPosition = 0;
            } catch (IOException e) {
                e.printStackTrace();
                terminalOutput.append("ERROR: ").append(e.getMessage()).append("\n");
                this.minecraftClient.execute(this::refreshScreen);
            }
            return true;
        }

        if (keyCode == GLFW.GLFW_KEY_UP) { // Up arrow for command history
            if (historyIndex > 0) {
                historyIndex--;
                inputBuffer.setLength(0);
                inputBuffer.append(commandHistory.get(historyIndex));
                cursorPosition = inputBuffer.length();
            }
            return true;
        }

        if (keyCode == GLFW.GLFW_KEY_BACKSPACE) { // Backspace to delete characters
            if (cursorPosition > 0) {
                inputBuffer.deleteCharAt(cursorPosition - 1);
                cursorPosition--;
            }
            return true;
        }

        if (keyCode == GLFW.GLFW_KEY_DOWN) { // Down arrow for command history
            if (historyIndex < commandHistory.size() - 1) {
                historyIndex++;
                inputBuffer.setLength(0);
                inputBuffer.append(commandHistory.get(historyIndex));
                cursorPosition = inputBuffer.length();
            } else {
                historyIndex = commandHistory.size();
                inputBuffer.setLength(0);
                cursorPosition = 0;
            }
            return true;
        }

        if (keyCode == GLFW.GLFW_KEY_LEFT) { // Left arrow to move cursor left
            if (cursorPosition > 0) {
                cursorPosition--;
            }
            return true;
        }

        if (keyCode == GLFW.GLFW_KEY_RIGHT) { // Right arrow to move cursor right
            if (cursorPosition < inputBuffer.length()) {
                cursorPosition++;
            }
            return true;
        }

        if (keyCode == GLFW.GLFW_KEY_V && (modifiers & GLFW.GLFW_MOD_CONTROL) != 0) { // Ctrl + V for pasting
            String clipboard = this.minecraftClient.keyboard.getClipboard();
            inputBuffer.insert(cursorPosition, clipboard);
            cursorPosition += clipboard.length();
            return true;
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (button == 0) { // Left mouse button
            int x = 10;
            int y = this.height - 20;
            int maxWidth = this.width - 20;
            int clickX = (int) mouseX - x;
            int clickY = (int) mouseY - y;

            if (clickY >= 0 && clickY < 20) {
                int charWidth = this.textRenderer.getWidth(" ");
                cursorPosition = Math.min(clickX / charWidth, inputBuffer.length());
            }
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double horizontalAmount, double verticalAmount) {
        int lineHeight = this.textRenderer.fontHeight;
        int maxScrollOffset = Math.max(0, terminalOutput.toString().split("\n").length * lineHeight - this.height + 20);

        scrollOffset -= verticalAmount * lineHeight * 3;
        if (scrollOffset < 0) scrollOffset = 0;
        if (scrollOffset > maxScrollOffset) scrollOffset = maxScrollOffset;

        return true;
    }

    @Override
    public void close() {
        super.close();
        // Do not close the terminal process here
        saveTerminalOutput();
        executorService.shutdown();
    }
}