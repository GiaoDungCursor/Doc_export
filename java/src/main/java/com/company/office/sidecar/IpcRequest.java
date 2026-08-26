package com.company.office.sidecar;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

public class IpcRequest {
    @JsonProperty("id")
    private String id;

    @JsonProperty("action")
    private String action;

    @JsonProperty("input")
    private Map<String, Object> input = new HashMap<>();

    public IpcRequest() {
        this.id = "req-" + UUID.randomUUID().toString().substring(0, 8);
    }

    public IpcRequest(String action, Map<String, Object> input) {
        this();
        this.action = action;
        this.input = input != null ? input : new HashMap<>();
    }

    public IpcRequest(String id, String action, Map<String, Object> input) {
        this.id = id;
        this.action = action;
        this.input = input != null ? input : new HashMap<>();
    }

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public String getAction() { return action; }
    public void setAction(String action) { this.action = action; }

    public Map<String, Object> getInput() { return input; }
    public void setInput(Map<String, Object> input) { this.input = input; }
}
