package redxax.oxy.explorer;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.text.Text;
import org.lwjgl.glfw.GLFW;
import redxax.oxy.servers.ServerInfo;
import redxax.oxy.SSHManager;
import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.text.SimpleDateFormat;
import java.util.*;

public class FileExplorerScreen extends Screen implements FileManager.FileManagerCallback {
    private final MinecraftClient minecraftClient;
    private final Screen parent;
    private final ServerInfo serverInfo;
    private List<Path> fileEntries;
    private float smoothOffset = 0;
    private int entryHeight = 25;
    private int baseColor = 0xFF181818;
    private int explorerBgColor = 0xFF242424;
    private int explorerBorderColor = 0xFF555555;
    private int entryBgColor = 0xFF2C2C2C;
    private int entryBorderColor = 0xFF444444;
    private int highlightColor = 0xFF444444;
    private int textColor = 0xFFFFFFFF;
    private Path currentPath;
    private float targetOffset = 0;
    private float scrollSpeed = 0.2f;
    private final SimpleDateFormat dateFormat = new SimpleDateFormat("yyyy-MM-dd HH:mm");
    private List<Path> selectedPaths = new ArrayList<>();
    private int lastSelectedIndex = -1;
    private long lastClickTime = 0;
    private static final int DOUBLE_CLICK_INTERVAL = 500;
    private int lastClickedIndex = -1;
    private Deque<Path> history = new ArrayDeque<>();
    private Deque<Path> forwardHistory = new ArrayDeque<>();
    private boolean searchActive = false;
    private StringBuilder searchQuery = new StringBuilder();
    private int searchBarX = 10;
    private int searchBarY = 0;
    private int searchBarWidth = 200;
    private int searchBarHeight = 20;
    private List<Notification> notifications = new ArrayList<>();
    private final TextRenderer textRenderer;
    private final FileManager fileManager;
    private boolean importMode;
    private static final Set<String> SUPPORTED_EXTENSIONS = Set.of(
            ".txt", ".md", ".json", ".yml", ".yaml", ".conf", ".properties",
            ".xml", ".cfg", ".sk", ".log", ".mcmeta", ".bat", ".sh", ".json5", ".jsonc",
            ".html", ".js", ".java", ".py", ".css", ".vsh", ".fsh", ".glsl", ".nu",
            ".bash", ".fish"
    );

    public FileExplorerScreen(MinecraftClient mc, Screen parent, ServerInfo info) {
        this(mc, parent, info, false);
    }

    public FileExplorerScreen(MinecraftClient mc, Screen parent, ServerInfo info, boolean importMode) {
        super(Text.literal("File Explorer"));
        this.minecraftClient = mc;
        this.parent = parent;
        this.serverInfo = info;
        this.fileEntries = new ArrayList<>();
        this.textRenderer = mc.textRenderer;
        this.fileManager = new FileManager(this, serverInfo);
        this.importMode = importMode;
        if (serverInfo.isRemote) {
            String normalized = serverInfo.path == null ? "" : serverInfo.path.replace("\\", "/").trim();
            if (normalized.isEmpty()) {
                normalized = "/";
            }
            if (!normalized.startsWith("/")) {
                normalized = "/" + normalized;
            }
            this.currentPath = Paths.get(normalized);
        } else {
            this.currentPath = Paths.get(serverInfo.path).toAbsolutePath().normalize();
        }
    }

