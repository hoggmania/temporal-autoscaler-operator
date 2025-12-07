package io.temporal.operator.controller;

import io.temporal.operator.model.*;
import io.temporal.operator.temporal.TemporalClientFacade;
import io.temporal.operator.temporal.TemporalClientFacade.TemporalConnectionConfig;
import io.fabric8.kubernetes.api.model.apps.Deployment;
import io.fabric8.kubernetes.api.model.apps.ReplicaSet;
import io.fabric8.kubernetes.api.model.apps.StatefulSet;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.javaoperatorsdk.operator.api.reconciler.ControllerConfiguration;
import io.javaoperatorsdk.operator.api.reconciler.Reconciler;
import io.javaoperatorsdk.operator.api.reconciler.UpdateControl;
import io.javaoperatorsdk.operator.api.reconciler.Context;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.inject.Inject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Main reconciler for TemporalScaler custom resources.
 * 
 * Responsibilities:
 * - Watch TemporalScaler CRs and process triggers
 * - Query Temporal task queues for backlog sizes
 * - Query custom metrics from Temporal (execution rates, latencies, etc.)
 * - Calculate desired replica counts based on queue metrics or custom metrics
 * - Coordinate scaling operations using Kubernetes Leases
 * - Update target workload (Deployment/StatefulSet) replicas
 * - Maintain status and emit metrics
 * 
 * Safety features:
 * - Respects minReplicaCount and maxReplicaCount
 * - Implements cooldown periods to prevent thrashing
 * - Uses stabilization windows from HPA config
 * - Acquires leases before scaling to prevent concurrent modifications
 * - Handles multiple triggers by taking max desired replicas
 * - Supports both queue-based and custom metric-based scaling
 */
@ControllerConfiguration
public class TemporalScalerController implements Reconciler<TemporalScaler> {

    private static final Logger log = LoggerFactory.getLogger(TemporalScalerController.class);

    @Inject
    KubernetesClient kubernetesClient;

    @Inject
    LeaseCoordinator leaseCoordinator;

    @Inject
    TemporalClientFacade temporalClient;

    @Inject
    MeterRegistry meterRegistry;

    // Track last scale times per resource to enforce cooldown
    private final Map<String, Instant> lastScaleTimes = new ConcurrentHashMap<>();

    // Metrics
    private final Map<String, Gauge> queueSizeGauges = new ConcurrentHashMap<>();
    private final Map<String, Counter> scaleUpCounters = new ConcurrentHashMap<>();
    private final Map<String, Counter> scaleDownCounters = new ConcurrentHashMap<>();

    @Override
    public UpdateControl<TemporalScaler> reconcile(TemporalScaler resource, Context<TemporalScaler> context) {
        String resourceKey = getResourceKey(resource);
        log.info("Reconciling TemporalScaler: {}", resourceKey);

        Duration rescheduleAfter = resolvePollingInterval(resource);

        try {
            TemporalScalerSpec spec = resource.getSpec();
            TemporalScalerStatus status = resource.getStatus() != null 
                ? resource.getStatus() 
                : new TemporalScalerStatus();

            // Validate spec
            if (!validateSpec(spec)) {
                updateCondition(status, "Invalid", "True", "ValidationFailed",
                        "Invalid specification: check scaleTargetRef and triggers");
                return patchStatusWithReschedule(resource, rescheduleAfter);
            }

            // Get current replicas from target workload
            Integer currentReplicas = getCurrentReplicas(resource);
            if (currentReplicas == null) {
                updateCondition(status, "Ready", "False", "TargetNotFound", 
                    "Target workload not found");
                return patchStatusWithReschedule(resource, rescheduleAfter);
            }

            status.setCurrentReplicas(currentReplicas);

            // Check if we're in cooldown period
            if (isInCooldown(resourceKey, spec.getCooldownPeriod())) {
                log.debug("Resource {} is in cooldown period, skipping scaling", resourceKey);
                updateCondition(status, "Ready", "True", "InCooldown", "In cooldown period");
                return patchStatusWithReschedule(resource, rescheduleAfter);
            }

            // Calculate desired replicas from all triggers
            int desiredReplicas = calculateDesiredReplicas(resource, currentReplicas, status);

            // Apply min/max constraints
            desiredReplicas = Math.max(spec.getMinReplicaCount(), desiredReplicas);
            desiredReplicas = Math.min(spec.getMaxReplicaCount(), desiredReplicas);

            status.setDesiredReplicas(desiredReplicas);

            // Check if scaling is needed
            if (desiredReplicas != currentReplicas) {
                boolean scaled = performScaling(resource, desiredReplicas, currentReplicas, status);
                if (scaled) {
                    lastScaleTimes.put(resourceKey, Instant.now());
                    status.setLastScaleTime(Instant.now());
                    
                    // Update metrics
                    if (desiredReplicas > currentReplicas) {
                        getScaleUpCounter(resourceKey).increment();
                    } else {
                        getScaleDownCounter(resourceKey).increment();
                    }
                }
            } else {
                updateCondition(status, "Ready", "True", "NoScalingNeeded", 
                    "Current replicas match desired replicas");
            }

            status.setObservedGeneration(resource.getMetadata().getGeneration());
            
            return patchStatusWithReschedule(resource, rescheduleAfter);

        } catch (Exception e) {
            log.error("Error reconciling TemporalScaler {}: {}", resourceKey, e.getMessage(), e);
            
            TemporalScalerStatus status = resource.getStatus() != null 
                ? resource.getStatus() 
                : new TemporalScalerStatus();
            updateCondition(status, "Ready", "False", "ReconciliationError", 
                "Error: " + e.getMessage());
            
            return patchStatusWithReschedule(resource, rescheduleAfter);
        }
    }

