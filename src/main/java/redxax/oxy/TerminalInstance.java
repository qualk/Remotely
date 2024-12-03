package redxax.oxy;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.text.MutableText;
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
    private static final Path COMMAND_LOG_DIR = Paths.get(System.getProperty("user.dir"), "remotely_command_logs");
    private final Path commandLogPath;

    private boolean isSelecting = false;
    private int selectionStartIndex = -1;
    private int selectionEndIndex = -1;

    private List<LineInfo> lineInfos = new ArrayList<>();

    public TerminalInstance(MinecraftClient client, MultiTerminalScreen parent, UUID id) {
        this.minecraftClient = client;
        this.parentScreen = parent;
        this.terminalId = id;
        this.commandLogPath = COMMAND_LOG_DIR.resolve("commands_" + terminalId.toString() + ".log");
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
        lineInfos.clear();
        minecraftClient.execute(() -> parentScreen.init());
    }

    private int getTotalLines() {
        synchronized (terminalOutput) {
            return terminalOutput.toString().split("\n", -1).length;
        }
    }

    private int getVisibleLines(int terminalHeight) {
        float safeScale = Math.max(scale, MIN_SCALE);
        int visibleHeight = Math.max(terminalHeight - getInputFieldHeight(), 1);
        return Math.max((int) (visibleHeight / (minecraftClient.textRenderer.fontHeight * safeScale)), 1);
    }

    private int getInputFieldHeight() {
        return minecraftClient.textRenderer.fontHeight + 4;
    }

    public void render(DrawContext context, int mouseX, int mouseY, float delta, int screenWidth, int screenHeight, float scale) {
        this.scale = Math.max(MIN_SCALE, Math.min(scale, MAX_SCALE));

        int terminalX = 10;
        int terminalY = MultiTerminalScreen.TAB_HEIGHT + 10;
        int terminalWidth = screenWidth - 20;
        int terminalHeight = screenHeight - terminalY - 10;

        context.fill(terminalX, terminalY, terminalX + terminalWidth, terminalY + terminalHeight, 0xFF000000);

        context.getMatrices().push();

        int padding = 5;
        int textAreaX = terminalX + padding;
        int textAreaY = terminalY + padding;
        int textAreaWidth = terminalWidth - 2 * padding;
        int textAreaHeight = terminalHeight - 2 * padding - getInputFieldHeight();

        context.getMatrices().translate(textAreaX, textAreaY, 0);
        context.getMatrices().scale(this.scale, this.scale, 1.0f);

        float currentScale = Math.max(this.scale, MIN_SCALE);
        int scaledWidth = (int) (textAreaWidth / this.scale);
        int scaledHeight = (int) (textAreaHeight / this.scale);
        int x = 0;
        int yStart = 0;
        int maxWidth = scaledWidth;

        synchronized (terminalOutput) {
            String[] lines = terminalOutput.toString().split("\n", -1);
            int totalLines = lines.length;
            int visibleLines = getVisibleLines(scaledHeight);

            scrollOffset = Math.min(scrollOffset, Math.max(0, totalLines - visibleLines));

            int startLine = Math.max(0, totalLines - visibleLines - scrollOffset);
            int endLine = Math.min(totalLines, startLine + visibleLines);

            int globalCharIndex = 0;
            lineInfos.clear();

            for (int i = startLine; i < endLine; i++) {
                String line = lines[i];
                List<StyleTextPair> segments = parseAnsiAndHighlight(line);
                List<OrderedText> wrappedLines = wrapStyledText(segments, maxWidth);

                for (OrderedText wrappedLine : wrappedLines) {
                    int lineHeight = minecraftClient.textRenderer.fontHeight;
                    int lineStartIndex = globalCharIndex;
                    int lineLength = wrappedLine.toString().length();
                    LineInfo lineInfo = new LineInfo(lineStartIndex, lineLength, yStart, lineHeight);
                    lineInfos.add(lineInfo);

                    if (isTextSelected(lineStartIndex, lineLength)) {
                        int lineWidth = minecraftClient.textRenderer.getWidth(wrappedLine);
                        context.fill(x, yStart, x + lineWidth, yStart + lineHeight, 0x80FFFFFF);
                    }
                    context.drawText(minecraftClient.textRenderer, wrappedLine, x, yStart, 0xFFFFFFFF, false);
                    yStart += lineHeight;
                    globalCharIndex += lineLength;
                    if (yStart + lineHeight > scaledHeight) {
                        break;
                    }
                }
                if (yStart + minecraftClient.textRenderer.fontHeight > scaledHeight) {
                    break;
                }
            }
        }

        context.getMatrices().pop();

        int inputX = terminalX + padding;
        int inputY = terminalY + terminalHeight - padding - getInputFieldHeight();
        String inputText = "> " + inputBuffer.toString();
        context.drawText(minecraftClient.textRenderer, Text.literal(inputText), inputX, inputY, 0x4AF626, false);

        if (System.currentTimeMillis() - lastBlinkTime > 500) {
            cursorVisible = !cursorVisible;
            lastBlinkTime = System.currentTimeMillis();
        }
        if (cursorVisible) {
            String beforeCursor = "> " + inputBuffer.substring(0, Math.min(cursorPosition, inputBuffer.length()));
            int cursorXPos = inputX + minecraftClient.textRenderer.getWidth(beforeCursor);
            int cursorYPos = inputY;
            context.fill(cursorXPos, cursorYPos, cursorXPos + 1, cursorYPos + minecraftClient.textRenderer.fontHeight, 0x4AF626);
        }
    }

    private boolean isTextSelected(int lineStartIndex, int lineLength) {
        if (selectionStartIndex == -1 || selectionEndIndex == -1) {
            return false;
        }
        int selStart = Math.min(selectionStartIndex, selectionEndIndex);
        int selEnd = Math.max(selectionStartIndex, selectionEndIndex);
        int lineEndIndex = lineStartIndex + lineLength;
        return selEnd > lineStartIndex && selStart < lineEndIndex;
    }

    private List<StyleTextPair> parseAnsiAndHighlight(String text) {
        List<StyleTextPair> result = new ArrayList<>();
        List<StyleTextPair> styledSegments = parseAnsiCodes(text);
        for (StyleTextPair segment : styledSegments) {
            List<StyleTextPair> highlightedSegments = applyKeywordHighlighting(segment);
            result.addAll(highlightedSegments);
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

    private List<OrderedText> wrapStyledText(List<StyleTextPair> segments, int maxWidth) {
        List<OrderedText> wrappedLines = new ArrayList<>();
        List<StyleTextPair> currentLineSegments = new ArrayList<>();
        int currentLineWidth = 0;

        for (StyleTextPair segment : segments) {
            String text = segment.text;
            Style style = segment.style;
            int index = 0;
            while (index < text.length()) {
                int remainingWidth = maxWidth - currentLineWidth;
                int charsToFit = measureTextToFit(text.substring(index), style, remainingWidth);
                if (charsToFit == 0) {
                    if (!currentLineSegments.isEmpty()) {
                        wrappedLines.add(buildOrderedText(currentLineSegments));
                        currentLineSegments.clear();
                    }
                    currentLineWidth = 0;
                    charsToFit = Math.max(1, measureTextToFit(text.substring(index), style, maxWidth));
                }
                String substring = text.substring(index, index + charsToFit);
                currentLineSegments.add(new StyleTextPair(style, substring));
                int width = minecraftClient.textRenderer.getWidth(substring);
                currentLineWidth += width;
                index += charsToFit;

                if (currentLineWidth >= maxWidth) {
                    wrappedLines.add(buildOrderedText(currentLineSegments));
                    currentLineSegments.clear();
                    currentLineWidth = 0;
                }
            }
        }
        if (!currentLineSegments.isEmpty()) {
            wrappedLines.add(buildOrderedText(currentLineSegments));
        }
        return wrappedLines;
    }

    private int measureTextToFit(String text, Style style, int maxWidth) {
        int width = 0;
        int index = 0;
        while (index < text.length()) {
            char c = text.charAt(index);
            int charWidth = minecraftClient.textRenderer.getWidth(String.valueOf(c));
            if (width + charWidth > maxWidth) {
                break;
            }
            width += charWidth;
            index++;
        }
        return index;
    }

    private OrderedText buildOrderedText(List<StyleTextPair> segments) {
        MutableText lineText = Text.literal("");
        for (StyleTextPair segment : segments) {
            lineText.append(Text.literal(segment.text).setStyle(segment.style));
        }
        return lineText.asOrderedText();
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
        boolean ctrlHeld = (modifiers & GLFW.GLFW_MOD_CONTROL) != 0;

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
                if (!command.isEmpty() && (parentScreen.commandHistory.isEmpty() || !command.equals(parentScreen.commandHistory.get(parentScreen.commandHistory.size() - 1)))) {
                    parentScreen.commandHistory.add(command);
                    parentScreen.historyIndex = parentScreen.commandHistory.size();
                }
                inputBuffer.setLength(0);
                cursorPosition = 0;
                scrollToBottom();
            } catch (IOException e) {
                appendOutput("ERROR: " + e.getMessage() + "\n");
            }
            return true;
        }

        if (keyCode == GLFW.GLFW_KEY_UP) {
            if (parentScreen.historyIndex > 0) {
                parentScreen.historyIndex--;
                inputBuffer.setLength(0);
                inputBuffer.append(parentScreen.commandHistory.get(parentScreen.historyIndex));
                cursorPosition = inputBuffer.length();
            }
            return true;
        }

        if (keyCode == GLFW.GLFW_KEY_DOWN) {
            if (parentScreen.historyIndex < parentScreen.commandHistory.size() - 1) {
                parentScreen.historyIndex++;
                inputBuffer.setLength(0);
                inputBuffer.append(parentScreen.commandHistory.get(parentScreen.historyIndex));
                cursorPosition = inputBuffer.length();
            } else {
                parentScreen.historyIndex = parentScreen.commandHistory.size();
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

        if (keyCode == GLFW.GLFW_KEY_DELETE) {
            if (cursorPosition < inputBuffer.length()) {
                inputBuffer.deleteCharAt(cursorPosition);
                scrollToBottom();
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

        if (ctrlHeld && keyCode == GLFW.GLFW_KEY_BACKSPACE) {
            int newCursorPos = moveCursorLeftWord(cursorPosition);
            inputBuffer.delete(newCursorPos, cursorPosition);
            cursorPosition = newCursorPos;
            scrollToBottom();
            return true;
        }

        if (keyCode == GLFW.GLFW_KEY_V && ctrlHeld) {
            String clipboard = this.minecraftClient.keyboard.getClipboard();
            inputBuffer.insert(cursorPosition, clipboard);
            cursorPosition += clipboard.length();
            scrollToBottom();
            return true;
        }

        if (keyCode == GLFW.GLFW_KEY_C && ctrlHeld) {
            if (selectionStartIndex != -1 && selectionEndIndex != -1) {
                String selectedText = getSelectedText();
                minecraftClient.keyboard.setClipboard(selectedText);
            }
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

    private String getSelectedText() {
        int selStart = Math.min(selectionStartIndex, selectionEndIndex);
        int selEnd = Math.max(selectionStartIndex, selectionEndIndex);
        synchronized (terminalOutput) {
            return terminalOutput.substring(selStart, selEnd);
        }
    }

    private void logCommand(String command) {
        try {
            if (!Files.exists(COMMAND_LOG_DIR)) {
                Files.createDirectories(COMMAND_LOG_DIR);
            }
            Files.write(commandLogPath, (command + System.lineSeparator()).getBytes(StandardCharsets.UTF_8), StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException e) {
            appendOutput("Failed to log command: " + e.getMessage() + "\n");
        }
    }

    void scrollToBottom() {
        scrollOffset = 0;
    }

    public void scroll(int direction, int terminalHeight) {
        int totalLines = getTotalLines();
        int visibleLines = getVisibleLines(terminalHeight);
        if (direction > 0) {
            if (scrollOffset < totalLines - visibleLines) {
                scrollOffset += SCROLL_STEP;
            }
        } else if (direction < 0) {
            if (scrollOffset > 0) {
                scrollOffset -= SCROLL_STEP;
            }
        }
        scrollOffset = Math.max(0, Math.min(scrollOffset, totalLines - visibleLines));
    }

    public void scrollToTop(int terminalHeight) {
        int totalLines = getTotalLines();
        int visibleLines = getVisibleLines(terminalHeight);
        scrollOffset = totalLines - visibleLines;
        scrollOffset = Math.max(0, scrollOffset);
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

    public boolean mouseClicked(double mouseX, double mouseY, int button, int screenWidth, int screenHeight, float scale) {
        int terminalX = 10;
        int terminalY = MultiTerminalScreen.TAB_HEIGHT + 10;
        int terminalWidth = screenWidth - 20;
        int terminalHeight = screenHeight - terminalY - 10 - getInputFieldHeight();

        if (button == GLFW.GLFW_MOUSE_BUTTON_LEFT) {
            if (mouseX >= terminalX && mouseX <= terminalX + terminalWidth && mouseY >= terminalY && mouseY <= terminalY + terminalHeight) {
                isSelecting = true;
                int padding = 5;
                double scaledMouseX = (mouseX - terminalX - padding) / scale;
                double scaledMouseY = (mouseY - terminalY - padding) / scale;
                selectionStartIndex = getCharIndexAtPosition(scaledMouseX, scaledMouseY);
                selectionEndIndex = selectionStartIndex;
                return true;
            }
        }
        return false;
    }

    public boolean mouseDragged(double mouseX, double mouseY, int button, double deltaX, double deltaY, int screenWidth, int screenHeight, float scale) {
        if (isSelecting) {
            int terminalX = 10;
            int terminalY = MultiTerminalScreen.TAB_HEIGHT + 10;
            int padding = 5;
            double scaledMouseX = (mouseX - terminalX - padding) / scale;
            double scaledMouseY = (mouseY - terminalY - padding) / scale;
            selectionEndIndex = getCharIndexAtPosition(scaledMouseX, scaledMouseY);
            return true;
        }
        return false;
    }

    public boolean mouseReleased(double mouseX, double mouseY, int button, int screenWidth, int screenHeight, float scale) {
        if (isSelecting) {
            isSelecting = false;
            return true;
        }
        return false;
    }

    private int getCharIndexAtPosition(double mouseX, double mouseY) {
        int y = (int) mouseY;
        for (LineInfo lineInfo : lineInfos) {
            if (y >= lineInfo.y && y < lineInfo.y + lineInfo.height) {
                int x = (int) mouseX;
                String lineText = terminalOutput.substring(lineInfo.startIndex, lineInfo.startIndex + lineInfo.length);
                int charIndex = minecraftClient.textRenderer.trimToWidth(lineText, x).length();
                return lineInfo.startIndex + charIndex;
            }
        }
        return terminalOutput.length();
    }

    private static class StyleTextPair {
        final Style style;
        final String text;

        StyleTextPair(Style style, String text) {
            this.style = style;
            this.text = text;
        }
    }

    private static class LineInfo {
        final int startIndex;
        final int length;
        final int y;
        final int height;

        LineInfo(int startIndex, int length, int y, int height) {
            this.startIndex = startIndex;
            this.length = length;
            this.y = y;
            this.height = height;
        }
    }
}
