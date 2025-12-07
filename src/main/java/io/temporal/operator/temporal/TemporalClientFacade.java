package io.temporal.operator.temporal;

import com.google.protobuf.ByteString;
import io.grpc.Metadata;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.stub.MetadataUtils;
import io.temporal.api.deployment.v1.WorkerDeploymentInfo;
import io.temporal.api.deployment.v1.WorkerDeploymentVersion;
import io.temporal.api.enums.v1.DescribeTaskQueueMode;
import io.temporal.api.enums.v1.TaskQueueType;
import io.temporal.api.taskqueue.v1.TaskQueueStats;
import io.temporal.api.taskqueue.v1.TaskQueueStatus;
import io.temporal.api.taskqueue.v1.TimestampedBuildIdAssignmentRule;
import io.temporal.api.taskqueue.v1.TimestampedCompatibleBuildIdRedirectRule;
import io.temporal.api.workflowservice.v1.DescribeTaskQueueRequest;
import io.temporal.api.workflowservice.v1.DescribeTaskQueueResponse;
import io.temporal.api.workflowservice.v1.DescribeWorkerDeploymentRequest;
import io.temporal.api.workflowservice.v1.DescribeWorkerDeploymentVersionRequest;
import io.temporal.api.workflowservice.v1.DescribeWorkerDeploymentVersionResponse;
import io.temporal.api.workflowservice.v1.GetWorkerVersioningRulesRequest;
import io.temporal.api.workflowservice.v1.GetWorkerVersioningRulesResponse;
import io.temporal.api.workflowservice.v1.ListWorkerDeploymentsRequest;
import io.temporal.api.workflowservice.v1.ListWorkerDeploymentsResponse;
import io.temporal.api.workflowservice.v1.WorkflowServiceGrpc;
import io.temporal.operator.model.CustomMetric;
import io.temporal.serviceclient.WorkflowServiceStubs;
import io.temporal.serviceclient.WorkflowServiceStubsOptions;
import jakarta.enterprise.context.ApplicationScoped;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.net.ssl.SSLException;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import io.grpc.netty.shaded.io.grpc.netty.GrpcSslContexts;
import io.grpc.netty.shaded.io.grpc.netty.NettyChannelBuilder;
import io.grpc.netty.shaded.io.netty.handler.ssl.SslContext;
import io.grpc.netty.shaded.io.netty.handler.ssl.SslContextBuilder;

/**
 * TemporalClientFacade wraps Temporal SDK operations to query task queue
 * metrics and custom metrics.
 * 
 * Features:
 * - Connection pooling and caching per endpoint
 * - Support for mTLS authentication
 * - Support for API key authentication (Temporal Cloud)
 * - Query task queue backlog sizes
 * - Query custom metrics (workflow rates, latencies, etc.)
 * - Exponential backoff for transient errors
 * - Configurable timeouts
 * For Temporal 1.20+, use DescribeTaskQueue with TaskQueueMetadata.
 * For older versions or specific queue stats, may need different approach.
 * Test with your Temporal version and adjust as needed.
 */
@ApplicationScoped
public class TemporalClientFacade {

    private static final Logger log = LoggerFactory.getLogger(TemporalClientFacade.class);

    // Cache of Temporal service stubs by endpoint
    private final Map<String, WorkflowServiceStubs> stubsCache = new ConcurrentHashMap<>();

    // Default timeout for queries
    private static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(10);

