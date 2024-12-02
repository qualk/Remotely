package redxax.oxy;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.util.InputUtil;
import org.lwjgl.glfw.GLFW;
import java.util.Objects;

public class RemotelyClient implements ClientModInitializer {

    private KeyBinding openTerminalKeyBinding;
    private Process terminalProcess;
    private TerminalScreen terminalScreen;

    @Override
    public void onInitializeClient() {
        System.out.println("Remotely mod initialized on the client.");

        openTerminalKeyBinding = KeyBindingHelper.registerKeyBinding(new KeyBinding(
                "key.remotely.open_terminal",
                InputUtil.Type.KEYSYM,
                GLFW.GLFW_KEY_Z,
                "category.remotely"
        ));

        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            if (client != null && client.player != null) {
                if (openTerminalKeyBinding.wasPressed()) {
                    openTerminalGUI(client);
                }
            }
        });

        Runtime.getRuntime().addShutdownHook(new Thread(this::shutdownTerminal));
    }

    private void openTerminalGUI(MinecraftClient client) {
        try {
            if (terminalProcess == null || !terminalProcess.isAlive()) {
                launchTerminal();
            }

            if (terminalScreen == null || !Objects.equals(client.currentScreen, terminalScreen)) {
                terminalScreen = new TerminalScreen(client, terminalProcess, this);
                client.setScreen(terminalScreen);
            }
        } catch (Exception e) {
            System.err.println("Error opening Terminal GUI: " + e.getMessage());
            e.printStackTrace();
        }
    }

    private void launchTerminal() {
        try {
            ProcessBuilder processBuilder = new ProcessBuilder("cmd.exe", "/k", "powershell");
            processBuilder.redirectErrorStream(true);
            terminalProcess = processBuilder.start();
        } catch (Exception e) {
            System.err.println("Failed to launch terminal process: " + e.getMessage());
            e.printStackTrace();
        }
    }

    public void shutdownTerminal() {
        if (terminalProcess != null && terminalProcess.isAlive()) {
            terminalProcess.destroy();
            terminalProcess = null;
        }

        if (terminalScreen != null) {
            MinecraftClient.getInstance().execute(() -> {
                if (MinecraftClient.getInstance().currentScreen == terminalScreen) {
                    MinecraftClient.getInstance().setScreen(null);
                }
            });
            terminalScreen = null;
        }
    }

    public void onTerminalScreenClosed() {
        terminalScreen = null;
    }
}
