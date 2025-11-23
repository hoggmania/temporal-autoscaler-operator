package io.temporal.operator.model;

import com.fasterxml.jackson.annotation.JsonProperty;

public class AdvancedConfig {

    @JsonProperty("horizontalPodAutoscalerConfig")
    private HorizontalPodAutoscalerConfig horizontalPodAutoscalerConfig;

    public HorizontalPodAutoscalerConfig getHorizontalPodAutoscalerConfig() {
        return horizontalPodAutoscalerConfig;
    }

    public void setHorizontalPodAutoscalerConfig(HorizontalPodAutoscalerConfig horizontalPodAutoscalerConfig) {
        this.horizontalPodAutoscalerConfig = horizontalPodAutoscalerConfig;
    }

    public static class HorizontalPodAutoscalerConfig {
        @JsonProperty("behavior")
        private Behavior behavior;

        public Behavior getBehavior() {
            return behavior;
        }

        public void setBehavior(Behavior behavior) {
            this.behavior = behavior;
        }
    }

    public static class Behavior {
        @JsonProperty("scaleDown")
        private ScalingBehavior scaleDown;

        @JsonProperty("scaleUp")
        private ScalingBehavior scaleUp;

        public ScalingBehavior getScaleDown() {
            return scaleDown;
        }

        public void setScaleDown(ScalingBehavior scaleDown) {
            this.scaleDown = scaleDown;
        }

        public ScalingBehavior getScaleUp() {
            return scaleUp;
        }

        public void setScaleUp(ScalingBehavior scaleUp) {
            this.scaleUp = scaleUp;
        }
    }

    public static class ScalingBehavior {
        @JsonProperty("stabilizationWindowSeconds")
        private Integer stabilizationWindowSeconds;

        public Integer getStabilizationWindowSeconds() {
            return stabilizationWindowSeconds;
        }

        public void setStabilizationWindowSeconds(Integer stabilizationWindowSeconds) {
            this.stabilizationWindowSeconds = stabilizationWindowSeconds;
        }
    }
}