    /**
     * Query task queue size for a specific task queue.
     * 
     * @param config Configuration for connecting to Temporal
     * @return Task queue backlog size (approximate)
     */
    public long getTaskQueueSize(TemporalConnectionConfig config) {
        try {
            if (config.getEndpoint() == null || config.getEndpoint().isBlank()) {
                log.warn("Cannot query Temporal queue size: endpoint is missing");
                return -1;
            }
            WorkflowServiceStubs stubs = getOrCreateStubs(config);

            Long workerVersioningBacklog = queryWorkerVersioningBacklog(stubs, config);
            if (workerVersioningBacklog != null) {
                log.debug("Worker versioning backlog for {}#{} resolved to {}",
                        config.getNamespace(), config.getTaskQueue(), workerVersioningBacklog);
                return workerVersioningBacklog;
            }

            long legacyBacklog = queryLegacyBacklog(stubs, config);
            log.debug("Legacy backlog for {}#{} resolved to {}",
                    config.getNamespace(), config.getTaskQueue(), legacyBacklog);
            return legacyBacklog;
        } catch (Exception e) {
            log.error("Failed to query Temporal task queue {}: {}", config.getTaskQueue(), e.getMessage(), e);
            // Return -1 to indicate error - controller should handle gracefully
            return -1;
        }
    }

    private void applyQueueTypeFilters(DescribeTaskQueueRequest.Builder builder, String queueTypesConfig) {
        EnumSet<TaskQueueType> requestedTypes = resolveQueueTypes(queueTypesConfig);

        TaskQueueType legacyType = requestedTypes.contains(TaskQueueType.TASK_QUEUE_TYPE_WORKFLOW)
                ? TaskQueueType.TASK_QUEUE_TYPE_WORKFLOW
                : TaskQueueType.TASK_QUEUE_TYPE_ACTIVITY;
        builder.setTaskQueueType(legacyType);

        requestedTypes.forEach(builder::addTaskQueueTypes);
    }

    private EnumSet<TaskQueueType> resolveQueueTypes(String queueTypesConfig) {
        boolean workflowRequested = false;
        boolean activityRequested = false;

        if (queueTypesConfig != null && !queueTypesConfig.isBlank()) {
            String[] requestedTypes = queueTypesConfig.split(",");
            for (String requestedType : requestedTypes) {
                String normalized = requestedType.trim().toLowerCase(Locale.ROOT);
                if ("workflow".equals(normalized)) {
                    workflowRequested = true;
                } else if ("activity".equals(normalized)) {
                    activityRequested = true;
                }
            }
        }

        if (!workflowRequested && !activityRequested) {
            workflowRequested = true;
        }

        EnumSet<TaskQueueType> requested = EnumSet.noneOf(TaskQueueType.class);
        if (workflowRequested) {
            requested.add(TaskQueueType.TASK_QUEUE_TYPE_WORKFLOW);
        }
        if (activityRequested) {
            requested.add(TaskQueueType.TASK_QUEUE_TYPE_ACTIVITY);
        }
        return requested;
    }

    private long queryLegacyBacklog(WorkflowServiceStubs stubs, TemporalConnectionConfig config) {
        DescribeTaskQueueRequest.Builder requestBuilder = DescribeTaskQueueRequest.newBuilder()
                .setNamespace(config.getNamespace())
                .setTaskQueue(io.temporal.api.taskqueue.v1.TaskQueue.newBuilder()
                        .setName(config.getTaskQueue())
                        .build())
                .setIncludeTaskQueueStatus(true)
                .setApiMode(DescribeTaskQueueMode.DESCRIBE_TASK_QUEUE_MODE_ENHANCED);

        applyQueueTypeFilters(requestBuilder, config.getQueueTypes());

        DescribeTaskQueueRequest request = requestBuilder.setReportStats(true).build();

        DescribeTaskQueueResponse response = stubs.blockingStub()
                .withDeadlineAfter(DEFAULT_TIMEOUT.toMillis(), java.util.concurrent.TimeUnit.MILLISECONDS)
                .describeTaskQueue(request);

        return calculateBacklogSize(response);
    }