    /**
     * Calculate desired replicas based on all triggers.
     */
    private int calculateDesiredReplicas(TemporalScaler resource, int currentReplicas, 
                                        TemporalScalerStatus status) {
        TemporalScalerSpec spec = resource.getSpec();
        String resourceKey = getResourceKey(resource);
        
        int maxDesired = currentReplicas;
        boolean hasValidMetric = false;

        for (Trigger trigger : spec.getTriggers()) {
            if (!"temporal".equals(trigger.getType())) {
                continue;
            }

            try {
                // Build Temporal connection config from trigger metadata
                TemporalConnectionConfig config = buildTemporalConfig(trigger, resource.getMetadata().getNamespace());

                int triggerDesired = currentReplicas;

                // Check if trigger has custom metrics
                if (trigger.getCustomMetrics() != null && !trigger.getCustomMetrics().isEmpty()) {
                    // Process custom metrics
                    triggerDesired = calculateReplicasFromCustomMetrics(
                        trigger, config, currentReplicas, resourceKey);
                    hasValidMetric = true;
                } else {
                    // Default: use queue backlog
                    long queueSize = temporalClient.getTaskQueueSize(config);
                    
                    if (queueSize < 0) {
                        log.warn("Failed to get queue size for trigger, skipping");
                        continue;
                    }

                    hasValidMetric = true;

                    // Update metrics
                    String metricKey = resourceKey + "-" + config.getTaskQueue();
                    updateQueueSizeGauge(metricKey, queueSize);

                    // Parse target queue size
                    int targetQueueSize = Integer.parseInt(
                        trigger.getMetadataValue("targetQueueSize", "1"));
                    
                    // Parse activation target
                    int activationTarget = Integer.parseInt(
                        trigger.getMetadataValue("activationTargetQueueSize", "0"));

                    // Calculate desired replicas for this trigger
                    triggerDesired = calculateReplicasForTrigger(
                        queueSize, targetQueueSize, activationTarget, currentReplicas);

                    log.debug("Trigger {} queue size: {}, target: {}, desired replicas: {}", 
                        config.getTaskQueue(), queueSize, targetQueueSize, triggerDesired);
                }

                // Take max across all triggers
                maxDesired = Math.max(maxDesired, triggerDesired);

            } catch (Exception e) {
                log.error("Error processing trigger: {}", e.getMessage(), e);
            }
        }

        if (!hasValidMetric) {
            log.warn("No valid metrics obtained, keeping current replicas");
            updateCondition(status, "Ready", "False", "NoMetrics", 
                "Failed to obtain metrics from Temporal");
            return currentReplicas;
        }

        // Apply stabilization window if scaling down
        if (maxDesired < currentReplicas) {
            if (!shouldScaleDown(resource, maxDesired, currentReplicas)) {
                log.debug("Scale down blocked by stabilization window");
                return currentReplicas;
            }
        }

        return maxDesired;
    }

