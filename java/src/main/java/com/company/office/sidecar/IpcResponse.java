package com.company.office.sidecar;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.Map;

@JsonIgnoreProperties(ignoreUnknown = true)
public class IpcResponse {
    @JsonProperty("id")
    private String id;

    @JsonProperty("success")
    private boolean success;

    @JsonProperty("data")
    private Map<String, Object> data;

    @JsonProperty("error")
    private String error;

    public IpcResponse() {}

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public boolean isSuccess() { return success; }
    public void setSuccess(boolean success) { this.success = success; }

    public Map<String, Object> getData() { return data; }
    public void setData(Map<String, Object> data) { this.data = data; }

    public String getError() { return error; }
    public void setError(String error) { this.error = error; }
}