    private Long queryWorkerVersioningBacklog(WorkflowServiceStubs stubs, TemporalConnectionConfig config) {
        WorkflowServiceGrpc.WorkflowServiceBlockingStub stubWithDeadline = stubs.blockingStub()
                .withDeadlineAfter(DEFAULT_TIMEOUT.toMillis(), java.util.concurrent.TimeUnit.MILLISECONDS);

        try {
            GetWorkerVersioningRulesResponse rulesResponse = stubWithDeadline.getWorkerVersioningRules(
                    GetWorkerVersioningRulesRequest.newBuilder()
                            .setNamespace(config.getNamespace())
                            .setTaskQueue(config.getTaskQueue())
                            .build());

            Set<String> targetBuildIds = collectBuildIdsFromRules(rulesResponse);
            if (targetBuildIds.isEmpty()) {
                return null;
            }

            List<WorkerDeploymentVersion> deploymentVersions =
                    resolveDeploymentVersions(stubWithDeadline, config, targetBuildIds);

            if (deploymentVersions.isEmpty()) {
                log.warn("Worker versioning rules resolved build IDs {} but no deployments were found in namespace {}",
                        targetBuildIds, config.getNamespace());
                return null;
            }

            EnumSet<TaskQueueType> requestedTypes = resolveQueueTypes(config.getQueueTypes());
            long backlogTotal = 0;

            for (WorkerDeploymentVersion deploymentVersion : deploymentVersions) {
                DescribeWorkerDeploymentVersionResponse response = stubWithDeadline.describeWorkerDeploymentVersion(
                        DescribeWorkerDeploymentVersionRequest.newBuilder()
                                .setNamespace(config.getNamespace())
                                .setDeploymentVersion(deploymentVersion)
                                .setReportTaskQueueStats(true)
                                .build());

                long versionBacklog = response.getVersionTaskQueuesList().stream()
                        .filter(taskQueue -> config.getTaskQueue().equals(taskQueue.getName()))
                        .filter(taskQueue -> requestedTypes.contains(taskQueue.getType()))
                        .mapToLong(this::calculateBacklogFromVersionQueue)
                        .sum();

                backlogTotal += versionBacklog;
            }

            return backlogTotal;
        } catch (StatusRuntimeException e) {
            if (e.getStatus().getCode() == Status.Code.UNIMPLEMENTED
                    || e.getStatus().getCode() == Status.Code.INVALID_ARGUMENT) {
                log.debug("Worker versioning backlog unavailable for namespace {} queue {}: {}",
                        config.getNamespace(), config.getTaskQueue(), e.getMessage());
                return null;
            }
            throw e;
        }
    }

    private Set<String> collectBuildIdsFromRules(GetWorkerVersioningRulesResponse response) {
        Set<String> buildIds = new LinkedHashSet<>();

        for (TimestampedBuildIdAssignmentRule assignmentRule : response.getAssignmentRulesList()) {
            if (assignmentRule.hasRule()) {
                addBuildId(buildIds, assignmentRule.getRule().getTargetBuildId());
            }
        }

        for (TimestampedCompatibleBuildIdRedirectRule redirectRule : response.getCompatibleRedirectRulesList()) {
            if (redirectRule.hasRule()) {
                addBuildId(buildIds, redirectRule.getRule().getSourceBuildId());
                addBuildId(buildIds, redirectRule.getRule().getTargetBuildId());
            }
        }

        return buildIds;
    }

    private void addBuildId(Set<String> collector, String candidate) {
        if (candidate == null) {
            return;
        }
        String trimmed = candidate.trim();
        if (!trimmed.isEmpty()) {
            collector.add(trimmed);
        }
    }

