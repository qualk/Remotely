package redxax.oxy;

import redxax.oxy.input.TerminalProcessManager;
import redxax.oxy.servers.ServerState;
import java.io.File;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

public class ServerProcessManager extends TerminalProcessManager {
    private final ServerTerminalInstance serverInstance;

    public ServerProcessManager(ServerTerminalInstance terminalInstance) {
        super(terminalInstance, null);
        this.serverInstance = terminalInstance;
    }

    @Override
    public void launchTerminal() {
        try {
            if (serverInstance.serverInfo.isRemote) {
                if (serverInstance.serverInfo.remoteSSHManager == null) {
                    serverInstance.appendOutput("Remote SSH Manager not initialized.\n");
                    return;
                }
                if (!serverInstance.serverInfo.remoteSSHManager.isSSH()) {
                    serverInstance.appendOutput("SSH connection not established.\n");
                    return;
                }
                serverInstance.serverInfo.remoteSSHManager.launchRemoteServer(serverInstance.serverInfo.path, serverInstance.serverJarPath);
                serverInstance.appendOutput("Remote server launch command sent.\n");
            } else {
                if (terminalProcess != null && terminalProcess.isAlive()) {
                    shutdown();
                }
                if (serverInstance.serverJarPath == null || serverInstance.serverJarPath.isEmpty()) {
                    serverInstance.appendOutput("No server JAR specified.\n");
                    return;
                }
                Path jarPath = Paths.get(serverInstance.serverJarPath);
                if (!Files.exists(jarPath)) {
                    serverInstance.appendOutput("Server JAR not found at: " + serverInstance.serverJarPath + "\n");
                    return;
                }
                File workingDir = jarPath.getParent().toFile();
                ProcessBuilder pb = new ProcessBuilder("java", "-jar", serverInstance.serverJarPath, "--nogui");
                if (workingDir.exists()) {
                    pb.directory(workingDir);
                }
                pb.redirectErrorStream(true);
                terminalProcess = pb.start();
                terminalInputStream = terminalProcess.getInputStream();
                terminalErrorStream = terminalProcess.getErrorStream();
                writer = new OutputStreamWriter(terminalProcess.getOutputStream(), StandardCharsets.UTF_8);
                startReaders();
                serverInstance.appendOutput("Server process started.\n");
            }
        } catch (Exception e) {
            serverInstance.serverInfo.state = ServerState.CRASHED;
            serverInstance.appendOutput("Failed to launch server process: " + e.getMessage() + "\n");
        }
    }
}
