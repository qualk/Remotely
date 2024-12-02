package redxax.oxy;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.text.OrderedText;
import net.minecraft.text.Style;
import net.minecraft.text.Text;
import net.minecraft.text.TextColor;
import net.minecraft.client.util.InputUtil;
import org.lwjgl.glfw.GLFW;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class TerminalScreen extends Screen {
    private static final String COMMAND_HISTORY_FILE = "command_history.log";
    private static final String TERMINAL_OUTPUT_FILE = "terminal_output.log";
    private static final String CONFIG_FILE = "terminal_config.properties";
    private final MinecraftClient minecraftClient;
    private Process terminalProcess;
    private BufferedReader reader;
    private BufferedReader errorReader;
    private Writer writer;
    private static final StringBuilder terminalOutput = new StringBuilder();
    private final StringBuilder inputBuffer = new StringBuilder();
    private static final ExecutorService executorService = Executors.newFixedThreadPool(2);
    private static boolean readersStarted = false;
    private final List<String> commandHistory = new ArrayList<>();
    private int historyIndex = -1;
    private int scrollOffset = 0;
    private int cursorPosition = 0;
    private long lastBlinkTime = 0;
    private boolean cursorVisible = true;
    private float scale = 1.0f;
    private static final float MIN_SCALE = 0.5f;
    private static final float MAX_SCALE = 2.0f;
    private Properties configProperties;

    private final RemotelyClient remotelyClient;

    private static final Pattern INFO_PATTERN = Pattern.compile("\\[.*?INFO.*?\\]");
    private static final Pattern WARN_PATTERN = Pattern.compile("\\[.*?WARN.*?\\]");
    private static final Pattern ERROR_PATTERN = Pattern.compile("\\[.*?ERROR.*?\\]");

    public TerminalScreen(MinecraftClient minecraftClient, Process terminalProcess, RemotelyClient remotelyClient) {
        super(Text.literal("Terminal"));
        this.minecraftClient = minecraftClient;
        this.terminalProcess = terminalProcess;
        this.remotelyClient = remotelyClient;

        loadConfig();
        loadCommandHistory();
        loadTerminalOutput();

        try {
            reader = new BufferedReader(new InputStreamReader(this.terminalProcess.getInputStream(), StandardCharsets.UTF_8));
            errorReader = new BufferedReader(new InputStreamReader(this.terminalProcess.getErrorStream(), StandardCharsets.UTF_8));
            writer = new OutputStreamWriter(this.terminalProcess.getOutputStream(), StandardCharsets.UTF_8);
        } catch (Exception e) {
            e.printStackTrace();
        }

        synchronized (TerminalScreen.class) {
            if (!readersStarted) {
                executorService.submit(this::readTerminalOutput);
                executorService.submit(this::readErrorOutput);
                readersStarted = true;
            }
        }
    }

    private void loadConfig() {
        configProperties = new Properties();
        File configFile = new File(CONFIG_FILE);
        if (configFile.exists()) {
            try (FileInputStream fis = new FileInputStream(configFile)) {
                configProperties.load(fis);
                String scaleStr = configProperties.getProperty("scale");
                if (scaleStr != null) {
                    try {
                        scale = Float.parseFloat(scaleStr);
                        scale = Math.max(MIN_SCALE, Math.min(MAX_SCALE, scale));
                    } catch (NumberFormatException e) {
                        scale = 1.0f;
                    }
                }
            } catch (IOException e) {
                e.printStackTrace();
                scale = 1.0f;
            }
        } else {
            configProperties.setProperty("scale", String.valueOf(scale));
            saveConfig();
        }
    }

    private void saveConfig() {
        try (FileOutputStream fos = new FileOutputStream(CONFIG_FILE)) {
            configProperties.store(fos, "Terminal Configuration");
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    private void loadCommandHistory() {
        try {
            List<String> lines = Files.readAllLines(Paths.get(COMMAND_HISTORY_FILE), StandardCharsets.UTF_8);
            commandHistory.addAll(lines);
            historyIndex = commandHistory.size();
        } catch (IOException e) {
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
            synchronized (terminalOutput) {
                terminalOutput.setLength(0);
                for (String line : lines) {
                    terminalOutput.append(line).append("\n");
                }
            }
        } catch (IOException e) {
        }
    }

    private void saveTerminalOutput() {
        synchronized (terminalOutput) {
            try (BufferedWriter writer = Files.newBufferedWriter(Paths.get(TERMINAL_OUTPUT_FILE), StandardCharsets.UTF_8)) {
                writer.write(terminalOutput.toString());
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
    }

    private void readTerminalOutput() {
        try {
            String line;
            while ((line = reader.readLine()) != null) {
                line = line.replace("\u0000", "").replace("\r", "");
                synchronized (terminalOutput) {
                    terminalOutput.append(line).append("\n");
                    saveTerminalOutput();
                }
                minecraftClient.execute(this::autoScrollIfAtBottom);
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private void readErrorOutput() {
        try {
            String line;
            while ((line = errorReader.readLine()) != null) {
                line = line.replace("\u0000", "").replace("\r", "");
                synchronized (terminalOutput) {
                    terminalOutput.append("ERROR: ").append(line).append("\n");
                    saveTerminalOutput();
                }
                minecraftClient.execute(this::autoScrollIfAtBottom);
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
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

    private int getVisibleLines() {
        float safeScale = Math.max(scale, 0.1f);
        return (int) ((this.height - getInputFieldHeight() - 20) / (10 * safeScale));
    }

    private int getInputFieldHeight() {
        return (int) (20 / scale);
    }

    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        this.renderBackground(context, mouseX, mouseY, delta);

        context.getMatrices().push();
        context.getMatrices().scale(scale, scale, 1.0f);

        float currentScale = Math.max(scale, 0.1f);
        int x = (int) (10 / currentScale);
        int yStart = (int) (10 / currentScale);
        int maxWidth = (int) ((this.width - 20) / currentScale);
        int inputFieldHeight = getInputFieldHeight();

        synchronized (terminalOutput) {
            String[] lines = terminalOutput.toString().split("\n", -1);
            int totalLines = lines.length;
            int visibleLines = getVisibleLines();

            scrollOffset = Math.min(scrollOffset, Math.max(0, totalLines - visibleLines));

            int startLine = Math.max(0, totalLines - visibleLines - scrollOffset);
            int endLine = Math.min(totalLines, startLine + visibleLines);

            for (int i = startLine; i < endLine; i++) {
                String line = lines[i];
                List<OrderedText> lineSegments = parseAnsiAndHighlight(line, maxWidth);
                int currentX = x;
                for (OrderedText segment : lineSegments) {
                    context.drawText(this.textRenderer, segment, currentX, yStart, 0, false);
                    currentX += this.textRenderer.getWidth(segment);
                    if (currentX - x > maxWidth) {
                        break;
                    }
                }
                yStart += 10;
                if (yStart + 10 > (this.height - inputFieldHeight - 20) / currentScale) {
                    break;
                }
            }
        }

        String inputText = "> " + inputBuffer.toString();
        context.drawText(this.textRenderer, Text.literal(inputText), x, (int) ((this.height - inputFieldHeight) / currentScale), 0x4AF626, false);

        if (System.currentTimeMillis() - lastBlinkTime > 500) {
            cursorVisible = !cursorVisible;
            lastBlinkTime = System.currentTimeMillis();
        }
        if (cursorVisible) {
            float safeScale = Math.max(scale, 0.1f);
            String beforeCursor = "> " + inputBuffer.substring(0, Math.min(cursorPosition, inputBuffer.length()));
            int cursorX = x + (int) (this.textRenderer.getWidth(beforeCursor) / safeScale);
            int cursorY = (int) ((this.height - inputFieldHeight) / safeScale);
            context.fill(cursorX, cursorY, cursorX + 1, cursorY + 10, 0x4AF626);
        }

        context.getMatrices().pop();

        super.render(context, mouseX, mouseY, delta);
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
            if (this.textRenderer.getWidth(potentialLine) > maxWidth) {
                if (line.length() > 0) {
                    wrapped.add(Text.literal(line.toString()).setStyle(style).asOrderedText());
                    line = new StringBuilder(word);
                } else {
                    for (char c : word.toCharArray()) {
                        String potentialCharLine = line.toString() + c;
                        if (this.textRenderer.getWidth(potentialCharLine) > maxWidth) {
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

    @Override
    public boolean charTyped(char chr, int keyCode) {
        if (chr >= 32 && chr != 127) {
            inputBuffer.insert(cursorPosition, chr);
            cursorPosition++;
            scrollToBottom();
            return true;
        }
        return super.charTyped(chr, keyCode);
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (keyCode == GLFW.GLFW_KEY_ENTER) {
            try {
                String command = inputBuffer.toString().trim();
                if (command.equalsIgnoreCase("clear")) {
                    synchronized (terminalOutput) {
                        terminalOutput.setLength(0);
                        saveTerminalOutput();
                    }
                } else if (command.equalsIgnoreCase("!exit")) {
                    remotelyClient.shutdownTerminal();
                    this.close();
                    return true;
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
                scrollToBottom();
            } catch (IOException e) {
                e.printStackTrace();
                synchronized (terminalOutput) {
                    terminalOutput.append("ERROR: ").append(e.getMessage()).append("\n");
                    saveTerminalOutput();
                }
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

        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (button == 0) {
            float safeScale = Math.max(scale, 0.1f);
            double scaledMouseX = mouseX / scale;
            double scaledMouseY = mouseY / scale;

            int x = (int) (10 / safeScale);
            int y = (int) ((this.height - getInputFieldHeight()) / safeScale);
            int clickX = (int) scaledMouseX - x;
            int clickY = (int) scaledMouseY - y;

            if (clickY >= 0 && clickY < getInputFieldHeight()) {
                int charWidth = this.textRenderer.getWidth(" ");
                charWidth = Math.max(charWidth, 1);
                cursorPosition = Math.min((int) (clickX / (charWidth / safeScale)), inputBuffer.length());
            }
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double horizontalAmount, double verticalAmount) {
        boolean ctrlHeld = InputUtil.isKeyPressed(
                this.minecraftClient.getWindow().getHandle(),
                GLFW.GLFW_KEY_LEFT_CONTROL) ||
                InputUtil.isKeyPressed(this.minecraftClient.getWindow().getHandle(),
                        GLFW.GLFW_KEY_RIGHT_CONTROL);

        if (ctrlHeld) {
            scale += verticalAmount > 0 ? 0.1f : -0.1f;
            scale = Math.max(MIN_SCALE, Math.min(MAX_SCALE, scale));
            configProperties.setProperty("scale", String.valueOf(scale));
            saveConfig();
        } else {
            int totalLines = getTotalLines();
            int visibleLines = getVisibleLines();
            int maxScroll = Math.max(0, totalLines - visibleLines);

            scrollOffset += verticalAmount > 0 ? 3 : -3;
            scrollOffset = Math.max(0, Math.min(scrollOffset, maxScroll));
        }
        return true;
    }

    private void scrollToBottom() {
        scrollOffset = 0;
    }

    private void shutdownReaders() {
        if (readersStarted) {
            executorService.shutdownNow();
            readersStarted = false;
        }
    }

    public void shutdownTerminal() {
        if (terminalProcess != null && terminalProcess.isAlive()) {
            terminalProcess.destroy();
            terminalProcess = null;
        }

        synchronized (terminalOutput) {
            terminalOutput.setLength(0);
            saveTerminalOutput();
        }

        try {
            Files.deleteIfExists(Paths.get(COMMAND_HISTORY_FILE));
            Files.deleteIfExists(Paths.get(TERMINAL_OUTPUT_FILE));
        } catch (IOException e) {
            e.printStackTrace();
        }

        shutdownReaders();
    }

    @Override
    public void close() {
        super.close();
        remotelyClient.onTerminalScreenClosed();
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