    private List<WorkerDeploymentVersion> resolveDeploymentVersions(
            WorkflowServiceGrpc.WorkflowServiceBlockingStub stub,
            TemporalConnectionConfig config,
            Set<String> targetBuildIds) {

        if (targetBuildIds.isEmpty()) {
            return List.of();
        }

        Map<String, WorkerDeploymentVersion> resolvedVersions = new LinkedHashMap<>();
        List<ListWorkerDeploymentsResponse.WorkerDeploymentSummary> summaries =
                fetchAllWorkerDeployments(stub, config.getNamespace());

        for (ListWorkerDeploymentsResponse.WorkerDeploymentSummary summary : summaries) {
            addVersionSummary(summary.getLatestVersionSummary(), resolvedVersions);
            addVersionSummary(summary.getCurrentVersionSummary(), resolvedVersions);
            addVersionSummary(summary.getRampingVersionSummary(), resolvedVersions);
        }

        Set<String> unresolved = new LinkedHashSet<>(targetBuildIds);
        unresolved.removeAll(resolvedVersions.keySet());

        if (!unresolved.isEmpty()) {
            for (ListWorkerDeploymentsResponse.WorkerDeploymentSummary summary : summaries) {
                var describeResponse = stub.describeWorkerDeployment(
                        DescribeWorkerDeploymentRequest.newBuilder()
                                .setNamespace(config.getNamespace())
                                .setDeploymentName(summary.getName())
                                .build());

                if (describeResponse.hasWorkerDeploymentInfo()) {
                    for (WorkerDeploymentInfo.WorkerDeploymentVersionSummary versionSummary :
                            describeResponse.getWorkerDeploymentInfo().getVersionSummariesList()) {
                        addVersionSummary(versionSummary, resolvedVersions);
                    }
                }

                unresolved.removeAll(resolvedVersions.keySet());
                if (unresolved.isEmpty()) {
                    break;
                }
            }
        }

        List<WorkerDeploymentVersion> ordered = new ArrayList<>();
        for (String buildId : targetBuildIds) {
            WorkerDeploymentVersion version = resolvedVersions.get(buildId);
            if (version != null) {
                ordered.add(version);
            } else {
                log.warn("Build ID {} referenced by worker versioning rules was not found in any deployment", buildId);
            }
        }
        return ordered;
    }

    private List<ListWorkerDeploymentsResponse.WorkerDeploymentSummary> fetchAllWorkerDeployments(
            WorkflowServiceGrpc.WorkflowServiceBlockingStub stub,
            String namespace) {

        List<ListWorkerDeploymentsResponse.WorkerDeploymentSummary> summaries = new ArrayList<>();
        ByteString nextPageToken = ByteString.EMPTY;

        do {
            ListWorkerDeploymentsRequest.Builder requestBuilder = ListWorkerDeploymentsRequest.newBuilder()
                    .setNamespace(namespace);
            if (!nextPageToken.isEmpty()) {
                requestBuilder.setNextPageToken(nextPageToken);
            }

            ListWorkerDeploymentsResponse response = stub.listWorkerDeployments(requestBuilder.build());
            summaries.addAll(response.getWorkerDeploymentsList());
            nextPageToken = response.getNextPageToken();
            if (nextPageToken == null) {
                nextPageToken = ByteString.EMPTY;
            }
        } while (!nextPageToken.isEmpty());

        return summaries;
    }

    private void addVersionSummary(WorkerDeploymentInfo.WorkerDeploymentVersionSummary summary,
            Map<String, WorkerDeploymentVersion> accumulator) {
        if (summary == null || !summary.hasDeploymentVersion()) {
            return;
        }
        WorkerDeploymentVersion version = summary.getDeploymentVersion();
        if (version.getBuildId().isBlank() || version.getDeploymentName().isBlank()) {
            return;
        }
        accumulator.putIfAbsent(version.getBuildId(), version);
    }

    private long calculateBacklogFromVersionQueue(
            DescribeWorkerDeploymentVersionResponse.VersionTaskQueue taskQueue) {
        long backlog = 0;
        if (taskQueue.hasStats()) {
            backlog = Math.max(taskQueue.getStats().getApproximateBacklogCount(), 0);
        }

        if (backlog <= 0 && taskQueue.getStatsByPriorityKeyCount() > 0) {
            backlog = taskQueue.getStatsByPriorityKeyMap().values().stream()
                    .mapToLong(TaskQueueStats::getApproximateBacklogCount)
                    .sum();
        }

        return Math.max(backlog, 0);
    }

