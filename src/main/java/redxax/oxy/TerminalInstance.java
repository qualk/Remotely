package redxax.oxy;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.text.OrderedText;
import net.minecraft.text.Style;
import net.minecraft.text.Text;
import net.minecraft.text.TextColor;
import org.lwjgl.glfw.GLFW;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static redxax.oxy.MultiTerminalScreen.SCROLL_STEP;

public class TerminalInstance {

    private Process terminalProcess;
    private BufferedReader reader;
    private BufferedReader errorReader;
    private Writer writer;
    private final StringBuilder terminalOutput = new StringBuilder();
    private final StringBuilder inputBuffer = new StringBuilder();
    private final ExecutorService executorService = Executors.newFixedThreadPool(2);
    private boolean isRunning = true;
    private final MinecraftClient minecraftClient;
    private final MultiTerminalScreen parentScreen;

    private final List<String> commandHistory = new ArrayList<>();
    private int historyIndex = -1;
    private int scrollOffset = 0;
    private int cursorPosition = 0;
    private long lastBlinkTime = 0;
    private boolean cursorVisible = true;
    private float scale = 1.0f;
    private static final float MIN_SCALE = 0.1f;
    private static final float MAX_SCALE = 2.0f;

    private static final Pattern INFO_PATTERN = Pattern.compile("\\[.*?INFO.*?\\]");
    private static final Pattern WARN_PATTERN = Pattern.compile("\\[.*?WARN.*?\\]");
    private static final Pattern ERROR_PATTERN = Pattern.compile("\\[.*?ERROR.*?\\]");

    private final UUID terminalId;
    private static final Path LOG_DIR = Paths.get(System.getProperty("user.dir"), "remotely_logs");
    private final Path commandLogPath;

    public TerminalInstance(MinecraftClient client, MultiTerminalScreen parent, UUID id) {
        this.minecraftClient = client;
        this.parentScreen = parent;
        this.terminalId = id;
        this.commandLogPath = LOG_DIR.resolve("commands_" + terminalId.toString() + ".log");
        launchTerminal();
        startReaders();
    }

