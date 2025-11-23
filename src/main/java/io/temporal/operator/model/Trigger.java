package io.temporal.operator.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.Map;

public class Trigger {

    @JsonProperty("type")
    private String type;

    @JsonProperty("metadata")
    private Map<String, String> metadata;

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

    public String getMetadataValue(String key) {
        return metadata != null ? metadata.get(key) : null;
    }

    public String getMetadataValue(String key, String defaultValue) {
        String value = getMetadataValue(key);
        return value != null ? value : defaultValue;
    }
}
