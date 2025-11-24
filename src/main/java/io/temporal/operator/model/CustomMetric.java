package io.temporal.operator.model;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Custom metric configuration for Temporal-based scaling.
 * Allows scaling based on custom metrics in addition to queue backlog.
 * 
 * Supported metric types:
 * - workflow_execution_rate: Rate of workflow executions
 * - workflow_success_rate: Success rate of completed workflows
 * - workflow_latency: Average workflow execution latency
 * - activity_execution_rate: Rate of activity executions
 * - activity_error_rate: Error rate of activities
 * - custom: Custom metrics from Temporal SDK metrics
 */
public class CustomMetric {

    /**
     * Type of custom metric.
     * Options: workflow_execution_rate, workflow_success_rate, workflow_latency,
     *          activity_execution_rate, activity_error_rate, custom
     */
    @JsonProperty("metricType")
    private String metricType;

    /**
     * Name of the custom metric (for 'custom' type).
     */
    @JsonProperty("metricName")
    private String metricName;

    /**
     * Target value for the metric.
     * Scaling logic: desiredReplicas = ceil(currentValue / targetValue)
     */
    @JsonProperty("targetValue")
    private Double targetValue;

    /**
     * Activation threshold - scale from zero when metric exceeds this value.
     */
    @JsonProperty("activationValue")
    private Double activationValue;

    /**
     * Optional: Workflow type filter (for workflow-specific metrics).
     */
    @JsonProperty("workflowType")
    private String workflowType;

    /**
     * Optional: Activity type filter (for activity-specific metrics).
     */
    @JsonProperty("activityType")
    private String activityType;

    /**
     * Optional: Time window for rate calculations (in seconds).
     * Default: 60 seconds
     */
    @JsonProperty("timeWindowSeconds")
    private Integer timeWindowSeconds = 60;

    /**
     * Optional: Aggregation method for the metric.
     * Options: average, sum, max, min, percentile
     * Default: average
     */
    @JsonProperty("aggregation")
    private String aggregation = "average";

    /**
     * Optional: Percentile value (0-100) when aggregation is 'percentile'.
     * Default: 95
     */
    @JsonProperty("percentile")
    private Integer percentile = 95;

    // Getters and Setters

    public String getMetricType() {
        return metricType;
    }

    public void setMetricType(String metricType) {
        this.metricType = metricType;
    }

    public String getMetricName() {
        return metricName;
    }

    public void setMetricName(String metricName) {
        this.metricName = metricName;
    }

    public Double getTargetValue() {
        return targetValue;
    }

    public void setTargetValue(Double targetValue) {
        this.targetValue = targetValue;
    }

    public Double getActivationValue() {
        return activationValue;
    }

    public void setActivationValue(Double activationValue) {
        this.activationValue = activationValue;
    }

    public String getWorkflowType() {
        return workflowType;
    }

    public void setWorkflowType(String workflowType) {
        this.workflowType = workflowType;
    }

    public String getActivityType() {
        return activityType;
    }

    public void setActivityType(String activityType) {
        this.activityType = activityType;
    }

    public Integer getTimeWindowSeconds() {
        return timeWindowSeconds;
    }

    public void setTimeWindowSeconds(Integer timeWindowSeconds) {
        this.timeWindowSeconds = timeWindowSeconds;
    }

    public String getAggregation() {
        return aggregation;
    }

    public void setAggregation(String aggregation) {
        this.aggregation = aggregation;
    }

    public Integer getPercentile() {
        return percentile;
    }

    public void setPercentile(Integer percentile) {
        this.percentile = percentile;
    }
}
