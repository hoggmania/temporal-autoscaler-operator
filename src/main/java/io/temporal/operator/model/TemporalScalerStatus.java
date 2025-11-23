package io.temporal.operator.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

public class TemporalScalerStatus {

    @JsonProperty("conditions")
    private List<Condition> conditions = new ArrayList<>();

    @JsonProperty("currentReplicas")
    private Integer currentReplicas;

    @JsonProperty("desiredReplicas")
    private Integer desiredReplicas;

    @JsonProperty("lastScaleTime")
    private String lastScaleTime;

    @JsonProperty("observedGeneration")
    private Long observedGeneration;

    // Getters and Setters
    public List<Condition> getConditions() {
        return conditions;
    }

    public void setConditions(List<Condition> conditions) {
        this.conditions = conditions;
    }

    public Integer getCurrentReplicas() {
        return currentReplicas;
    }

    public void setCurrentReplicas(Integer currentReplicas) {
        this.currentReplicas = currentReplicas;
    }

    public Integer getDesiredReplicas() {
        return desiredReplicas;
    }

    public void setDesiredReplicas(Integer desiredReplicas) {
        this.desiredReplicas = desiredReplicas;
    }

    public String getLastScaleTime() {
        return lastScaleTime;
    }

    public void setLastScaleTime(String lastScaleTime) {
        this.lastScaleTime = lastScaleTime;
    }

    public void setLastScaleTime(Instant instant) {
        this.lastScaleTime = instant.toString();
    }

    public Long getObservedGeneration() {
        return observedGeneration;
    }

    public void setObservedGeneration(Long observedGeneration) {
        this.observedGeneration = observedGeneration;
    }

    public static class Condition {
        @JsonProperty("type")
        private String type;

        @JsonProperty("status")
        private String status;

        @JsonProperty("lastTransitionTime")
        private String lastTransitionTime;

        @JsonProperty("reason")
        private String reason;

        @JsonProperty("message")
        private String message;

        public Condition() {}

        public Condition(String type, String status, String reason, String message) {
            this.type = type;
            this.status = status;
            this.lastTransitionTime = Instant.now().toString();
            this.reason = reason;
            this.message = message;
        }

        // Getters and Setters
        public String getType() {
            return type;
        }

        public void setType(String type) {
            this.type = type;
        }

        public String getStatus() {
            return status;
        }

        public void setStatus(String status) {
            this.status = status;
        }

        public String getLastTransitionTime() {
            return lastTransitionTime;
        }

        public void setLastTransitionTime(String lastTransitionTime) {
            this.lastTransitionTime = lastTransitionTime;
        }

        public String getReason() {
            return reason;
        }

        public void setReason(String reason) {
            this.reason = reason;
        }

        public String getMessage() {
            return message;
        }

        public void setMessage(String message) {
            this.message = message;
        }
    }
}
