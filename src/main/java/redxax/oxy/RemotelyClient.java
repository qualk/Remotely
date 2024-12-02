package redxax.oxy;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.util.InputUtil;
import org.lwjgl.glfw.GLFW;

public class RemotelyClient implements ClientModInitializer {

    private static KeyBinding openTerminalKeyBinding;

    @Override
    public void onInitializeClient() {
        System.out.println("Remotely mod initialized on the client.");

        // Register the custom key binding
        openTerminalKeyBinding = KeyBindingHelper.registerKeyBinding(new KeyBinding(
                "Open Terminal", // The translation key of the keybinding's name
                InputUtil.Type.KEYSYM, // The type of the keybinding, KEYSYM for keyboard
                GLFW.GLFW_KEY_Z, // The keycode of the key
                "Reemotely" // The translation key of the keybinding's category
        ));

        // Register a keybinding or GUI opening on the client-side
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            if (client != null && client.player != null) {
                // Open the terminal GUI when the custom key binding is pressed
                if (openTerminalKeyBinding.wasPressed()) {
                    openTerminalGUI(client);
                }
            }
        });
    }

    private void openTerminalGUI(MinecraftClient client) {
        // Launch the terminal if not already running
        if (TerminalScreen.terminalProcess == null || !TerminalScreen.terminalProcess.isAlive()) {
            launchTerminal();
        }
        // Open the terminal screen
        client.setScreen(new TerminalScreen(client, TerminalScreen.terminalProcess));
    }

    private void launchTerminal() {
        try {
            // Launch cmd.exe and run PowerShell
            ProcessBuilder processBuilder = new ProcessBuilder("cmd.exe", "/k", "powershell");
            processBuilder.redirectErrorStream(true);
            TerminalScreen.terminalProcess = processBuilder.start();
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}