package com.company.office.model;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class PreflightResult {
    private boolean canRender;
    private List<MappingIssue> issues = new ArrayList<>();
    private Map<String, Object> resolvedContext = new HashMap<>();
    public boolean isCanRender() { return canRender; }
    public void setCanRender(boolean canRender) { this.canRender = canRender; }
    public List<MappingIssue> getIssues() { return issues; }
    public void setIssues(List<MappingIssue> issues) { this.issues = issues; }
    public Map<String, Object> getResolvedContext() { return resolvedContext; }
    public void setResolvedContext(Map<String, Object> resolvedContext) { this.resolvedContext = resolvedContext; }
}
