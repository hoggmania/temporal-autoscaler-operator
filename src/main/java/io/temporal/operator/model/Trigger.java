package io.temporal.operator.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;
import java.util.Map;

public class Trigger {

    @JsonProperty("type")
    private String type;

    @JsonProperty("metadata")
    private Map<String, String> metadata;

    /**
     * Optional: Custom metrics to use for scaling decisions.
     * When specified, these metrics are evaluated in addition to or instead of queue backlog.
     */
    @JsonProperty("customMetrics")
    private List<CustomMetric> customMetrics;

    // Getters and Setters
    public String getType() {
        return type;
    }

    public void setType(String type) {
        this.type = type;
    }

    public Map<String, String> getMetadata() {
        return metadata;
    }

    public void setMetadata(Map<String, String> metadata) {
        this.metadata = metadata;
    }

    public List<CustomMetric> getCustomMetrics() {
        return customMetrics;
    }

    public void setCustomMetrics(List<CustomMetric> customMetrics) {
        this.customMetrics = customMetrics;
    }

    public String getMetadataValue(String key) {
        return metadata != null ? metadata.get(key) : null;
    }

    public String getMetadataValue(String key, String defaultValue) {
        String value = getMetadataValue(key);
        return value != null ? value : defaultValue;
    }
}
