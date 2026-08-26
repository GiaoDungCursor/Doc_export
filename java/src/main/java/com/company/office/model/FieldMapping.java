package com.company.office.model;

public class FieldMapping {
    private String target;
    private String source;
    private String transform;
    private Object fallback;
    private boolean required;
    private double confidence;
    private MappingOrigin origin = MappingOrigin.RULE;

    public FieldMapping() {}
    public FieldMapping(String target, String source, boolean required, double confidence, MappingOrigin origin) {
        this.target = target; this.source = source; this.required = required;
        this.confidence = confidence; this.origin = origin;
    }
    public String getTarget() { return target; }
    public void setTarget(String target) { this.target = target; }
    public String getSource() { return source; }
    public void setSource(String source) { this.source = source; }
    public String getTransform() { return transform; }
    public void setTransform(String transform) { this.transform = transform; }
    public Object getFallback() { return fallback; }
    public void setFallback(Object fallback) { this.fallback = fallback; }
    public boolean isRequired() { return required; }
    public void setRequired(boolean required) { this.required = required; }
    public double getConfidence() { return confidence; }
    public void setConfidence(double confidence) { this.confidence = confidence; }
    public MappingOrigin getOrigin() { return origin; }
    public void setOrigin(MappingOrigin origin) { this.origin = origin; }
}