    /**
     * Calculate desired replicas based on custom metrics.
     */
    private int calculateReplicasFromCustomMetrics(Trigger trigger, 
                                                   TemporalConnectionConfig config,
                                                   int currentReplicas,
                                                   String resourceKey) {
        int maxDesired = currentReplicas;

        for (CustomMetric metric : trigger.getCustomMetrics()) {
            try {
                double metricValue = temporalClient.getCustomMetricValue(config, metric);
                
                if (metricValue < 0) {
                    log.warn("Failed to get custom metric {}, skipping", metric.getMetricType());
                    continue;
                }

                // Calculate desired replicas based on metric
                int desired = calculateReplicasForCustomMetric(
                    metricValue, metric, currentReplicas);

                log.debug("Custom metric {} value: {}, target: {}, desired replicas: {}", 
                    metric.getMetricType(), metricValue, metric.getTargetValue(), desired);

                // Take max across all metrics
                maxDesired = Math.max(maxDesired, desired);

            } catch (Exception e) {
                log.error("Error processing custom metric {}: {}", 
                    metric.getMetricType(), e.getMessage(), e);
            }
        }

        return maxDesired;
    }

    /**
     * Calculate desired replicas for a custom metric.
     */
    private int calculateReplicasForCustomMetric(double metricValue, 
                                                CustomMetric metric,
                                                int currentReplicas) {
        // Handle scale-from-zero activation
        if (currentReplicas == 0) {
            if (metric.getActivationValue() != null && metricValue >= metric.getActivationValue()) {
                // Activate: calculate initial replicas
                return Math.max(1, (int) Math.ceil(metricValue / metric.getTargetValue()));
            } else {
                return 0;
            }
        }

        // Normal scaling logic
        if (metricValue == 0) {
            // Scale to zero if allowed
            return 0;
        }

        // Calculate replicas needed: ceil(metricValue / targetValue)
        return (int) Math.ceil(metricValue / metric.getTargetValue());
    }

    /**
     * Calculate desired replicas for a single trigger (queue-based).
     */
    private int calculateReplicasForTrigger(long queueSize, int targetQueueSize, 
                                           int activationTarget, int currentReplicas) {
        // Handle scale-from-zero activation
        if (currentReplicas == 0) {
            if (queueSize >= activationTarget) {
                // Activate: calculate initial replicas
                return Math.max(1, (int) Math.ceil((double) queueSize / targetQueueSize));
            } else {
                return 0;
            }
        }

        // Normal scaling logic
        if (queueSize == 0) {
            // Scale to zero if allowed
            return 0;
        }

        // Calculate replicas needed: ceil(queueSize / targetQueueSize)
        return (int) Math.ceil((double) queueSize / targetQueueSize);
    }

    /**
     * Check if scale down should be blocked by stabilization window.
     */
    private boolean shouldScaleDown(TemporalScaler resource, int desired, int current) {
        TemporalScalerSpec spec = resource.getSpec();
        
        if (spec.getAdvanced() != null 
            && spec.getAdvanced().getHorizontalPodAutoscalerConfig() != null
            && spec.getAdvanced().getHorizontalPodAutoscalerConfig().getBehavior() != null) {
            
            var scaleDown = spec.getAdvanced().getHorizontalPodAutoscalerConfig()
                .getBehavior().getScaleDown();
            
            if (scaleDown != null && scaleDown.getStabilizationWindowSeconds() != null) {
                int windowSeconds = scaleDown.getStabilizationWindowSeconds();
                
                String resourceKey = getResourceKey(resource);
                Instant lastScale = lastScaleTimes.get(resourceKey);
                
                if (lastScale != null) {
                    Instant now = Instant.now();
                    long secondsSinceLastScale = Duration.between(lastScale, now).getSeconds();
                    
                    if (secondsSinceLastScale < windowSeconds) {
                        return false;
                    }
                }
            }
        }
        
        return true;
    }

    /**
     * Perform the actual scaling operation with lease coordination.
     */
    private boolean performScaling(TemporalScaler resource, int desiredReplicas, 
                                  int currentReplicas, TemporalScalerStatus status) {
        String resourceKey = getResourceKey(resource);
        String namespace = resource.getMetadata().getNamespace();
        ScaleTargetRef target = resource.getSpec().getScaleTargetRef();

        // Generate lease name
        String leaseName = LeaseCoordinator.generateLeaseName(
            resource.getMetadata().getName(), target.getName());

        // Try to acquire lease
        boolean leaseAcquired = leaseCoordinator.acquireLease(
            namespace, leaseName, Duration.ofSeconds(30));

        if (!leaseAcquired) {
            log.info("Could not acquire lease for {}, another instance may be scaling", resourceKey);
            updateCondition(status, "Ready", "True", "LeaseNotAcquired", 
                "Another operator instance is handling scaling");
            return false;
        }

        try {
            // Patch the target workload
            boolean success = patchTargetReplicas(resource, desiredReplicas);

            if (success) {
                String action = desiredReplicas > currentReplicas ? "up" : "down";
                log.info("Scaled {} from {} to {} replicas", resourceKey, currentReplicas, desiredReplicas);
                updateCondition(status, "Ready", "True", "Scaled" + capitalize(action), 
                    String.format("Scaled %s from %d to %d replicas", action, currentReplicas, desiredReplicas));
                return true;
            } else {
                updateCondition(status, "Ready", "False", "ScalingFailed", 
                    "Failed to patch target workload");
                return false;
            }

        } finally {
            // Release the lease
            leaseCoordinator.releaseLease(namespace, leaseName);
        }
    }