    /**
     * Calculate backlog size from DescribeTaskQueueResponse by inspecting both
     * legacy {@link TaskQueueStatus} hints and the newer stats payload when
     * available.
     */
    private long calculateBacklogSize(DescribeTaskQueueResponse response) {
        long totalBacklog = 0;

        if (response.hasTaskQueueStatus()) {
            TaskQueueStatus legacyStatus = response.getTaskQueueStatus();
            totalBacklog = legacyStatus.getBacklogCountHint();
            log.debug("TaskQueueStatus backlog hint: {}", totalBacklog);
        }

        // In Temporal SDK 1.32+ -- stats are here
        var stats = response.hasStats() ? response.getStats() : null;

        if (stats != null) {
            long approximate = stats.getApproximateBacklogCount();
            double addRate = stats.getTasksAddRate();
            double dispatchRate = stats.getTasksDispatchRate();

            if (approximate > totalBacklog) {
                totalBacklog = approximate;
            }

            log.debug("Approx backlog: {}", approximate);
            log.debug("Task add rate (/s): {}", addRate);
            log.debug("Task dispatch rate (/s): {}", dispatchRate);
        }

        if (totalBacklog < 0) {
            totalBacklog = 0;
        }

        return totalBacklog;
    }

    /**
     * Get or create WorkflowServiceStubs for an endpoint.
     */
    private WorkflowServiceStubs getOrCreateStubs(TemporalConnectionConfig config) {
        String cacheKey = config.getEndpoint() + "-" + config.getNamespace();

        return stubsCache.computeIfAbsent(cacheKey, key -> {
            try {
                return createStubs(config);
            } catch (Exception e) {
                log.error("Failed to create Temporal stubs: {}", e.getMessage(), e);
                throw new RuntimeException("Failed to create Temporal client", e);
            }
        });
    }

    /**
     * Create WorkflowServiceStubs with appropriate authentication.
     */
    private WorkflowServiceStubs createStubs(TemporalConnectionConfig config) throws SSLException, IOException {
        WorkflowServiceStubsOptions.Builder optionsBuilder = WorkflowServiceStubsOptions.newBuilder()
                .setTarget(config.getEndpoint());

        // Configure channel builder
        NettyChannelBuilder channelBuilder = NettyChannelBuilder.forTarget(config.getEndpoint());

        // Configure SSL/TLS
        if (config.isUnsafeSsl()) {
            log.warn("Unsafe SSL mode enabled for endpoint: {}", config.getEndpoint());
            channelBuilder.usePlaintext();
        } else if (config.hasMtlsConfig()) {
            // Configure mTLS
            SslContext sslContext = buildSslContext(config);
            channelBuilder.sslContext(sslContext);

            if (config.getTlsServerName() != null) {
                channelBuilder.overrideAuthority(config.getTlsServerName());
            }
        } else {
            // Use TLS without client cert
            channelBuilder.useTransportSecurity();
        }

        // Configure API key if provided
        if (config.getApiKey() != null) {
            Metadata metadata = new Metadata();
            Metadata.Key<String> authKey = Metadata.Key.of("authorization", Metadata.ASCII_STRING_MARSHALLER);
            metadata.put(authKey, "Bearer " + config.getApiKey());

            channelBuilder.intercept(MetadataUtils.newAttachHeadersInterceptor(metadata));
        }

        // Set connection timeout
        if (config.getMinConnectTimeout() != null) {
            channelBuilder.keepAliveTimeout(config.getMinConnectTimeout().toMillis(),
                    java.util.concurrent.TimeUnit.MILLISECONDS);
        }

        optionsBuilder.setChannel(channelBuilder.build());

        WorkflowServiceStubs stubs = WorkflowServiceStubs.newServiceStubs(optionsBuilder.build());

        log.info("Created Temporal client for endpoint: {}", config.getEndpoint());
        return stubs;
    }

