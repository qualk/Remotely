package redxax.oxy;

import net.minecraft.client.MinecraftClient;
import org.lwjgl.glfw.GLFW;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class InputHandler {

    private Process terminalProcess;
    private BufferedReader reader;
    private BufferedReader errorReader;
    private Writer writer;
    private final StringBuilder inputBuffer = new StringBuilder();
    private final ExecutorService executorService = Executors.newFixedThreadPool(4);
    private boolean isRunning = true;
    private final MinecraftClient minecraftClient;
    private final TerminalInstance terminalInstance;

    private int cursorPosition = 0;

    private final UUID terminalId;
    private static final Path COMMAND_LOG_DIR = Paths.get(System.getProperty("user.dir"), "remotely_command_logs");
    private final Path commandLogPath;

    private final SSHManager sshManager;

    public InputHandler(MinecraftClient client, TerminalInstance terminalInstance) {
        this.minecraftClient = client;
        this.terminalInstance = terminalInstance;
        this.terminalId = terminalInstance.terminalId;
        this.commandLogPath = COMMAND_LOG_DIR.resolve("commands_" + terminalId.toString() + ".log");
        this.sshManager = terminalInstance.getSSHManager();
    }

    public void launchTerminal() {
        try {
            ProcessBuilder processBuilder = new ProcessBuilder("cmd.exe", "/k", "powershell");
            processBuilder.redirectErrorStream(true);
            terminalProcess = processBuilder.start();
            reader = new BufferedReader(new InputStreamReader(terminalProcess.getInputStream(), StandardCharsets.UTF_8));
            errorReader = new BufferedReader(new InputStreamReader(terminalProcess.getErrorStream(), StandardCharsets.UTF_8));
            writer = new OutputStreamWriter(terminalProcess.getOutputStream(), StandardCharsets.UTF_8);
            startReaders();
        } catch (Exception e) {
            terminalInstance.appendOutput("Failed to launch terminal process: " + e.getMessage() + "\n");
            e.printStackTrace();
        }
    }

    private void startReaders() {
        executorService.submit(this::readTerminalOutput);
        executorService.submit(this::readErrorOutput);
    }

    private void readTerminalOutput() {
        try {
            String line;
            while (isRunning && (line = reader.readLine()) != null) {
                line = line.replace("\u0000", "").replace("\r", "");
                terminalInstance.appendOutput(line + "\n");
            }
        } catch (IOException e) {
            terminalInstance.appendOutput("Error reading terminal output: " + e.getMessage() + "\n");
            e.printStackTrace();
        }
    }

    private void readErrorOutput() {
        try {
            String line;
            while (isRunning && (line = errorReader.readLine()) != null) {
                line = line.replace("\u0000", "").replace("\r", "");
                terminalInstance.appendOutput("ERROR: " + line + "\n");
            }
        } catch (IOException e) {
            terminalInstance.appendOutput("Error reading terminal error output: " + e.getMessage() + "\n");
            e.printStackTrace();
        }
    }

    public boolean charTyped(char chr, int keyCode) {
        if (sshManager.isAwaitingPassword()) {
            if (chr != '\n' && chr != '\r') {
                sshManager.setSshPassword(sshManager.getSshPassword() + chr);
                inputBuffer.append('*');
                cursorPosition++;
                terminalInstance.scrollToBottom();
                return true;
            }
            return false;
        }

        if (chr == '\t' || chr == '`') {
            return false;
        }

        if (chr >= 32 && chr != 127) {
            inputBuffer.insert(cursorPosition, chr);
            cursorPosition++;
            terminalInstance.scrollToBottom();
            return true;
        }
        return false;
    }

    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        boolean ctrlHeld = (modifiers & GLFW.GLFW_MOD_CONTROL) != 0;

        if (sshManager.isAwaitingPassword()) {
            if (keyCode == GLFW.GLFW_KEY_ENTER) {
                String password = sshManager.getSshPassword();
                sshManager.setSshPassword("");
                inputBuffer.setLength(0);
                cursorPosition = 0;
                terminalInstance.appendOutput("\n");
                sshManager.setAwaitingPassword(false);
                sshManager.connectSSHWithPassword(password);
                return true;
            }
            if (keyCode == GLFW.GLFW_KEY_BACKSPACE) {
                if (!sshManager.getSshPassword().isEmpty()) {
                    sshManager.setSshPassword(sshManager.getSshPassword().substring(0, sshManager.getSshPassword().length() - 1));
                    if (inputBuffer.length() > 0) {
                        inputBuffer.deleteCharAt(inputBuffer.length() - 1);
                        cursorPosition--;
                    }
                    terminalInstance.scrollToBottom();
                }
                return true;
            }
            return false;
        }

        if (keyCode == GLFW.GLFW_KEY_TAB) {
            terminalInstance.handleTabCompletion();
            return true;
        }

        if (keyCode == GLFW.GLFW_KEY_C && ctrlHeld) {
            terminalInstance.renderer.copySelectionToClipboard();
            return true;
        }

        if (keyCode == GLFW.GLFW_KEY_ENTER) {
            try {
                String command = inputBuffer.toString().trim();
                terminalInstance.logCommand(command);

                if (command.startsWith("ssh ")) {
                    sshManager.startSSHConnection(command);
                } else if (sshManager.isSSH()) {
                    sshManager.getSshWriter().write(inputBuffer.toString() + "\n");
                    sshManager.getSshWriter().flush();
                } else {
                    if (command.equalsIgnoreCase("clear")) {
                        synchronized (terminalInstance.renderer.getTerminalOutput()) {
                            terminalInstance.renderer.getTerminalOutput().setLength(0);
                        }
                    } else {
                        writer.write(inputBuffer.toString() + "\n");
                        writer.flush();
                    }
                }

                if (!command.isEmpty() && (terminalInstance.getCommandHistory().isEmpty() || !command.equals(terminalInstance.getCommandHistory().get(terminalInstance.getCommandHistory().size() - 1)))) {
                    terminalInstance.getCommandHistory().add(command);
                    terminalInstance.setHistoryIndex(terminalInstance.getCommandHistory().size());
                }
                inputBuffer.setLength(0);
                cursorPosition = 0;
                terminalInstance.scrollToBottom();
            } catch (Exception e) {
                terminalInstance.appendOutput("ERROR: " + e.getMessage() + "\n");
            }
            return true;
        }

        if (keyCode == GLFW.GLFW_KEY_UP) {
            if (terminalInstance.getHistoryIndex() > 0) {
                terminalInstance.setHistoryIndex(terminalInstance.getHistoryIndex() - 1);
                inputBuffer.setLength(0);
                inputBuffer.append(terminalInstance.getCommandHistory().get(terminalInstance.getHistoryIndex()));
                cursorPosition = inputBuffer.length();
            }
            return true;
        }

        if (keyCode == GLFW.GLFW_KEY_DOWN) {
            if (terminalInstance.getHistoryIndex() < terminalInstance.getCommandHistory().size() - 1) {
                terminalInstance.setHistoryIndex(terminalInstance.getHistoryIndex() + 1);
                inputBuffer.setLength(0);
                inputBuffer.append(terminalInstance.getCommandHistory().get(terminalInstance.getHistoryIndex()));
                cursorPosition = inputBuffer.length();
            } else {
                terminalInstance.setHistoryIndex(terminalInstance.getCommandHistory().size());
                inputBuffer.setLength(0);
                cursorPosition = 0;
            }
            return true;
        }

        if (keyCode == GLFW.GLFW_KEY_BACKSPACE) {
            if (cursorPosition > 0) {
                inputBuffer.deleteCharAt(cursorPosition - 1);
                cursorPosition--;
                terminalInstance.scrollToBottom();
            }
            return true;
        }

        if (keyCode == GLFW.GLFW_KEY_DELETE) {
            if (cursorPosition < inputBuffer.length()) {
                inputBuffer.deleteCharAt(cursorPosition);
                terminalInstance.scrollToBottom();
            }
            return true;
        }

        if (keyCode == GLFW.GLFW_KEY_LEFT) {
            if (ctrlHeld) {
                cursorPosition = moveCursorLeftWord(cursorPosition);
            } else {
                if (cursorPosition > 0) {
                    cursorPosition--;
                }
            }
            return true;
        }

        if (keyCode == GLFW.GLFW_KEY_RIGHT) {
            if (ctrlHeld) {
                cursorPosition = moveCursorRightWord(cursorPosition);
            } else {
                if (cursorPosition < inputBuffer.length()) {
                    cursorPosition++;
                }
            }
            return true;
        }

        if (keyCode == GLFW.GLFW_KEY_BACKSPACE && ctrlHeld) {
            int newCursorPos = moveCursorLeftWord(cursorPosition);
            inputBuffer.delete(newCursorPos, cursorPosition);
            cursorPosition = newCursorPos;
            terminalInstance.scrollToBottom();
            return true;
        }

        if (keyCode == GLFW.GLFW_KEY_V && ctrlHeld) {
            String clipboard = this.minecraftClient.keyboard.getClipboard();
            inputBuffer.insert(cursorPosition, clipboard);
            cursorPosition += clipboard.length();
            terminalInstance.scrollToBottom();
            return true;
        }

        return false;
    }

    private int moveCursorLeftWord(int position) {
        if (position == 0) return 0;
        int index = position - 1;
        while (index > 0 && Character.isWhitespace(inputBuffer.charAt(index))) {
            index--;
        }
        while (index > 0 && !Character.isWhitespace(inputBuffer.charAt(index - 1))) {
            index--;
        }
        return index;
    }

    private int moveCursorRightWord(int position) {
        int length = inputBuffer.length();
        if (position >= length) return length;
        int index = position;
        while (index < length && !Character.isWhitespace(inputBuffer.charAt(index))) {
            index++;
        }
        while (index < length && Character.isWhitespace(inputBuffer.charAt(index))) {
            index++;
        }
        return index;
    }

    public void handleTabCompletion() {
        String currentInput = inputBuffer.toString();
        String[] tokens = currentInput.split("\\s+");
        String lastToken = tokens.length > 0 ? tokens[tokens.length - 1] : "";

        if (lastToken.isEmpty()) {
            return;
        }

        List<String> completions = new ArrayList<>();

        if (tokens.length == 1) {
            String pathEnv = System.getenv("PATH");
            if (pathEnv != null) {
                String[] pathDirs = pathEnv.split(File.pathSeparator);
                Set<String> commandSet = new HashSet<>();
                for (String dir : pathDirs) {
                    File dirFile = new File(dir);
                    if (dirFile.isDirectory()) {
                        File[] files = dirFile.listFiles();
                        if (files != null) {
                            for (File file : files) {
                                String fileName = file.getName();
                                if (file.isFile() && isExecutable(file)) {
                                    commandSet.add(fileName);
                                }
                            }
                        }
                    }
                }
                for (String command : commandSet) {
                    if (command.startsWith(lastToken)) {
                        completions.add(command);
                    }
                }
            }
        } else {
            String path = lastToken;
            File file = new File(path);
            File dir;
            String prefix;
            if (file.isDirectory()) {
                dir = file;
                prefix = "";
            } else {
                dir = file.getParentFile();
                prefix = file.getName();
            }
            if (dir == null) {
                dir = new File(".");
            }
            File[] files = dir.listFiles();
            if (files != null) {
                for (File f : files) {
                    String name = f.getName();
                    if (name.startsWith(prefix)) {
                        completions.add(f.getPath());
                    }
                }
            }
        }

        if (completions.isEmpty()) {
            return;
        } else if (completions.size() == 1) {
            String completion = completions.get(0);
            StringBuilder newInputBuffer = new StringBuilder();
            for (int i = 0; i < tokens.length - 1; i++) {
                newInputBuffer.append(tokens[i]).append(' ');
            }
            newInputBuffer.append(completion);
            inputBuffer.setLength(0);
            inputBuffer.append(newInputBuffer.toString());
            cursorPosition = inputBuffer.length();
        } else {
            String commonPrefix = findCommonPrefix(completions);
            if (!commonPrefix.equals(lastToken)) {
                StringBuilder newInputBuffer = new StringBuilder();
                for (int i = 0; i < tokens.length - 1; i++) {
                    newInputBuffer.append(tokens[i]).append(' ');
                }
                newInputBuffer.append(commonPrefix);
                inputBuffer.setLength(0);
                inputBuffer.append(newInputBuffer.toString());
                cursorPosition = inputBuffer.length();
            } else {
                terminalInstance.appendOutput("\n");
                for (String completion : completions) {
                    terminalInstance.appendOutput(completion + "    ");
                }
                terminalInstance.appendOutput("\n");
                terminalInstance.appendOutput("> " + inputBuffer.toString());
            }
        }
    }

    private String findCommonPrefix(List<String> strings) {
        if (strings.isEmpty()) return "";
        String prefix = strings.get(0);
        for (int i = 1; i < strings.size(); i++) {
            while (!strings.get(i).startsWith(prefix)) {
                if (prefix.length() == 0) return "";
                prefix = prefix.substring(0, prefix.length() - 1);
            }
        }
        return prefix;
    }

    private boolean isExecutable(File file) {
        String name = file.getName().toLowerCase();
        if (System.getProperty("os.name").toLowerCase().contains("win")) {
            return name.endsWith(".exe") || name.endsWith(".bat") || name.endsWith(".cmd");
        } else {
            return file.canExecute();
        }
    }

    public void logCommand(String command) {
        try {
            if (!Files.exists(COMMAND_LOG_DIR)) {
                Files.createDirectories(COMMAND_LOG_DIR);
            }
            Files.writeString(commandLogPath, command + System.lineSeparator(), StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException e) {
            terminalInstance.appendOutput("Failed to log command: " + e.getMessage() + "\n");
        }
    }

    public StringBuilder getInputBuffer() {
        return inputBuffer;
    }

    public int getCursorPosition() {
        return cursorPosition;
    }

    public void shutdown() {
        isRunning = false;
        if (terminalProcess != null && terminalProcess.isAlive()) {
            terminalProcess.destroy();
            terminalProcess = null;
        }
        sshManager.shutdown();
        executorService.shutdownNow();
    }

    public void saveTerminalOutput(Path path) throws IOException {
        Files.writeString(path, terminalInstance.renderer.getTerminalOutput().toString(), StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
    }

    public void loadTerminalOutput(Path path) throws IOException {
        String content = Files.readString(path);
        synchronized (terminalInstance.renderer.getTerminalOutput()) {
            terminalInstance.renderer.getTerminalOutput().append(content);
        }
    }
}
