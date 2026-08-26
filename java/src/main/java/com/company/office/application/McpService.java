package com.company.office.application;

import com.company.office.mcp.McpServer;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

public class McpService {
    private final McpServer mcpServer;

    public McpService(McpServer mcpServer) {
        this.mcpServer = mcpServer;
    }

    public List<Map<String, Object>> getAvailableTools() {
        return mcpServer.getRegisteredTools();
    }

    public CompletableFuture<Map<String, Object>> callTool(String toolName, Map<String, Object> arguments) {
        return mcpServer.executeTool(toolName, arguments);
    }
}