    /**
     * Patch target workload replicas.
     */
    private boolean patchTargetReplicas(TemporalScaler resource, int replicas) {
        ScaleTargetRef target = resource.getSpec().getScaleTargetRef();
        String namespace = resource.getMetadata().getNamespace();

        try {
            switch (target.getKind().toLowerCase()) {
                case "deployment":
                    Deployment deployment = kubernetesClient.apps().deployments()
                        .inNamespace(namespace)
                        .withName(target.getName())
                        .get();
                    
                    if (deployment != null) {
                        deployment.getSpec().setReplicas(replicas);
                        kubernetesClient.apps().deployments()
                            .inNamespace(namespace)
                            .resource(deployment)
                            .update();
                        return true;
                    }
                    break;

                case "statefulset":
                    StatefulSet statefulSet = kubernetesClient.apps().statefulSets()
                        .inNamespace(namespace)
                        .withName(target.getName())
                        .get();
                    
                    if (statefulSet != null) {
                        statefulSet.getSpec().setReplicas(replicas);
                        kubernetesClient.apps().statefulSets()
                            .inNamespace(namespace)
                            .resource(statefulSet)
                            .update();
                        return true;
                    }
                    break;

                case "replicaset":
                    ReplicaSet replicaSet = kubernetesClient.apps().replicaSets()
                        .inNamespace(namespace)
                        .withName(target.getName())
                        .get();
                    
                    if (replicaSet != null) {
                        replicaSet.getSpec().setReplicas(replicas);
                        kubernetesClient.apps().replicaSets()
                            .inNamespace(namespace)
                            .resource(replicaSet)
                            .update();
                        return true;
                    }
                    break;

                default:
                    log.error("Unsupported target kind: {}", target.getKind());
                    return false;
            }

            log.error("Target workload not found: {}/{}", target.getKind(), target.getName());
            return false;

        } catch (Exception e) {
            log.error("Failed to patch target replicas: {}", e.getMessage(), e);
            return false;
        }
    }

    /**
     * Get current replicas from target workload.
     */
    private Integer getCurrentReplicas(TemporalScaler resource) {
        ScaleTargetRef target = resource.getSpec().getScaleTargetRef();
        String namespace = resource.getMetadata().getNamespace();

        try {

            switch (target.getKind().toLowerCase()) {
                case "deployment":
                    Deployment deployment = kubernetesClient.apps().deployments()
                        .inNamespace(namespace)
                        .withName(target.getName())
                        .get();
                    return deployment != null ? deployment.getSpec().getReplicas() : null;

                case "statefulset":
                    StatefulSet statefulSet = kubernetesClient.apps().statefulSets()
                        .inNamespace(namespace)
                        .withName(target.getName())
                        .get();
                    return statefulSet != null ? statefulSet.getSpec().getReplicas() : null;

                case "replicaset":
                    ReplicaSet replicaSet = kubernetesClient.apps().replicaSets()
                        .inNamespace(namespace)
                        .withName(target.getName())
                        .get();
                    return replicaSet != null ? replicaSet.getSpec().getReplicas() : null;

                default:
                    log.error("Unsupported target kind: {}", target.getKind());
                    return null;
            }
        } catch (Exception e) {
            log.error("Failed to get current replicas: {}", e.getMessage(), e);
            return null;
        }
    }