    @Override
    protected void init() {
        super.init();
        loadDirectory(currentPath);
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        boolean ctrl = (modifiers & GLFW.GLFW_MOD_CONTROL) != 0;
        if (searchActive) {
            if (keyCode == GLFW.GLFW_KEY_ESCAPE) {
                searchActive = false;
                searchQuery.setLength(0);
                loadDirectory(currentPath, false);
                return true;
            }
            if (keyCode == GLFW.GLFW_KEY_BACKSPACE) {
                if (ctrl) {
                    int lastSpace = searchQuery.lastIndexOf(" ");
                    if (lastSpace != -1) {
                        searchQuery.delete(lastSpace, searchQuery.length());
                    } else {
                        searchQuery.setLength(0);
                    }
                } else {
                    if (searchQuery.length() > 0) {
                        searchQuery.deleteCharAt(searchQuery.length() - 1);
                    }
                }
                filterFileEntries();
                return true;
            }
            if (keyCode == GLFW.GLFW_KEY_ENTER) {
                searchActive = false;
                return true;
            }
            return super.keyPressed(keyCode, scanCode, modifiers);
        }
        if (keyCode == this.minecraftClient.options.backKey.getDefaultKey().getCode()) {
            navigateUp();
            return true;
        }
        if (ctrl) {
            if (keyCode == GLFW.GLFW_KEY_C && !serverInfo.isRemote) {
                fileManager.copySelected(selectedPaths);
                showNotification("Copied to clipboard", Notification.Type.INFO);
                return true;
            }
            if (keyCode == GLFW.GLFW_KEY_X && !serverInfo.isRemote) {
                fileManager.cutSelected(selectedPaths);
                showNotification("Cut to clipboard", Notification.Type.INFO);
                return true;
            }
            if (keyCode == GLFW.GLFW_KEY_V && !serverInfo.isRemote) {
                fileManager.paste(currentPath);
                showNotification("Pasted from clipboard", Notification.Type.INFO);
                return true;
            }
            if (keyCode == GLFW.GLFW_KEY_Z && !serverInfo.isRemote) {
                fileManager.undo(currentPath);
                showNotification("Undo action", Notification.Type.INFO);
                return true;
            }
            if (keyCode == GLFW.GLFW_KEY_F) {
                searchActive = true;
                searchQuery.setLength(0);
                searchBarY = this.height - searchBarHeight - 10;
                return true;
            }
        }
        if (keyCode == GLFW.GLFW_KEY_DELETE) {
            fileManager.deleteSelected(selectedPaths, currentPath);
            return true;
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    @Override
    public boolean charTyped(char chr, int modifiers) {
        if (searchActive) {
            if (chr == '\b' || chr == '\n') {
                return false;
            }
            searchQuery.append(chr);
            filterFileEntries();
            return true;
        }
        return super.charTyped(chr, modifiers);
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        boolean handled = false;
        if (searchActive) {
            if (mouseX >= searchBarX && mouseX <= searchBarX + searchBarWidth &&
                    mouseY >= searchBarY && mouseY <= searchBarY + searchBarHeight) {
                handled = true;
            }
        }
        if (!handled) {
            if (button == GLFW.GLFW_MOUSE_BUTTON_1) {
                long currentTime = System.currentTimeMillis();
                boolean isDoubleClick = false;
                if (lastClickedIndex != -1 && (currentTime - lastClickTime) < DOUBLE_CLICK_INTERVAL) {
                    isDoubleClick = true;
                }
                lastClickTime = currentTime;
                int explorerX = 50;
                int explorerY = 60;
                int explorerWidth = this.width - 100;
                int explorerHeight = this.height - 100;
                int backButtonX = this.width - 120;
                int backButtonY = 5;
                int btnW = 50;
                int btnH = 20;
                int closeButtonX = this.width - 60;
                int closeButtonY = 5;
                int closeBtnW = 50;
                int closeBtnH = 20;

                if (mouseX >= backButtonX && mouseX <= backButtonX + btnW && mouseY >= backButtonY && mouseY <= backButtonY + btnH) {
                    navigateUp();
                    return true;
                }
                if (mouseX >= closeButtonX && mouseX <= closeButtonX + closeBtnW && mouseY >= closeButtonY && mouseY <= closeButtonY + closeBtnH) {
                    minecraftClient.setScreen(parent);
                    return true;
                }
                if (mouseX >= explorerX && mouseX <= explorerX + explorerWidth && mouseY >= explorerY && mouseY <= explorerY + explorerHeight) {
                    int relativeY = (int) mouseY - explorerY + (int) smoothOffset;
                    int clickedIndex = relativeY / entryHeight;
                    if (clickedIndex >= 0 && clickedIndex < fileEntries.size()) {
                        Path selectedPath = fileEntries.get(clickedIndex);
                        if (isDoubleClick) {
                            if (isDirectory(selectedPath)) {
                                loadDirectory(selectedPath);
                            } else {
                                if (importMode && selectedPath.getFileName().toString().equalsIgnoreCase("server.jar")) {
                                    String folderName = selectedPath.getParent().getFileName().toString();
                                    if (parent instanceof redxax.oxy.servers.ServerManagerScreen sms) {
                                        sms.importServerJar(selectedPath, folderName);
                                    }
                                    minecraftClient.setScreen(parent);
                                    return true;
                                }
                                if (isSupportedFile(selectedPath)) {
                                    minecraftClient.setScreen(new FileEditorScreen(minecraftClient, this, selectedPath, serverInfo));
                                } else {
                                    showNotification("Unsupported file.", Notification.Type.ERROR);
                                }
                            }
                            lastClickedIndex = -1;
                            return true;
                        } else {
                            if (!serverInfo.isRemote) {
                                boolean ctrlPressed = (GLFW.glfwGetKey(minecraftClient.getWindow().getHandle(), GLFW.GLFW_KEY_LEFT_CONTROL) == GLFW.GLFW_PRESS) ||
                                        (GLFW.glfwGetKey(minecraftClient.getWindow().getHandle(), GLFW.GLFW_KEY_RIGHT_CONTROL) == GLFW.GLFW_PRESS);
                                boolean shiftPressed = (GLFW.glfwGetKey(minecraftClient.getWindow().getHandle(), GLFW.GLFW_KEY_LEFT_SHIFT) == GLFW.GLFW_PRESS) ||
                                        (GLFW.glfwGetKey(minecraftClient.getWindow().getHandle(), GLFW.GLFW_KEY_RIGHT_SHIFT) == GLFW.GLFW_PRESS);
                                if (ctrlPressed) {
                                    if (selectedPaths.contains(selectedPath)) {
                                        selectedPaths.remove(selectedPath);
                                    } else {
                                        selectedPaths.add(selectedPath);
                                    }
                                    lastSelectedIndex = clickedIndex;
                                } else if (shiftPressed && lastSelectedIndex != -1) {
                                    int start = Math.min(lastSelectedIndex, clickedIndex);
                                    int end = Math.max(lastSelectedIndex, clickedIndex);
                                    for (int i = start; i <= end; i++) {
                                        Path path = fileEntries.get(i);
                                        if (!selectedPaths.contains(path)) {
                                            selectedPaths.add(path);
                                        }
                                    }
                                } else {
                                    selectedPaths.clear();
                                    selectedPaths.add(selectedPath);
                                    lastSelectedIndex = clickedIndex;
                                }
                            } else {
                                selectedPaths.clear();
                                selectedPaths.add(selectedPath);
                            }
                            lastClickedIndex = clickedIndex;
                            return true;
                        }
                    }
                }
            } else if (button == GLFW.GLFW_MOUSE_BUTTON_4) {
                navigateUp();
                return true;
            } else if (button == GLFW.GLFW_MOUSE_BUTTON_5) {
                navigateBack();
                return true;
            }
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    private boolean isDirectory(Path p) {
        if (serverInfo.isRemote) {
            if (serverInfo.remoteSSHManager == null || !serverInfo.remoteSSHManager.isSFTPConnected()) {
                return false;
            }
            try {
                String remotePath = p.toString().replace("\\", "/");
                return serverInfo.remoteSSHManager.isRemoteDirectory(remotePath);
            } catch (Exception e) {
                return false;
            }
        }
        return Files.isDirectory(p);
    }

    private void navigateBack() {
        if (!history.isEmpty()) {
            Path previousPath = history.pop();
            forwardHistory.push(currentPath);
            loadDirectory(previousPath, false);
        }
    }

    private void navigateUp() {
        if (serverInfo.isRemote) {
            if (currentPath == null || currentPath.toString().equals("/")) {
                minecraftClient.setScreen(parent);
            } else {
                Path parentPath = currentPath.getParent();
                if (parentPath == null || parentPath.toString().isEmpty()) {
                    currentPath = Paths.get("/");
                    loadDirectory(currentPath);
                } else {
                    loadDirectory(parentPath);
                }
            }
        } else {
            Path parentPath = currentPath.getParent();
            if (parentPath != null && parentPath.startsWith(Paths.get(serverInfo.path).toAbsolutePath().normalize())) {
                loadDirectory(parentPath);
            } else {
                minecraftClient.setScreen(parent);
            }
        }
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double horizontalAmount, double verticalAmount) {
        if (searchActive) {
            return super.mouseScrolled(mouseX, mouseY, horizontalAmount, verticalAmount);
        }
        targetOffset -= verticalAmount * entryHeight * 0.5f;
        targetOffset = Math.max(0, Math.min(targetOffset, Math.max(0, fileEntries.size() * entryHeight - (this.height - 100))));
        return true;
    }

    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        context.fillGradient(0, 0, this.width, this.height, baseColor, baseColor);
        super.render(context, mouseX, mouseY, delta);

        int explorerX = 50;
        int explorerY = 60;
        int explorerWidth = this.width - 100;
        int explorerHeight = this.height - 100;
        context.fill(explorerX, explorerY, explorerX + explorerWidth, explorerY + explorerHeight, explorerBgColor);
        drawInnerBorder(context, explorerX, explorerY, explorerWidth, explorerHeight, explorerBorderColor);

        int headerY = explorerY - 25;
        context.fill(explorerX, headerY, explorerX + explorerWidth, headerY + 25, explorerBgColor);
        drawInnerBorder(context, explorerX, headerY, explorerWidth, 25, explorerBorderColor);

        context.drawText(this.textRenderer, Text.literal("Name"), explorerX + 10, headerY + 5, textColor, false);
        context.drawText(this.textRenderer, Text.literal("Size"), explorerX + 250, headerY + 5, textColor, false);
        context.drawText(this.textRenderer, Text.literal("Created"), explorerX + 350, headerY + 5, textColor, false);

        int titleBarHeight = 30;
        context.fill(0, 0, this.width, titleBarHeight, 0xFF222222);
        drawInnerBorder(context, 0, 0, this.width, titleBarHeight, 0xFF333333);

        String titleText = "File Explorer - " + currentPath;
        if (!serverInfo.isRemote && !currentPath.equals(Paths.get(serverInfo.path).toAbsolutePath().normalize())) {
            titleText += "/";
        }
        context.drawText(this.textRenderer, Text.literal(titleText), 10, 10, textColor, false);

        int backButtonX = this.width - 120;
        int backButtonY = 5;
        int btnW = 50;
        int btnH = 20;
        boolean hoveredBack = mouseX >= backButtonX && mouseX <= backButtonX + btnW && mouseY >= backButtonY && mouseY <= backButtonY + btnH;
        int bgBack = hoveredBack ? highlightColor : explorerBgColor;
        context.fill(backButtonX, backButtonY, backButtonX + btnW, backButtonY + btnH, bgBack);
        drawInnerBorder(context, backButtonX, backButtonY, btnW, btnH, explorerBorderColor);
        int twb = minecraftClient.textRenderer.getWidth("Back");
        int txb = backButtonX + (btnW - twb) / 2;
        int ty = backButtonY + (btnH - minecraftClient.textRenderer.fontHeight) / 2;
        context.drawText(this.textRenderer, Text.literal("Back"), txb, ty, textColor, false);

        int closeButtonX = this.width - 60;
        int closeButtonY = 5;
        int closeBtnW = 50;
        int closeBtnH = 20;
        boolean hoveredClose = mouseX >= closeButtonX && mouseX <= closeButtonX + closeBtnW && mouseY >= closeButtonY && mouseY <= closeButtonY + closeBtnH;
        int bgClose = hoveredClose ? highlightColor : explorerBgColor;
        context.fill(closeButtonX, closeButtonY, closeButtonX + closeBtnW, closeButtonY + closeBtnH, bgClose);
        drawInnerBorder(context, closeButtonX, closeButtonY, closeBtnW, closeBtnH, explorerBorderColor);
        int tcw = minecraftClient.textRenderer.getWidth("Close");
        int tcx = closeButtonX + (closeBtnW - tcw) / 2;
        int tcy = closeButtonY + (closeBtnH - minecraftClient.textRenderer.fontHeight) / 2;
        context.drawText(this.textRenderer, Text.literal("Close"), tcx, tcy, textColor, false);

        smoothOffset += (targetOffset - smoothOffset) * scrollSpeed;
        int visibleEntries = explorerHeight / entryHeight;
        int startIndex = (int) Math.floor(smoothOffset / entryHeight) - 1;
        if (startIndex < 0) startIndex = 0;
        int endIndex = startIndex + visibleEntries + 2;
        if (endIndex > fileEntries.size()) endIndex = fileEntries.size();

        for (int entryIndex = startIndex; entryIndex < endIndex; entryIndex++) {
            Path entry = fileEntries.get(entryIndex);
            int entryY = explorerY + (entryIndex * entryHeight) - (int) smoothOffset;
            float opacity = 1.0f;
            if (entryY < explorerY) {
                float distAbove = explorerY - entryY;
                if (distAbove < 10) {
                    opacity = 1.0f - (distAbove / 10.0f);
                } else {
                    opacity = 0.0f;
                }
            }
            int bottomEdge = entryY + entryHeight;
            if (bottomEdge > explorerY + explorerHeight) {
                float distBelow = bottomEdge - (explorerY + explorerHeight);
                if (distBelow < 10) {
                    float fade = 1.0f - (distBelow / 10.0f);
                    if (fade < opacity) {
                        opacity = fade;
                    }
                } else {
                    opacity = 0.0f;
                }
            }
            if (opacity <= 0.0f) continue;
            boolean hovered = mouseX >= explorerX && mouseX <= explorerX + explorerWidth && mouseY >= entryY && mouseY < entryY + entryHeight;
            boolean isSelected = selectedPaths.contains(entry);
            int bg = isSelected ? 0xFF555555 : (hovered ? highlightColor : entryBgColor);
            int bgWithOpacity = blendColor(bg, opacity);
            int borderWithOpacity = blendColor(entryBorderColor, opacity);
            int textWithOpacity = blendColor(textColor, opacity);
            context.fill(explorerX, entryY, explorerX + explorerWidth, entryY + entryHeight, bgWithOpacity);
            drawInnerBorder(context, explorerX, entryY, explorerWidth, entryHeight, borderWithOpacity);

            String namePrefix = isDirectory(entry) ? "\uD83D\uDDC1" : "\uD83D\uDDC8";
            String displayName = namePrefix + " " + entry.getFileName().toString();
            String size = isDirectory(entry) ? "-" : getFileSize(entry);
            String created = getCreationDate(entry);

            context.drawText(this.textRenderer, Text.literal(displayName), explorerX + 10, entryY + 5, textWithOpacity, false);
            context.drawText(this.textRenderer, Text.literal(size), explorerX + 250, entryY + 5, textWithOpacity, false);
            context.drawText(this.textRenderer, Text.literal(created), explorerX + 350, entryY + 5, textWithOpacity, false);
        }

        context.fillGradient(explorerX, explorerY, explorerX + explorerWidth, explorerY + 10, 0x80000000, 0x00000000);
        context.fillGradient(explorerX, explorerY + explorerHeight - 10, explorerX + explorerWidth, explorerY + explorerHeight, 0x00000000, 0x80000000);

        if (searchActive) {
            context.fill(searchBarX, searchBarY, searchBarX + searchBarWidth, searchBarY + searchBarHeight, 0xFF333333);
            drawInnerBorder(context, searchBarX, searchBarY, searchBarWidth, searchBarHeight, 0xFF555555);
            context.drawText(this.textRenderer, Text.literal(searchQuery.toString()), searchBarX + 5, searchBarY + 5, 0xFFFFFFFF, false);
        }

        updateNotifications(delta);
        renderNotifications(context, mouseX, mouseY, delta);
    }

    public void showNotification(String message, Notification.Type type) {
        notifications.add(new Notification(message, type, this.width, this.height));
    }

    private void updateNotifications(float delta) {
        Iterator<Notification> iterator = notifications.iterator();
        while (iterator.hasNext()) {
            Notification notification = iterator.next();
            notification.update(delta);
            if (notification.isFinished()) {
                iterator.remove();
            }
        }
    }

    private void renderNotifications(DrawContext context, int mouseX, int mouseY, float delta) {
        for (Notification notification : notifications) {
            notification.render(context);
        }
    }

    public void loadDirectory(Path dir) {
        loadDirectory(dir, true);
    }

    private void loadDirectory(Path dir, boolean addToHistory) {
        if (addToHistory && currentPath != null && !currentPath.equals(dir)) {
            history.push(currentPath);
            forwardHistory.clear();
            targetOffset = 0;
        }
        if (addToHistory) {
            targetOffset = 0;
        }
        if (searchActive) {
            searchActive = false;
            searchQuery.setLength(0);
        }
        if (serverInfo.isRemote) {
            if (serverInfo.remoteSSHManager == null) {
                serverInfo.remoteSSHManager = new SSHManager(serverInfo);
                try {
                    serverInfo.remoteSSHManager.connectToRemoteHost(
                            serverInfo.remoteHost.getUser(),
                            serverInfo.remoteHost.ip,
                            serverInfo.remoteHost.port,
                            serverInfo.remoteHost.password
                    );
                    serverInfo.remoteSSHManager.connectSFTP();
                } catch (Exception e) {
                    showNotification("Error connecting to remote host: " + e.getMessage(), Notification.Type.ERROR);
                    return;
                }
            } else if (!serverInfo.remoteSSHManager.isSFTPConnected()) {
                try {
                    serverInfo.remoteSSHManager.connectSFTP();
                } catch (Exception e) {
                    showNotification("Error connecting to SFTP: " + e.getMessage(), Notification.Type.ERROR);
                    return;
                }
            }
            try {
                String remotePath = dir.toString().replace("\\", "/");
                List<String> entries = serverInfo.remoteSSHManager.listRemoteDirectory(remotePath);
                List<Path> realEntries = new ArrayList<>();
                for (String e : entries) {
                    realEntries.add(dir.resolve(e));
                }
                realEntries.sort(Comparator.comparing((Path p) -> !isDirectory(p)).thenComparing(Path::getFileName));
                fileEntries = realEntries;
            } catch (Exception e) {
                showNotification("Error reading remote directory: " + e.getMessage(), Notification.Type.ERROR);
            }
        } else {
            try (DirectoryStream<Path> stream = Files.newDirectoryStream(dir)) {
                List<Path> entries = new ArrayList<>();
                for (Path entry : stream) {
                    entries.add(entry);
                }
                entries.sort(Comparator.comparing((Path p) -> !Files.isDirectory(p)).thenComparing(Path::getFileName));
                fileEntries = entries;
            } catch (IOException e) {
                showNotification("Error reading directory: " + e.getMessage(), Notification.Type.ERROR);
            }
        }
        currentPath = dir;
    }

    private void filterFileEntries() {
        if (searchQuery.length() == 0) {
            loadDirectory(currentPath, false);
            return;
        }
        String query = searchQuery.toString().toLowerCase();
        fileEntries = fileEntries.stream()
                .filter(p -> p.getFileName().toString().toLowerCase().contains(query))
                .sorted(Comparator.comparing((Path p) -> !isDirectory(p)).thenComparing(Path::getFileName))
                .toList();
        targetOffset = 0;
    }

    private boolean isSupportedFile(Path file) {
        String fileName = file.getFileName().toString().toLowerCase();
        return SUPPORTED_EXTENSIONS.stream().anyMatch(fileName::endsWith);
    }

    private String getFileSize(Path file) {
        if (serverInfo.isRemote) {
            return serverInfo.remoteSSHManager != null ? serverInfo.remoteSSHManager.getRemoteFileSize(file.toString()) : "N/A";
        } else {
            try {
                long size = Files.size(file);
                return humanReadableByteCountBin(size);
            } catch (IOException e) {
                return "N/A";
            }
        }
    }

    private String humanReadableByteCountBin(long bytes) {
        if (bytes < 1024) return bytes + " B";
        int exp = (int) (Math.log(bytes) / Math.log(1024));
        String pre = "KMGTPE".charAt(exp - 1) + "B";
        return String.format("%.1f %s", bytes / Math.pow(1024, exp), pre);
    }

    private String getCreationDate(Path file) {
        if (serverInfo.isRemote) {
            return serverInfo.remoteSSHManager != null ? serverInfo.remoteSSHManager.getRemoteFileDate(file.toString()) : "N/A";
        } else {
            try {
                BasicFileAttributes attrs = Files.readAttributes(file, BasicFileAttributes.class);
                return dateFormat.format(attrs.creationTime().toMillis());
            } catch (IOException e) {
                return "N/A";
            }
        }
    }

    private void drawInnerBorder(DrawContext context, int x, int y, int w, int h, int c) {
        context.fill(x, y, x + w, y + 1, c);
        context.fill(x, y + h - 1, x + w, y + h, c);
        context.fill(x, y, x + 1, y + h, c);
        context.fill(x + w - 1, y, x + w, y + h, c);
    }

    private int blendColor(int color, float opacity) {
        int a = (int) ((color >> 24 & 0xFF) * opacity);
        int r = (color >> 16 & 0xFF);
        int g = (color >> 8 & 0xFF);
        int b = (color & 0xFF);
        return (a << 24) | (r << 16) | (g << 8) | b;
    }

    @Override
    public void refreshDirectory(Path path) {
        loadDirectory(path, false);
    }

    public class Notification {
        private final TextRenderer textRenderer = minecraftClient.textRenderer;
        enum Type { INFO, WARN, ERROR }
        private String message;
        private Type type;
        private float x;
        private float y;
        private float targetX;
        private float opacity;
        private float animationSpeed = 30.0f;
        private float fadeOutSpeed = 100.0f;
        private float currentOpacity = 0.0f;
        private float maxOpacity = 1.0f;
        private float duration = 50.0f;
        private float elapsedTime = 0.0f;
        private boolean fadingOut = false;
        private int padding = 10;
        private int width;
        private int height;
        private static final List<Notification> activeNotifications = new ArrayList<>();

        Notification(String message, Type type, int screenWidth, int screenHeight) {
            this.message = message;
            this.type = type;
            this.width = textRenderer.getWidth(message) + 2 * padding;
            this.height = textRenderer.fontHeight + 2 * padding;
            this.x = screenWidth;
            this.y = screenHeight - height - padding - (activeNotifications.size() * (height + padding));
            this.targetX = screenWidth - width - padding;
            this.opacity = 1.0f;
            this.currentOpacity = 1.0f;
            activeNotifications.add(this);
        }

        void update(float delta) {
            if (x > targetX) {
                float move = animationSpeed * delta;
                x -= move;
                if (x < targetX) {
                    x = targetX;
                }
            } else if (!fadingOut) {
                elapsedTime += delta;
                if (elapsedTime >= duration) {
                    fadingOut = true;
                }
            }
            if (fadingOut) {
                currentOpacity -= fadeOutSpeed * delta / 1000.0f;
                if (currentOpacity <= 0.0f) {
                    currentOpacity = 0.0f;
                    activeNotifications.remove(this);
                }
            } else {
                currentOpacity = maxOpacity;
            }
        }

        boolean isFinished() {
            return currentOpacity <= 0.0f;
        }

        void render(DrawContext context) {
            if (currentOpacity <= 0.0f) return;
            int color;
            switch (type) {
                case ERROR -> color = blendColor(0xFFFF5555, currentOpacity);
                case WARN -> color = blendColor(0xFFFFAA55, currentOpacity);
                default -> color = blendColor(0xFF5555FF, currentOpacity);
            }
            context.fill((int) x, (int) y, (int) x + width, (int) y + height, color);
            drawInnerBorder(context, (int) x, (int) y, width, height, blendColor(0xFF000000, currentOpacity));
            context.drawText(this.textRenderer, Text.literal(message), (int) x + padding, (int) y + padding, blendColor(0xFFFFFFFF, currentOpacity), false);
        }

        private int blendColor(int color, float opacity) {
            int a = (int) ((color >> 24 & 0xFF) * opacity);
            int r = (color >> 16 & 0xFF);
            int g = (color >> 8 & 0xFF);
            int b = (color & 0xFF);
            return (a << 24) | (r << 16) | (g << 8) | b;
        }

        private void drawInnerBorder(DrawContext context, int x, int y, int w, int h, int c) {
            context.fill(x, y, x + w, y + 1, c);
            context.fill(x, y + h - 1, x + w, y + h, c);
            context.fill(x, y, x + 1, y + h, c);
            context.fill(x + w - 1, y, x + w, y + h, c);
        }
    }
}