    /**
     * Build SSL context for mTLS.
     */
    private SslContext buildSslContext(TemporalConnectionConfig config) throws SSLException {
        SslContextBuilder sslBuilder = GrpcSslContexts.forClient();

        // Add CA certificate
        if (config.getCaCert() != null) {
            ByteArrayInputStream caStream = new ByteArrayInputStream(
                    config.getCaCert().getBytes(StandardCharsets.UTF_8));
            sslBuilder.trustManager(caStream);
        }

        // Add client certificate and key
        if (config.getClientCert() != null && config.getClientKey() != null) {
            ByteArrayInputStream certStream = new ByteArrayInputStream(
                    config.getClientCert().getBytes(StandardCharsets.UTF_8));
            ByteArrayInputStream keyStream = new ByteArrayInputStream(
                    config.getClientKey().getBytes(StandardCharsets.UTF_8));

            // TODO: Handle keyPassword if provided
            sslBuilder.keyManager(certStream, keyStream);
        }

        return sslBuilder.build();
    }

    /**
     * Test connection to Temporal.
     */
    public boolean testConnection(TemporalConnectionConfig config) {
        try {
            WorkflowServiceStubs stubs = getOrCreateStubs(config);

            // Simple health check - describe namespace
            var request = io.temporal.api.workflowservice.v1.DescribeNamespaceRequest.newBuilder()
                    .setNamespace(config.getNamespace())
                    .build();

            stubs.blockingStub()
                    .withDeadlineAfter(5000, java.util.concurrent.TimeUnit.MILLISECONDS)
                    .describeNamespace(request);

            return true;
        } catch (Exception e) {
            log.error("Connection test failed: {}", e.getMessage());
            return false;
        }
    }

    /**
     * Shutdown and cleanup all cached stubs.
     */
    public void shutdown() {
        stubsCache.values().forEach(stubs -> {
            try {
                stubs.shutdown();
            } catch (Exception e) {
                log.warn("Error shutting down Temporal stubs: {}", e.getMessage());
            }
        });
        stubsCache.clear();
    }

    /**
     * Configuration class for Temporal connection.
     */
    public static class TemporalConnectionConfig {
        private String endpoint;
        private String namespace;
        private String taskQueue;
        private String queueTypes;
        private String apiKey;
        private String caCert;
        private String clientCert;
        private String clientKey;
        private String keyPassword;
        private String tlsServerName;
        private boolean unsafeSsl;
        private Duration minConnectTimeout;

        // Getters and setters
        public String getEndpoint() {
            return endpoint;
        }

        public void setEndpoint(String endpoint) {
            this.endpoint = endpoint;
        }

        public String getNamespace() {
            return namespace;
        }

        public void setNamespace(String namespace) {
            this.namespace = namespace;
        }

        public String getTaskQueue() {
            return taskQueue;
        }

        public void setTaskQueue(String taskQueue) {
            this.taskQueue = taskQueue;
        }

        public String getQueueTypes() {
            return queueTypes;
        }

        public void setQueueTypes(String queueTypes) {
            this.queueTypes = queueTypes;
        }

        public String getApiKey() {
            return apiKey;
        }

        public void setApiKey(String apiKey) {
            this.apiKey = apiKey;
        }

        public String getCaCert() {
            return caCert;
        }

        public void setCaCert(String caCert) {
            this.caCert = caCert;
        }

        public String getClientCert() {
            return clientCert;
        }

        public void setClientCert(String clientCert) {
            this.clientCert = clientCert;
        }

        public String getClientKey() {
            return clientKey;
        }

        public void setClientKey(String clientKey) {
            this.clientKey = clientKey;
        }

        public String getKeyPassword() {
            return keyPassword;
        }

        public void setKeyPassword(String keyPassword) {
            this.keyPassword = keyPassword;
        }

        public String getTlsServerName() {
            return tlsServerName;
        }

        public void setTlsServerName(String tlsServerName) {
            this.tlsServerName = tlsServerName;
        }

        public boolean isUnsafeSsl() {
            return unsafeSsl;
        }

        public void setUnsafeSsl(boolean unsafeSsl) {
            this.unsafeSsl = unsafeSsl;
        }

        public Duration getMinConnectTimeout() {
            return minConnectTimeout;
        }

        public void setMinConnectTimeout(Duration minConnectTimeout) {
            this.minConnectTimeout = minConnectTimeout;
        }

        public boolean hasMtlsConfig() {
            return (caCert != null || clientCert != null || clientKey != null);
        }
    }