    /**
     * Build Temporal connection config from trigger metadata.
     */
    private TemporalConnectionConfig buildTemporalConfig(Trigger trigger, String defaultNamespace) {
        TemporalConnectionConfig config = new TemporalConnectionConfig();

        // Endpoint
        String endpoint = trigger.getMetadataValue("endpoint");
        if (endpoint == null) {
            String endpointEnv = trigger.getMetadataValue("endpointFromEnv");
            if (endpointEnv != null) {
                endpoint = System.getenv(endpointEnv);
            }
        }
        config.setEndpoint(endpoint);

        // Namespace
        config.setNamespace(trigger.getMetadataValue("namespace", defaultNamespace));

        // Task queue
        config.setTaskQueue(trigger.getMetadataValue("taskQueue"));

        // Queue types
        config.setQueueTypes(trigger.getMetadataValue("queueTypes", "workflow"));

        // API Key
        String apiKey = trigger.getMetadataValue("apiKey");
        if (apiKey == null) {
            String apiKeyEnv = trigger.getMetadataValue("apiKeyFromEnv");
            if (apiKeyEnv != null) {
                apiKey = System.getenv(apiKeyEnv);
            }
        }
        config.setApiKey(apiKey);

        // mTLS config
        config.setCaCert(trigger.getMetadataValue("ca"));
        config.setClientCert(trigger.getMetadataValue("cert"));
        config.setClientKey(trigger.getMetadataValue("key"));
        config.setKeyPassword(trigger.getMetadataValue("keyPassword"));
        config.setTlsServerName(trigger.getMetadataValue("tlsServerName"));

        // SSL config
        String unsafeSsl = trigger.getMetadataValue("unsafeSsl", "false");
        config.setUnsafeSsl("true".equalsIgnoreCase(unsafeSsl));

        // Timeout
        String timeoutStr = trigger.getMetadataValue("minConnectTimeout");
        if (timeoutStr != null) {
            try {
                config.setMinConnectTimeout(Duration.ofSeconds(Long.parseLong(timeoutStr)));
            } catch (NumberFormatException e) {
                log.warn("Invalid minConnectTimeout: {}", timeoutStr);
            }
        }

        return config;
    }

    // Helper methods

    private boolean validateSpec(TemporalScalerSpec spec) {
        if (spec.getScaleTargetRef() == null || spec.getTriggers() == null || spec.getTriggers().isEmpty()) {
            return false;
        }
        return true;
    }

    private boolean isInCooldown(String resourceKey, int cooldownSeconds) {
        Instant lastScale = lastScaleTimes.get(resourceKey);
        if (lastScale == null) {
            return false;
        }
        
        Instant now = Instant.now();
        long secondsSinceLastScale = Duration.between(lastScale, now).getSeconds();
        return secondsSinceLastScale < cooldownSeconds;
    }

    private void updateCondition(TemporalScalerStatus status, String type, String conditionStatus, 
                                 String reason, String message) {
        var condition = new TemporalScalerStatus.Condition(type, conditionStatus, reason, message);
        
        // Remove existing condition of same type
        status.getConditions().removeIf(c -> type.equals(c.getType()));
        status.getConditions().add(condition);
    }

    private String getResourceKey(TemporalScaler resource) {
        return resource.getMetadata().getNamespace() + "/" + resource.getMetadata().getName();
    }

    private String capitalize(String str) {
        if (str == null || str.isEmpty()) {
            return str;
        }
        return str.substring(0, 1).toUpperCase() + str.substring(1);
    }

    // Metrics helpers

    private void updateQueueSizeGauge(String key, long size) {
        queueSizeGauges.computeIfAbsent(key, k -> 
            Gauge.builder("temporal_queue_size", () -> size)
                .tag("scaler", k)
                .register(meterRegistry)
        );
    }

    private Counter getScaleUpCounter(String key) {
        return scaleUpCounters.computeIfAbsent(key, k ->
            Counter.builder("temporal_scaler_scale_up_total")
                .tag("scaler", k)
                .register(meterRegistry)
        );
    }

    private Counter getScaleDownCounter(String key) {
        return scaleDownCounters.computeIfAbsent(key, k ->
            Counter.builder("temporal_scaler_scale_down_total")
                .tag("scaler", k)
                .register(meterRegistry)
        );
    }

    private UpdateControl<TemporalScaler> patchStatusWithReschedule(TemporalScaler resource, Duration delay) {
        return UpdateControl.patchStatus(resource).rescheduleAfter(delay);
    }

    private Duration resolvePollingInterval(TemporalScaler resource) {
        TemporalScalerSpec spec = resource.getSpec();
        int intervalSeconds = 5;
        if (spec != null && spec.getPollingInterval() != null && spec.getPollingInterval() > 0) {
            intervalSeconds = spec.getPollingInterval();
        }
        return Duration.ofSeconds(intervalSeconds);
    }

}
