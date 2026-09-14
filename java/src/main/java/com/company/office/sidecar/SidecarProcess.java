package com.company.office.sidecar;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

public class SidecarProcess {
    private static final Logger logger = LoggerFactory.getLogger(SidecarProcess.class);

    private final String pythonExecutable;
    private String scriptPath;
    private final IpcClient ipcClient;
    private Process process;
    private Thread stderrThread;

    public SidecarProcess() {
        this("python", "python/main.py");
    }

    public SidecarProcess(String pythonExecutable, String scriptPath) {
        this.pythonExecutable = pythonExecutable;
        this.scriptPath = scriptPath;
        this.ipcClient = new IpcClient();
    }

    public synchronized void start() {
        if (process != null && process.isAlive()) {
            logger.info("Sidecar process is already running.");
            return;
        }

        try {
            File scriptFile = resolveScriptFile(scriptPath);
            File workingDir = scriptFile.getParentFile().getParentFile(); // Root directory containing python/ and app-data/
            if (workingDir == null || !workingDir.exists()) {
                workingDir = new File(".");
            }

            String resolvedPython = resolvePythonExecutable(scriptFile);
            ProcessBuilder pb = new ProcessBuilder(resolvedPython, "-u", scriptFile.getAbsolutePath());
            pb.directory(workingDir);
            pb.environment().put("PYTHONUNBUFFERED", "1");
            pb.environment().put("PYMUPDF_MESSAGE", "fd:2");
            isolatePythonRuntime(pb, resolvedPython);

            logger.info("Starting Python sidecar process: {} in workingDir: {}", scriptFile.getAbsolutePath(), workingDir.getAbsolutePath());
            process = pb.start();

            // Pipe stderr to SLF4J logger
            stderrThread = new Thread(() -> {
                try (BufferedReader errReader = new BufferedReader(new InputStreamReader(process.getErrorStream(), StandardCharsets.UTF_8))) {
                    String errLine;
                    while ((errLine = errReader.readLine()) != null) {
                        logger.warn("[Sidecar STDERR] {}", errLine);
                    }
                } catch (Exception ignored) {}
            }, "sidecar-stderr-reader");
            stderrThread.setDaemon(true);
            stderrThread.start();

            ipcClient.attach(process);

            // Add JVM shutdown hook
            Runtime.getRuntime().addShutdownHook(new Thread(this::stop, "sidecar-shutdown-hook"));

            // Check health with ping
            IpcRequest pingReq = new IpcRequest("ping", null);
            IpcResponse pingRes = ipcClient.send(pingReq, 10, TimeUnit.SECONDS);
            if (pingRes.isSuccess()) {
                logger.info("Python sidecar initialized and verified healthy: {}", pingRes.getData());
            } else {
                throw new IllegalStateException("Python sidecar ping failed: " + pingRes.getError());
            }
        } catch (Exception e) {
            logger.error("Failed to start Python sidecar process", e);
            throw new RuntimeException("Could not launch Python Sidecar", e);
        }
    }

    private void isolatePythonRuntime(ProcessBuilder pb, String resolvedPython) {
        File pythonDir = new File(resolvedPython).getAbsoluteFile().getParentFile();
        if (pythonDir == null) return;

        String currentPath = pb.environment().getOrDefault("PATH", "");
        String javaRuntimeBin = new File(System.getProperty("java.home"), "bin")
                .getAbsolutePath().toLowerCase(Locale.ROOT);
        String filteredPath = Arrays.stream(currentPath.split(";"))
                .map(String::trim)
                .filter(entry -> !entry.isEmpty())
                .filter(entry -> !new File(entry).getAbsolutePath().toLowerCase(Locale.ROOT)
                        .equals(javaRuntimeBin))
                .collect(Collectors.joining(";"));
        pb.environment().put("PATH", pythonDir.getAbsolutePath()
                + (filteredPath.isEmpty() ? "" : ";" + filteredPath));
    }

    private File resolveScriptFile(String path) {
        File file = new File(path);
        if (file.exists()) return file;

        File parentFile = new File("..", path);
        if (parentFile.exists()) return parentFile;

        String appPath = System.getProperty("jpackage.app-path");
        if (appPath != null && !appPath.isBlank()) {
            File packaged = new File(new File(appPath).getParentFile(), "app" + File.separator + path);
            if (packaged.exists()) return packaged;
        }

        // Try absolute search
        File currentDir = new File(".").getAbsoluteFile();
        while (currentDir != null) {
            File test = new File(currentDir, path);
            if (test.exists()) return test;
            currentDir = currentDir.getParentFile();
        }

        return file;
    }

    private String resolvePythonExecutable(File scriptFile) {
        File root = scriptFile.getParentFile() == null ? null : scriptFile.getParentFile().getParentFile();
        if (root != null) {
            File bundled = new File(root, "python-runtime" + File.separator + "python.exe");
            if (bundled.isFile()) return bundled.getAbsolutePath();
        }
        return pythonExecutable;
    }

    public synchronized void stop() {
        ipcClient.close();
        if (process != null && process.isAlive()) {
            logger.info("Stopping Python sidecar process...");
            process.destroy();
            try {
                if (!process.waitFor(3, TimeUnit.SECONDS)) {
                    process.destroyForcibly();
                }
            } catch (InterruptedException e) {
                process.destroyForcibly();
                Thread.currentThread().interrupt();
            }
        }
    }

    public boolean isAlive() {
        return process != null && process.isAlive();
    }

    public IpcClient getIpcClient() {
        return ipcClient;
    }
}
