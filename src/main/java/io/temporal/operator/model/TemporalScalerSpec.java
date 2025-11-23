package io.temporal.operator.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

public class TemporalScalerSpec {

    @JsonProperty("scaleTargetRef")
    private ScaleTargetRef scaleTargetRef;

    @JsonProperty("pollingInterval")
    private Integer pollingInterval = 5;

    @JsonProperty("cooldownPeriod")
    private Integer cooldownPeriod = 10;

    @JsonProperty("minReplicaCount")
    private Integer minReplicaCount = 0;

    @JsonProperty("maxReplicaCount")
    private Integer maxReplicaCount = 10;

    @JsonProperty("advanced")
    private AdvancedConfig advanced;

    @JsonProperty("triggers")
    private List<Trigger> triggers;

    // Getters and Setters
    public ScaleTargetRef getScaleTargetRef() {
        return scaleTargetRef;
    }

    public void setScaleTargetRef(ScaleTargetRef scaleTargetRef) {
        this.scaleTargetRef = scaleTargetRef;
    }

    public Integer getPollingInterval() {
        return pollingInterval;
    }

    public void setPollingInterval(Integer pollingInterval) {
        this.pollingInterval = pollingInterval;
    }

    public Integer getCooldownPeriod() {
        return cooldownPeriod;
    }

    public void setCooldownPeriod(Integer cooldownPeriod) {
        this.cooldownPeriod = cooldownPeriod;
    }

    public Integer getMinReplicaCount() {
        return minReplicaCount;
    }

    public void setMinReplicaCount(Integer minReplicaCount) {
        this.minReplicaCount = minReplicaCount;
    }

    public Integer getMaxReplicaCount() {
        return maxReplicaCount;
    }

    public void setMaxReplicaCount(Integer maxReplicaCount) {
        this.maxReplicaCount = maxReplicaCount;
    }

    public AdvancedConfig getAdvanced() {
        return advanced;
    }

    public void setAdvanced(AdvancedConfig advanced) {
        this.advanced = advanced;
    }

    public List<Trigger> getTriggers() {
        return triggers;
    }

    public void setTriggers(List<Trigger> triggers) {
        this.triggers = triggers;
    }
}
