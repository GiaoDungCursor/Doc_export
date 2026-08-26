package com.company.office.sidecar;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.*;

public class IpcClient {
    private static final Logger logger = LoggerFactory.getLogger(IpcClient.class);
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final ConcurrentHashMap<String, CompletableFuture<IpcResponse>> pendingRequests = new ConcurrentHashMap<>();

    private BufferedWriter writer;
    private BufferedReader reader;
    private Thread listenerThread;
    private volatile boolean running = false;

    public void attach(Process process) {
        this.writer = new BufferedWriter(new OutputStreamWriter(process.getOutputStream(), StandardCharsets.UTF_8));
        this.reader = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8));
        this.running = true;

        this.listenerThread = new Thread(this::listenLoop, "sidecar-ipc-reader");
        this.listenerThread.setDaemon(true);
        this.listenerThread.start();
    }

    public CompletableFuture<IpcResponse> sendAsync(IpcRequest request) {
        CompletableFuture<IpcResponse> future = new CompletableFuture<>();
        if (!running || writer == null) {
            future.completeExceptionally(new IllegalStateException("IPC Client is not connected to Python Sidecar"));
            return future;
        }

        pendingRequests.put(request.getId(), future);

        try {
            String jsonLine = objectMapper.writeValueAsString(request);
            synchronized (this) {
                writer.write(jsonLine);
                writer.newLine();
                writer.flush();
            }
            logger.debug("Sent IPC request [{}]: action={}", request.getId(), request.getAction());
        } catch (IOException e) {
            pendingRequests.remove(request.getId());
            future.completeExceptionally(e);
        }

        return future;
    }

    public IpcResponse send(IpcRequest request, long timeout, TimeUnit unit) throws Exception {
        return sendAsync(request).get(timeout, unit);
    }

    private void listenLoop() {
        try {
            String line;
            while (running && (line = reader.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty()) continue;

                // If line contains non-JSON warning, skip or check if it starts with {
                if (!line.startsWith("{") || !line.endsWith("}")) {
                    logger.debug("Non-JSON stdout from Python sidecar: {}", line);
                    continue;
                }

                try {
                    IpcResponse response = objectMapper.readValue(line, IpcResponse.class);
                    String reqId = response.getId();
                    CompletableFuture<IpcResponse> future = pendingRequests.remove(reqId);
                    if (future != null) {
                        future.complete(response);
                    } else {
                        logger.warn("Received response for unknown request ID: {}", reqId);
                    }
                } catch (Exception e) {
                    logger.error("Failed to parse JSON response from sidecar: {}", line, e);
                }
            }
        } catch (IOException e) {
            if (running) {
                logger.error("IPC stdout stream closed with error", e);
            }
        } finally {
            close();
        }
    }

    public void close() {
        running = false;
        // Fail any remaining pending requests
        for (CompletableFuture<IpcResponse> future : pendingRequests.values()) {
            future.completeExceptionally(new IOException("IPC connection terminated"));
        }
        pendingRequests.clear();
    }
}
