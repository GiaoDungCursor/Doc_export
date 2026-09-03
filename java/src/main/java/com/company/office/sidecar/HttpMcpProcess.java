package com.company.office.sidecar;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;

public class HttpMcpProcess {
    private static final Logger logger = LoggerFactory.getLogger(HttpMcpProcess.class);
    public static final int PORT = 8765;
    public static final String MCP_URL = "http://127.0.0.1:" + PORT + "/mcp";
    public static final String HEALTH_URL = "http://127.0.0.1:" + PORT + "/health";

    private Process process;
    private boolean ownsProcess;

    public synchronized void start() {
        if (isHealthy()) {
            logger.info("MCP HTTP server is already available at {}", MCP_URL);
            return;
        }
        try {
            File script = resolveScriptFile("python/mcp_server.py");
            File root = script.getParentFile().getParentFile();
            File bundledPython = new File(root, "python-runtime" + File.separator + "python.exe");
            String python = bundledPython.isFile() ? bundledPython.getAbsolutePath() : "python";
            ProcessBuilder builder = new ProcessBuilder(
                    python, "-u", script.getAbsolutePath(), "--http", "--host", "127.0.0.1", "--port", String.valueOf(PORT));
            builder.directory(root);
            builder.environment().put("OFFICE_STUDIO_ROOT", root.getAbsolutePath());
            builder.environment().put("PYTHONUNBUFFERED", "1");
            process = builder.start();
            ownsProcess = true;
            pipe(process.getErrorStream(), "mcp-http-stderr");
            pipe(process.getInputStream(), "mcp-http-stdout");
            for (int attempt = 0; attempt < 40 && !isHealthy(); attempt++) Thread.sleep(250);
            if (!isHealthy()) throw new IllegalStateException("MCP HTTP health check timed out");
            logger.info("MCP HTTP server ready at {}", MCP_URL);
        } catch (Exception e) {
            stop();
            throw new RuntimeException("Could not start MCP HTTP server", e);
        }
    }

    private void pipe(java.io.InputStream stream, String name) {
        Thread thread = new Thread(() -> {
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) logger.info("[MCP HTTP] {}", line);
            } catch (Exception ignored) { }
        }, name);
        thread.setDaemon(true);
        thread.start();
    }

    private File resolveScriptFile(String relative) {
        File direct = new File(relative);
        if (direct.isFile()) return direct.getAbsoluteFile();
        File parent = new File("..", relative);
        if (parent.isFile()) return parent.getAbsoluteFile();
        String appPath = System.getProperty("jpackage.app-path");
        if (appPath != null && !appPath.isBlank()) {
            File packaged = new File(new File(appPath).getParentFile(), "app" + File.separator + relative);
            if (packaged.isFile()) return packaged.getAbsoluteFile();
        }
        throw new IllegalStateException("MCP script not found: " + relative);
    }

    public boolean isHealthy() {
        try {
            HttpURLConnection connection = (HttpURLConnection) URI.create(HEALTH_URL).toURL().openConnection();
            connection.setConnectTimeout(350);
            connection.setReadTimeout(350);
            connection.setRequestMethod("GET");
            int status = connection.getResponseCode();
            connection.disconnect();
            return status == 200;
        } catch (Exception ignored) {
            return false;
        }
    }

    public synchronized void stop() {
        if (ownsProcess && process != null && process.isAlive()) {
            process.destroy();
            try {
                if (!process.waitFor(3, TimeUnit.SECONDS)) process.destroyForcibly();
            } catch (InterruptedException e) {
                process.destroyForcibly();
                Thread.currentThread().interrupt();
            }
        }
        process = null;
        ownsProcess = false;
    }
}