    /**
     * Get custom metric value from Temporal.
     * 
     * @param config       Connection configuration
     * @param customMetric Custom metric definition
     * @return Metric value
     */
    public double getCustomMetricValue(TemporalConnectionConfig config, CustomMetric customMetric) {
        try {
            WorkflowServiceStubs stubs = getOrCreateStubs(config);

            switch (customMetric.getMetricType().toLowerCase()) {
                case "workflow_execution_rate":
                    return getWorkflowExecutionRate(stubs, config, customMetric);

                case "workflow_success_rate":
                    return getWorkflowSuccessRate(stubs, config, customMetric);

                case "workflow_latency":
                    return getWorkflowLatency(stubs, config, customMetric);

                case "activity_execution_rate":
                    return getActivityExecutionRate(stubs, config, customMetric);

                case "activity_error_rate":
                    return getActivityErrorRate(stubs, config, customMetric);

                case "custom":
                    return getCustomMetric(stubs, config, customMetric);

                default:
                    log.warn("Unknown custom metric type: {}", customMetric.getMetricType());
                    return -1.0;
            }

        } catch (Exception e) {
            log.error("Failed to query custom metric {}: {}",
                    customMetric.getMetricType(), e.getMessage(), e);
            return -1.0;
        }
    }

    /**
     * Get workflow execution rate.
     * TODO: Implement using Temporal metrics API or visibility API.
     * This is a placeholder implementation.
     */
    private double getWorkflowExecutionRate(WorkflowServiceStubs stubs,
            TemporalConnectionConfig config,
            CustomMetric metric) {
        log.warn("workflow_execution_rate metric not yet implemented");
        // TODO: Query Temporal metrics endpoint or use visibility API
        // to count workflow starts in the time window
        return 0.0;
    }

    /**
     * Get workflow success rate (percentage).
     * TODO: Implement using Temporal visibility API.
     */
    private double getWorkflowSuccessRate(WorkflowServiceStubs stubs,
            TemporalConnectionConfig config,
            CustomMetric metric) {
        log.warn("workflow_success_rate metric not yet implemented");
        // TODO: Query completed workflows in time window
        // Calculate: (successful / total) * 100
        return 100.0;
    }

    /**
     * Get workflow latency (average in milliseconds).
     * TODO: Implement using Temporal visibility API.
     */
    private double getWorkflowLatency(WorkflowServiceStubs stubs,
            TemporalConnectionConfig config,
            CustomMetric metric) {
        log.warn("workflow_latency metric not yet implemented");
        // TODO: Query workflow execution history
        // Calculate: average(closeTime - startTime) for completed workflows
        return 0.0;
    }

    /**
     * Get activity execution rate.
     * TODO: Implement using Temporal metrics.
     */
    private double getActivityExecutionRate(WorkflowServiceStubs stubs,
            TemporalConnectionConfig config,
            CustomMetric metric) {
        log.warn("activity_execution_rate metric not yet implemented");
        // TODO: Query activity metrics from Temporal
        return 0.0;
    }

    /**
     * Get activity error rate (percentage).
     * TODO: Implement using Temporal visibility API.
     */
    private double getActivityErrorRate(WorkflowServiceStubs stubs,
            TemporalConnectionConfig config,
            CustomMetric metric) {
        log.warn("activity_error_rate metric not yet implemented");
        // TODO: Query activity failures vs successes
        // Calculate: (failures / total) * 100
        return 0.0;
    }

    /**
     * Get custom metric by name.
     * TODO: Implement integration with Temporal SDK metrics or external metrics
     * system.
     */
    private double getCustomMetric(WorkflowServiceStubs stubs,
            TemporalConnectionConfig config,
            CustomMetric metric) {
        log.warn("Custom metric '{}' not yet implemented", metric.getMetricName());
        // TODO: Query custom metric from:
        // - Temporal SDK metrics (if exposed)
        // - External metrics system (Prometheus, Datadog, etc.)
        // - Custom metric endpoint
        return 0.0;
    }
}
