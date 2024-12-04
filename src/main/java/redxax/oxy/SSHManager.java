package redxax.oxy;

import com.jcraft.jsch.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class SSHManager {

    private Session sshSession;
    private ChannelShell sshChannel;
    private BufferedReader sshReader;
    private Writer sshWriter;
    private boolean isSSH = false;
    private boolean awaitingPassword = false;
    private String sshPassword = "";
    private final TerminalInstance terminalInstance;
    private final ExecutorService executorService = Executors.newFixedThreadPool(2);

    public SSHManager(TerminalInstance terminalInstance) {
        this.terminalInstance = terminalInstance;
    }

    public void startSSHConnection(String command) {
        executorService.submit(() -> {
            try {
                terminalInstance.appendOutput("Connecting...\n");
                String[] parts = command.split(" ");
                if (parts.length < 2) {
                    terminalInstance.appendOutput("Usage: ssh user@host\n");
                    return;
                }
                String userHost = parts[1];
                String[] userHostParts = userHost.split("@");
                if (userHostParts.length != 2) {
                    terminalInstance.appendOutput("Invalid SSH command. Use ssh user@host\n");
                    return;
                }
                String user = userHostParts[0];
                String host = userHostParts[1];

                JSch jsch = new JSch();
                sshSession = jsch.getSession(user, host, 22);
                sshSession.setConfig("StrictHostKeyChecking", "no");
                sshSession.setUserInfo(new SSHUserInfo());
                awaitingPassword = true;
                terminalInstance.appendOutput("Password: ");
            } catch (Exception e) {
                terminalInstance.appendOutput("SSH connection failed: " + e.getMessage() + "\n");
            }
        });
    }

    public void connectSSHWithPassword(String password) {
        executorService.submit(() -> {
            try {
                sshSession.setPassword(password);
                sshSession.connect(10000);
                sshChannel = (ChannelShell) sshSession.openChannel("shell");
                sshChannel.setPty(true);
                sshChannel.connect();

                sshReader = new BufferedReader(new InputStreamReader(sshChannel.getInputStream(), StandardCharsets.UTF_8));
                sshWriter = new OutputStreamWriter(sshChannel.getOutputStream(), StandardCharsets.UTF_8);

                isSSH = true;

                executorService.submit(this::readSSHOutput);

                terminalInstance.appendOutput("Connected.\n");
            } catch (Exception e) {
                terminalInstance.appendOutput("SSH connection failed: " + e.getMessage() + "\n");
                isSSH = false;
                if (sshSession != null && sshSession.isConnected()) {
                    sshSession.disconnect();
                }
            }
        });
    }

    private void readSSHOutput() {
        try {
            String line;
            while (isSSH && (line = sshReader.readLine()) != null) {
                line = line.replace("\u0000", "").replace("\r", "");
                terminalInstance.appendOutput(line + "\n");
            }
        } catch (IOException e) {
            terminalInstance.appendOutput("Error reading SSH output: " + e.getMessage() + "\n");
        }
    }

    public void shutdown() {
        if (sshChannel != null && sshChannel.isConnected()) {
            sshChannel.disconnect();
        }
        if (sshSession != null && sshSession.isConnected()) {
            sshSession.disconnect();
        }
        executorService.shutdownNow();
    }

    public boolean isAwaitingPassword() {
        return awaitingPassword;
    }

    public void setAwaitingPassword(boolean awaitingPassword) {
        this.awaitingPassword = awaitingPassword;
    }

    public String getSshPassword() {
        return sshPassword;
    }

    public void setSshPassword(String sshPassword) {
        this.sshPassword = sshPassword;
    }

    public boolean isSSH() {
        return isSSH;
    }

    public Writer getSshWriter() {
        return sshWriter;
    }

    private class SSHUserInfo implements UserInfo {

        @Override
        public String getPassword() {
            return sshPassword;
        }

        @Override
        public boolean promptYesNo(String message) {
            return true;
        }

        @Override
        public String getPassphrase() {
            return null;
        }

        @Override
        public boolean promptPassphrase(String message) {
            return false;
        }

        @Override
        public boolean promptPassword(String message) {
            return true;
        }

        @Override
        public void showMessage(String message) {
            terminalInstance.appendOutput(message + "\n");
        }
    }
}