    private void launchTerminal() {
        try {
            ProcessBuilder processBuilder = new ProcessBuilder("cmd.exe", "/k", "powershell");
            processBuilder.redirectErrorStream(true);
            terminalProcess = processBuilder.start();
            reader = new BufferedReader(new InputStreamReader(terminalProcess.getInputStream(), StandardCharsets.UTF_8));
            errorReader = new BufferedReader(new InputStreamReader(terminalProcess.getErrorStream(), StandardCharsets.UTF_8));
            writer = new OutputStreamWriter(terminalProcess.getOutputStream(), StandardCharsets.UTF_8);
        } catch (Exception e) {
            appendOutput("Failed to launch terminal process: " + e.getMessage() + "\n");
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
                appendOutput(line + "\n");
            }
        } catch (IOException e) {
            appendOutput("Error reading terminal output: " + e.getMessage() + "\n");
            e.printStackTrace();
        }
    }

    private void readErrorOutput() {
        try {
            String line;
            while (isRunning && (line = errorReader.readLine()) != null) {
                line = line.replace("\u0000", "").replace("\r", "");
                appendOutput("ERROR: " + line + "\n");
            }
        } catch (IOException e) {
            appendOutput("Error reading terminal error output: " + e.getMessage() + "\n");
            e.printStackTrace();
        }
    }

    private synchronized void appendOutput(String text) {
        terminalOutput.append(text);
        minecraftClient.execute(this::autoScrollIfAtBottom);
    }

    private void autoScrollIfAtBottom() {
        if (scrollOffset == 0) {
            scrollToBottom();
        }
    }

    private int getTotalLines() {
        synchronized (terminalOutput) {
            return terminalOutput.toString().split("\n", -1).length;
        }
    }

    private int getVisibleLines(int terminalHeight) {
        float safeScale = Math.max(scale, MIN_SCALE);
        return (int) ((terminalHeight - getInputFieldHeight()) / (10 * safeScale));
    }

    private int getInputFieldHeight() {
        return (int) (20 / Math.max(scale, MIN_SCALE));
    }

    public void render(DrawContext context, int mouseX, int mouseY, float delta, int screenWidth, int screenHeight, float scale) {
        this.scale = Math.max(MIN_SCALE, Math.min(scale, MAX_SCALE));
        int terminalX = 10;
        int terminalY = MultiTerminalScreen.TAB_HEIGHT + 10;
        int terminalWidth = screenWidth - 20;
        int terminalHeight = screenHeight - terminalY - 40;

        context.fill(terminalX, terminalY, terminalX + terminalWidth, terminalY + terminalHeight, 0xFF000000);

        context.getMatrices().push();
        context.getMatrices().scale(this.scale, this.scale, 1.0f);

        float currentScale = Math.max(this.scale, MIN_SCALE);
        int x = (int) (terminalX / currentScale) + 5;
        int yStart = (int) (terminalY / currentScale) + 5;
        int maxWidth = (int) ((terminalWidth - 10) / currentScale);
        int inputFieldHeight = getInputFieldHeight();

        synchronized (terminalOutput) {
            String[] lines = terminalOutput.toString().split("\n", -1);
            int totalLines = lines.length;
            int visibleLines = getVisibleLines(terminalHeight);

            scrollOffset = Math.min(scrollOffset, Math.max(0, totalLines - visibleLines));

            int startLine = Math.max(0, totalLines - visibleLines - scrollOffset);
            int endLine = Math.min(totalLines, startLine + visibleLines);

            for (int i = startLine; i < endLine; i++) {
                String line = lines[i];
                List<OrderedText> lineSegments = parseAnsiAndHighlight(line, maxWidth);
                int currentX = x;
                for (OrderedText segment : lineSegments) {
                    context.drawText(minecraftClient.textRenderer, segment, currentX, yStart, 0, false);
                    currentX += minecraftClient.textRenderer.getWidth(segment);
                    if (currentX - x > maxWidth) {
                        break;
                    }
                }
                yStart += 10;
                if (yStart + 10 > (terminalHeight - inputFieldHeight - 10) / currentScale) {
                    break;
                }
            }
        }

        String inputText = "> " + inputBuffer.toString();
        context.drawText(minecraftClient.textRenderer, Text.literal(inputText), x, (int) ((terminalHeight - inputFieldHeight) / currentScale) + terminalY / (int) scale, 0x4AF626, false);

        if (System.currentTimeMillis() - lastBlinkTime > 500) {
            cursorVisible = !cursorVisible;
            lastBlinkTime = System.currentTimeMillis();
        }
        if (cursorVisible) {
            float safeScale = Math.max(scale, MIN_SCALE);
            String beforeCursor = "> " + inputBuffer.substring(0, Math.min(cursorPosition, inputBuffer.length()));
            int cursorX = x + (int) (minecraftClient.textRenderer.getWidth(beforeCursor) / safeScale);
            int cursorY = (int) ((terminalHeight - inputFieldHeight) / safeScale) + terminalY / (int) scale;
            context.fill(cursorX, cursorY, cursorX + 1, cursorY + 10, 0x4AF626);
        }

        context.getMatrices().pop();
    }

    private List<OrderedText> parseAnsiAndHighlight(String text, int maxWidth) {
        List<OrderedText> result = new ArrayList<>();
        List<StyleTextPair> styledSegments = parseAnsiCodes(text);
        for (StyleTextPair segment : styledSegments) {
            List<StyleTextPair> highlightedSegments = applyKeywordHighlighting(segment);
            for (StyleTextPair highlightedSegment : highlightedSegments) {
                List<OrderedText> wrappedLines = wrapText(highlightedSegment.text, highlightedSegment.style, maxWidth);
                result.addAll(wrappedLines);
            }
        }
        return result;
    }

    private List<StyleTextPair> parseAnsiCodes(String text) {
        List<StyleTextPair> segments = new ArrayList<>();
        String[] parts = text.split("\u001B\\[");
        Style currentStyle = Style.EMPTY.withColor(TextColor.fromRgb(0xFFFFFF));
        for (int i = 0; i < parts.length; i++) {
            String part = parts[i];
            if (i == 0) {
                segments.add(new StyleTextPair(currentStyle, part));
                continue;
            }
            int mIndex = part.indexOf('m');
            if (mIndex != -1) {
                String code = part.substring(0, mIndex);
                String content = part.substring(mIndex + 1);
                currentStyle = applyAnsiCodes(currentStyle, code);
                if (!content.isEmpty()) {
                    segments.add(new StyleTextPair(currentStyle, content));
                }
            } else {
                segments.add(new StyleTextPair(currentStyle, part));
            }
        }
        return segments;
    }

    private Style applyAnsiCodes(Style style, String code) {
        String[] codes = code.split(";");
        for (int i = 0; i < codes.length; i++) {
            String c = codes[i];
            int codeNum;
            try {
                codeNum = Integer.parseInt(c);
            } catch (NumberFormatException e) {
                continue;
            }
            if (codeNum == 0) {
                style = Style.EMPTY.withColor(TextColor.fromRgb(0xFFFFFF));
            } else if (codeNum == 38) {
                if (i + 2 < codes.length && "5".equals(codes[i + 1])) {
                    String colorIndexStr = codes[i + 2];
                    try {
                        int colorIndex = Integer.parseInt(colorIndexStr);
                        TextColor color = TextColor.fromRgb(get256ColorRGB(colorIndex));
                        style = style.withColor(color);
                        i += 2;
                    } catch (NumberFormatException ex) {
                    }
                }
            } else if (codeNum >= 30 && codeNum <= 37) {
                TextColor color = getStandardColor(codeNum - 30);
                style = style.withColor(color);
            }
        }
        return style;
    }

    private List<StyleTextPair> applyKeywordHighlighting(StyleTextPair segment) {
        List<StyleTextPair> highlighted = new ArrayList<>();
        String text = segment.text;
        Style style = segment.style;
        Pattern combinedPattern = Pattern.compile("\\[.*?INFO.*?\\]|\\[.*?WARN.*?\\]|\\[.*?ERROR.*?\\]");
        Matcher matcher = combinedPattern.matcher(text);
        int lastEnd = 0;
        while (matcher.find()) {
            if (matcher.start() > lastEnd) {
                String before = text.substring(lastEnd, matcher.start());
                highlighted.add(new StyleTextPair(style, before));
            }
            String matched = matcher.group();
            Style keywordStyle = style;
            if (INFO_PATTERN.matcher(matched).matches()) {
                keywordStyle = style.withColor(TextColor.fromRgb(0x00FF00));
            } else if (WARN_PATTERN.matcher(matched).matches()) {
                keywordStyle = style.withColor(TextColor.fromRgb(0xFFFF00));
            } else if (ERROR_PATTERN.matcher(matched).matches()) {
                keywordStyle = style.withColor(TextColor.fromRgb(0xFF0000));
            }
            highlighted.add(new StyleTextPair(keywordStyle, matched));
            lastEnd = matcher.end();
        }
        if (lastEnd < text.length()) {
            String remaining = text.substring(lastEnd);
            highlighted.add(new StyleTextPair(style, remaining));
        }
        return highlighted;
    }

    private List<OrderedText> wrapText(String text, Style style, int maxWidth) {
        List<OrderedText> wrapped = new ArrayList<>();
        if (text.isEmpty()) {
            wrapped.add(Text.literal("").setStyle(style).asOrderedText());
            return wrapped;
        }
        StringBuilder line = new StringBuilder();
        Pattern pattern = Pattern.compile("\\S+|\\s+");
        Matcher matcher = pattern.matcher(text);
        while (matcher.find()) {
            String word = matcher.group();
            String potentialLine = line.length() == 0 ? word : line.toString() + word;
            if (minecraftClient.textRenderer.getWidth(potentialLine) > maxWidth) {
                if (line.length() > 0) {
                    wrapped.add(Text.literal(line.toString()).setStyle(style).asOrderedText());
                    line = new StringBuilder(word);
                } else {
                    for (char c : word.toCharArray()) {
                        String potentialCharLine = line.toString() + c;
                        if (minecraftClient.textRenderer.getWidth(potentialCharLine) > maxWidth) {
                            if (line.length() > 0) {
                                wrapped.add(Text.literal(line.toString()).setStyle(style).asOrderedText());
                                line = new StringBuilder(String.valueOf(c));
                            }
                        } else {
                            line.append(c);
                        }
                    }
                }
            } else {
                line.append(word);
            }
        }
        if (line.length() > 0) {
            wrapped.add(Text.literal(line.toString()).setStyle(style).asOrderedText());
        }
        return wrapped;
    }

    private int get256ColorRGB(int index) {
        if (index < 0 || index > 255) {
            return 0xFFFFFF;
        }
        if (index < 16) {
            return getStandardColorRGB(index);
        } else if (index >= 16 && index <= 231) {
            index -= 16;
            int r = (index / 36) % 6;
            int g = (index / 6) % 6;
            int b = index % 6;
            return ((r == 0 ? 0 : 55 + r * 40) << 16) |
                    ((g == 0 ? 0 : 55 + g * 40) << 8) |
                    (b == 0 ? 0 : 55 + b * 40);
        } else if (index >= 232 && index <= 255) {
            int gray = 8 + (index - 232) * 10;
            return (gray << 16) | (gray << 8) | gray;
        } else {
            return 0xFFFFFF;
        }
    }

    private TextColor getStandardColor(int index) {
        switch (index) {
            case 0:
                return TextColor.fromRgb(0x000000);
            case 1:
                return TextColor.fromRgb(0xAA0000);
            case 2:
                return TextColor.fromRgb(0x00AA00);
            case 3:
                return TextColor.fromRgb(0xAA5500);
            case 4:
                return TextColor.fromRgb(0x0000AA);
            case 5:
                return TextColor.fromRgb(0xAA00AA);
            case 6:
                return TextColor.fromRgb(0x00AAAA);
            case 7:
                return TextColor.fromRgb(0xAAAAAA);
            default:
                return TextColor.fromRgb(0xFFFFFF);
        }
    }

    private int getStandardColorRGB(int index) {
        switch (index) {
            case 0:
                return 0x000000;
            case 1:
                return 0xAA0000;
            case 2:
                return 0x00AA00;
            case 3:
                return 0xAA5500;
            case 4:
                return 0x0000AA;
            case 5:
                return 0xAA00AA;
            case 6:
                return 0x00AAAA;
            case 7:
                return 0xAAAAAA;
            case 8:
                return 0x555555;
            case 9:
                return 0xFF5555;
            case 10:
                return 0x55FF55;
            case 11:
                return 0xFFFF55;
            case 12:
                return 0x5555FF;
            case 13:
                return 0xFF55FF;
            case 14:
                return 0x55FFFF;
            case 15:
                return 0xFFFFFF;
            default:
                return 0xFFFFFF;
        }
    }

    public boolean charTyped(char chr, int keyCode) {
        if (chr >= 32 && chr != 127) {
            inputBuffer.insert(cursorPosition, chr);
            cursorPosition++;
            scrollToBottom();
            return true;
        }
        return false;
    }

    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (keyCode == GLFW.GLFW_KEY_ENTER) {
            try {
                String command = inputBuffer.toString().trim();
                logCommand(command);
                if (command.equalsIgnoreCase("clear")) {
                    synchronized (terminalOutput) {
                        terminalOutput.setLength(0);
                    }
                } else {
                    writer.write(command + "\n");
                    writer.flush();
                }
                if (!command.isEmpty() && (commandHistory.isEmpty() || !command.equals(commandHistory.get(commandHistory.size() - 1)))) {
                    commandHistory.add(command);
                    historyIndex = commandHistory.size();
                }
                inputBuffer.setLength(0);
                cursorPosition = 0;
                scrollToBottom();
            } catch (IOException e) {
                appendOutput("ERROR: " + e.getMessage() + "\n");
                minecraftClient.execute(this::autoScrollIfAtBottom);
            }
            return true;
        }

        if (keyCode == GLFW.GLFW_KEY_UP) {
            if (historyIndex > 0) {
                historyIndex--;
                inputBuffer.setLength(0);
                inputBuffer.append(commandHistory.get(historyIndex));
                cursorPosition = inputBuffer.length();
            }
            return true;
        }

        if (keyCode == GLFW.GLFW_KEY_DOWN) {
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

        if (keyCode == GLFW.GLFW_KEY_BACKSPACE) {
            if (cursorPosition > 0) {
                inputBuffer.deleteCharAt(cursorPosition - 1);
                cursorPosition--;
                scrollToBottom();
            }
            return true;
        }

        if (keyCode == GLFW.GLFW_KEY_LEFT) {
            if (cursorPosition > 0) {
                cursorPosition--;
            }
            return true;
        }

        if (keyCode == GLFW.GLFW_KEY_RIGHT) {
            if (cursorPosition < inputBuffer.length()) {
                cursorPosition++;
            }
            return true;
        }

        if (keyCode == GLFW.GLFW_KEY_V && (modifiers & GLFW.GLFW_MOD_CONTROL) != 0) {
            String clipboard = this.minecraftClient.keyboard.getClipboard();
            inputBuffer.insert(cursorPosition, clipboard);
            cursorPosition += clipboard.length();
            scrollToBottom();
            return true;
        }

        return false;
    }

    private void logCommand(String command) {
        try {
            if (!Files.exists(LOG_DIR)) {
                Files.createDirectories(LOG_DIR);
            }
            Files.write(commandLogPath, (command + System.lineSeparator()).getBytes(StandardCharsets.UTF_8), StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException e) {
            appendOutput("Failed to log command: " + e.getMessage() + "\n");
        }
    }

    private void scrollToBottom() {
        scrollOffset = 0;
    }

    public void scroll(int direction, int terminalHeight) {
        if (direction > 0) {
            if (scrollOffset < getTotalLines() - getVisibleLines(terminalHeight)) {
                scrollOffset += SCROLL_STEP;
            }
        } else if (direction < 0) {
            if (scrollOffset > 0) {
                scrollOffset -= SCROLL_STEP;
            }
        }
        int totalLines = getTotalLines();
        int visibleLines = getVisibleLines(terminalHeight);
        scrollOffset = Math.max(0, Math.min(scrollOffset, totalLines - visibleLines));
    }

    public void shutdown() {
        isRunning = false;
        if (terminalProcess != null && terminalProcess.isAlive()) {
            terminalProcess.destroy();
            terminalProcess = null;
        }
        executorService.shutdownNow();
    }

    public void saveTerminalOutput(Path path) {
        try {
            Files.write(path, terminalOutput.toString().getBytes(StandardCharsets.UTF_8), StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
        } catch (IOException e) {
            appendOutput("Failed to save terminal output: " + e.getMessage() + "\n");
        }
    }

    public void loadTerminalOutput(Path path) {
        try {
            String content = new String(Files.readAllBytes(path), StandardCharsets.UTF_8);
            synchronized (terminalOutput) {
                terminalOutput.append(content);
            }
        } catch (IOException e) {
            appendOutput("Failed to load terminal output: " + e.getMessage() + "\n");
        }
    }

    private static class StyleTextPair {
        final Style style;
        final String text;

        StyleTextPair(Style style, String text) {
            this.style = style;
            this.text = text;
        }
    }
}
