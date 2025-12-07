# Temporal Autoscaler Operator

A Kubernetes Operator built with Quarkus and Java Operator SDK that provides autoscaling for workloads based on Temporal task queue metrics.

## Overview

The Temporal Autoscaler Operator monitors Temporal task queues and automatically scales Kubernetes workloads (Deployments, StatefulSets, ReplicaSets) based on queue backlog. It provides:

- **Temporal-driven scaling**: Scale based on workflow or activity queue sizes
- **Worker versioning aware backlog**: Prefer the Worker Deployment + Versioning Rules APIs and only fall back to legacy DescribeTaskQueue when a namespace lacks those RPCs
- **Custom metrics support**: Scale based on workflow execution rates, latencies, success rates, and custom metrics
- **Distributed coordination**: Uses Kubernetes Leases API to prevent concurrent scaling operations
- **Multiple trigger support**: Monitor multiple task queues with different scaling policies
- **Authentication support**: mTLS and API key authentication for Temporal Cloud
- **Safety features**: Min/max replica counts, cooldown periods, stabilization windows
- **Scale-to-zero**: Activate workloads from zero replicas when tasks arrive
- **Metrics**: Prometheus metrics for monitoring queue sizes and scaling events

## Deployment Options

The operator can be deployed using multiple methods:

1. **Helm Chart** (Recommended) - Production-ready deployment with extensive configuration options
   - Pre-configured for Kubernetes and OpenShift
   - Includes RBAC, CRDs, ServiceMonitor, Routes, and security contexts
   - See [Helm Chart Documentation](helm/temporal-autoscaler-operator/README.md)

2. **kubectl/oc** - Direct YAML manifest deployment
   - Manual deployment of CRDs, RBAC, and operator
   - Located in `deploy/` directory

3. **Build from Source** - For development and customization
   - Requires Java 17+ and Maven 3.8+
   - Supports native compilation with GraalVM

## Architecture

### Single Operator, Multiple Task Queues

The Temporal Autoscaler Operator is designed to manage multiple task queues and workloads from a single deployment:

- **One operator instance** can manage multiple `TemporalScaler` custom resources
- Each `TemporalScaler` CR defines its own:
  - Target workload to scale (Deployment/StatefulSet/ReplicaSet)
  - Temporal namespace and task queue to monitor
  - Independent scaling parameters (min/max replicas, target queue size)
  - Separate polling intervals and cooldown periods

**Benefits:**

- ✅ Single operator deployment manages all Temporal-based autoscaling
- ✅ Each workload gets independent scaling policies
- ✅ Reduced operational overhead
- ✅ Centralized monitoring and management
- ✅ Different task queues can be scaled concurrently

### Example: Managing Multiple Workers

```yaml
# payments-scaler.yaml
apiVersion: scaling.example.com/v1alpha1
kind: TemporalScaler
metadata:
  name: payments-scaler
spec:
  scaleTargetRef:
    name: payment-workers
  triggers:
    - type: temporal
      metadata:
        taskQueue: payments-queue
        targetQueueSize: "5"
---
# orders-scaler.yaml
apiVersion: scaling.example.com/v1alpha1
kind: TemporalScaler
metadata:
  name: orders-scaler
spec:
  scaleTargetRef:
    name: order-workers
  triggers:
    - type: temporal
      metadata:
        taskQueue: orders-queue
        targetQueueSize: "10"
```

### Component Architecture

```
┌────────────────────────────────────────────────────────────┐
│                    Kubernetes Cluster                      │
│                                                            │
│  ┌─────────────────────────────────────────────────────┐   │
│  │   Temporal Autoscaler Operator (Single Instance)    │   │
│  │   ┌──────────────────────────────────────────────┐  │   │
│  │   │  TemporalScalerController                    │  │   │
│  │   │  - Watches TemporalScaler CRs                │  │   │
│  │   │  - Queries Temporal task queues              │  │   │
│  │   │  - Calculates desired replicas               │  │   │
│  │   │  - Scales target workloads                   │  │   │
│  │   │  - Handles MULTIPLE CRs independently        │  │   │
│  │   └──────────────────────────────────────────────┘  │   │
│  │   ┌──────────────────────────────────────────────┐  │   │
│  │   │  LeaseCoordinator                            │  │   │
│  │   │  - Acquires/releases Kubernetes Leases       │  │   │
│  │   │  - Ensures single operator instance scales   │  │   │
│  │   │  - Per-target coordination in HA mode        │  │   │
│  │   └──────────────────────────────────────────────┘  │   │
│  │   ┌──────────────────────────────────────────────┐  │   │
│  │   │  TemporalClientFacade                        │  │   │
│  │   │  - Connects to Temporal via gRPC             │  │   │
│  │   │  - Queries task queue metrics                │  │   │
│  │   │  - Handles mTLS/API key auth                 │  │   │
│  │   │  - Creates connections per CR                │  │   │
│  │   └──────────────────────────────────────────────┘  │   │
│  └─────────────────────────────────────────────────────┘   │
│                           │                                │
│                           │ scales multiple workloads      │
│                           ▼                                │
│  ┌─────────────────────────────────────────────────────┐   │
│  │   Target Workloads (Deployments/StatefulSets)       │   │
│  │   - Payment workers (payments-queue)                │   │
│  │   - Order workers (orders-queue)                    │   │
│  │   - Fulfillment workers (fulfillment-queue)         │   │
│  │   - Each scaled independently                       │   │
│  └─────────────────────────────────────────────────────┘   │
│                                                            │
└────────────────────────────────────────────────────────────┘
                           │
                           │ queries multiple queue metrics
                           ▼
                  ┌─────────────────┐
                  │  Temporal       │
                  │  Frontend       │
                  │  - payments-q   │
                  │  - orders-q     │
                  │  - fulfill-q    │
                  └─────────────────┘
```

### How Backlog Is Calculated

The operator no longer relies on user-provided build IDs or version sets. Instead it uses the Worker Versioning surface to discover what needs to be drained:

1. **Get worker versioning rules**: Fetch assignment and compatible redirect rules for the task queue to collect the authoritative build IDs.
2. **Resolve deployments/versions**: Use `ListWorkerDeployments` and, if necessary, `DescribeWorkerDeployment` to map each build ID to a concrete deployment version.
3. **Pull backlog per version**: Call `DescribeWorkerDeploymentVersion` with `reportTaskQueueStats` enabled and sum the backlog for the requested queue/type combination.
4. **Graceful fallback**: When a namespace doesn’t expose those APIs (older clusters or permissions), the operator automatically falls back to `DescribeTaskQueue` in enhanced mode so queue-based scaling keeps working.

This flow mirrors what Temporal Cloud uses internally, so no CRD field is required for build IDs and discrepancies between worker rollouts and autoscaling signals are eliminated.

## Features

### Scaling Strategies

#### Queue-Based Scaling (Default)

- **Queue-based scaling**: Calculates replicas as `ceil(queueSize / targetQueueSize)`
- **Activation threshold**: Scale from zero when queue size reaches activation target
- **Example use case**: Scale workers based on pending workflow/activity tasks

#### Custom Metrics Scaling

Scale based on Temporal workflow and activity metrics:

**Supported Metric Types:**

- `workflow_execution_rate`: Scale based on workflow start rate (workflows/second)
- `workflow_success_rate`: Scale based on successful workflow completion percentage
- `workflow_latency`: Scale based on workflow execution duration (milliseconds)
- `activity_execution_rate`: Scale based on activity execution rate (activities/second)
- `activity_error_rate`: Scale based on activity error percentage
- `custom`: Scale based on custom metric names from external systems

**Configuration Options:**

- `targetValue`: Target metric value to maintain (e.g., 100 workflows/sec, 95% success rate)
- `activationValue`: Threshold to scale from zero replicas
- `workflowType`/`activityType`: Filter metrics by specific types
- `timeWindowSeconds`: Time window for rate calculations (default: 60s)
- `aggregation`: How to aggregate metric values (average, sum, max, min, percentile)
- `percentile`: For percentile-based aggregation (e.g., p95 latency)

**Example Custom Metrics Configuration:**

```yaml
triggers:
  - customMetrics:
      # Scale to maintain 100 workflow executions per second
      - metricType: workflow_execution_rate
        workflowType: order-processing
        targetValue: 100.0
        activationValue: 10.0
        timeWindowSeconds: 60
        aggregation: average
      
      # Scale to maintain 99% success rate
      - metricType: workflow_success_rate
        workflowType: order-processing
        targetValue: 99.0
        activationValue: 95.0
        aggregation: average
      
      # Scale to keep p95 latency under 5 seconds
      - metricType: workflow_latency
        workflowType: order-processing
        targetValue: 5000.0  # milliseconds
        aggregation: percentile
        percentile: 95.0
      
      # Scale based on activity error rate
      - metricType: activity_error_rate
        activityType: payment-processing
        targetValue: 1.0  # Keep errors below 1%
        aggregation: average
```

**Multi-Metric Behavior:**

- When multiple custom metrics are configured, the operator calculates desired replicas for each
- Takes the **maximum** replicas across all metrics to ensure all thresholds are met
- Falls back to queue-based scaling if no custom metrics are configured

**Implementation Status:**
> ⚠️ **Note**: Custom metrics support is currently implemented with stub methods. To enable custom metrics:

### General Scaling Features

- **Multi-trigger support**: Takes max desired replicas across all triggers
- **Min/max constraints**: Respects configured replica bounds
- **Cooldown periods**: Prevents thrashing with configurable cooldown
- **Stabilization windows**: HPA-style stabilization for scale-down operations

### Safety & Reliability

- **Lease-based coordination**: Only one operator instance scales a target at a time
- **Exponential backoff**: Retry logic for transient Temporal connection errors
- **Validation**: Spec validation prevents invalid configurations
- **Status tracking**: Reports current/desired replicas and conditions
- **Metrics**: Prometheus metrics for observability

### Authentication

- **mTLS**: Client certificates for secure Temporal connections
- **API Key**: Bearer token authentication for Temporal Cloud
- **Unsafe SSL**: Optional plaintext mode for development

## Prerequisites

- Kubernetes 1.21+ or OpenShift 4.8+
- Helm 3.0+ (for Helm installation method)
- Java 17+ (for building from source)
- Maven 3.8+ (for building from source)
- Podman or Docker (for building container images)
- Access to a Temporal cluster

## Quick Start

### Option 1: Deploy with Helm (Recommended)

**For Kubernetes:**

```bash
# Clone the repository
git clone https://github.com/hoggmania/temporal-autoscaler-operator
cd temporal-autoscaler-operator

# Install with Helm
helm install temporal-autoscaler ./helm/temporal-autoscaler-operator \
  --namespace temporal-autoscaler-system \
  --create-namespace
```

**For OpenShift:**

```bash
# Create project
oc new-project temporal-autoscaler-system

# Install with OpenShift values
helm install temporal-autoscaler ./helm/temporal-autoscaler-operator \
  --namespace temporal-autoscaler-system \
  --values ./helm/temporal-autoscaler-operator/values-openshift.yaml
```

See [Helm Chart Documentation](helm/temporal-autoscaler-operator/README.md) for detailed configuration options.

### Option 2: Deploy with kubectl/oc

```bash
# Clone the repository
git clone https://github.com/hoggmania/temporal-autoscaler-operator
cd temporal-autoscaler-operator

# Create namespace
kubectl create namespace temporal-autoscaler-system

# Deploy CRD
kubectl apply -f deploy/crd/temporal-autoscaler-crd.yaml

# Deploy RBAC
kubectl apply -f deploy/rbac.yaml

# Deploy operator
kubectl apply -f deploy/operator-deployment.yaml

# Verify operator is running
kubectl get pods -n temporal-autoscaler-system
```

### Option 3: Build from Source

```bash
# Build with Maven
mvn clean package

# Build container image
podman build -f src/main/docker/Dockerfile.jvm -t temporal-autoscaler-operator:latest .

# Push to your registry
podman push temporal-autoscaler-operator:latest your-registry/temporal-autoscaler-operator:latest
```

## Usage

### Create a TemporalScaler Resource

```bash
# Edit the example with your Temporal details
vim deploy/examples/temporal-scaler-example.yaml

# Apply the TemporalScaler
kubectl apply -f deploy/examples/temporal-scaler-example.yaml

# Watch the operator scale your workload
kubectl get temporalscalers -w
```

## Configuration

### TemporalScaler Custom Resource

```yaml
apiVersion: scaling.example.com/v1alpha1
kind: TemporalScaler
metadata:
  name: my-worker-scaler
  namespace: default
spec:
  # Target workload to scale
  scaleTargetRef:
    apiVersion: apps/v1
    kind: Deployment
    name: my-temporal-worker

  # Scaling parameters
  pollingInterval: 5          # Query Temporal every 5 seconds
  cooldownPeriod: 10          # Wait 10 seconds between scale operations
  minReplicaCount: 0          # Allow scale to zero
  maxReplicaCount: 10         # Maximum 10 replicas

  # Optional: HPA-style behavior
  advanced:
    horizontalPodAutoscalerConfig:
      behavior:
        scaleDown:
          stabilizationWindowSeconds: 30  # Wait 30s before scaling down

  # Temporal triggers
  triggers:
    - type: temporal
      metadata:
        # Temporal connection
        endpoint: temporal-frontend.temporal.svc.cluster.local:7233
        namespace: default
        
        # Task queue to monitor
        taskQueue: "my-workflow-queue"
        queueTypes: "workflow"  # or "activity" or "workflow,activity"
        
        # Scaling thresholds (queue-based scaling)
        targetQueueSize: "5"              # Target 5 tasks per replica
        activationTargetQueueSize: "1"    # Activate from zero at 1 task
        
        # Optional: Authentication (choose one)
        # API Key (Temporal Cloud)
        # apiKey: "your-api-key"
        # apiKeyFromEnv: "TEMPORAL_API_KEY"
        
        # mTLS
        # ca: "base64-encoded-ca-cert"
        # cert: "base64-encoded-client-cert"
        # key: "base64-encoded-client-key"
        # tlsServerName: "temporal.example.com"
        
        # Worker versioning is resolved automatically.
        # The operator inspects Worker Deployments + Versioning Rules for this queue
        # and falls back to legacy DescribeTaskQueue if those APIs are unavailable.
      
      # Optional: Custom metrics scaling (overrides queue-based scaling)
      customMetrics:
        # Scale to maintain 100 workflow executions per second
        - metricType: workflow_execution_rate
          workflowType: order-processing
          targetValue: 100.0
          activationValue: 10.0
          timeWindowSeconds: 60
          aggregation: average
        
        # Scale to maintain 95% success rate
        - metricType: workflow_success_rate
          workflowType: order-processing
          targetValue: 95.0
          activationValue: 90.0
          aggregation: average
```

### Environment Variables

Configure the operator via environment variables in `deploy/operator-deployment.yaml`:

```yaml
env:
  # Temporal connection (if using environment-based config)
  - name: TEMPORAL_ENDPOINT
    value: temporal-frontend.temporal.svc.cluster.local:7233
  
  # API Key from Secret
  - name: TEMPORAL_API_KEY
    valueFrom:
      secretKeyRef:
        name: temporal-credentials
        key: api-key
  
  # Operator configuration
  - name: QUARKUS_LOG_LEVEL
    value: INFO
```

### Using Secrets for Authentication

#### API Key Authentication

```yaml
apiVersion: v1
kind: Secret
metadata:
  name: temporal-credentials
type: Opaque
stringData:
  api-key: "your-temporal-cloud-api-key"
```

Reference in trigger:

```yaml
metadata:
  apiKeyFromEnv: TEMPORAL_API_KEY
```

#### mTLS Authentication

```yaml
apiVersion: v1
kind: Secret
metadata:
  name: temporal-mtls-certs
type: Opaque
stringData:
  ca.crt: |
    -----BEGIN CERTIFICATE-----
    ... CA certificate ...
    -----END CERTIFICATE-----
  tls.crt: |
    -----BEGIN CERTIFICATE-----
    ... Client certificate ...
    -----END CERTIFICATE-----
  tls.key: |
    -----BEGIN PRIVATE KEY-----
    ... Client private key ...
    -----END PRIVATE KEY-----
```

Reference in trigger:

```yaml
metadata:
  ca: "$(cat ca.crt | base64)"
  cert: "$(cat tls.crt | base64)"
  key: "$(cat tls.key | base64)"
  tlsServerName: "temporal.example.com"
```

## Development

### Running Locally

```bash
# Start Quarkus in dev mode
./mvnw quarkus:dev

# The operator will connect to your current kubectl context
# Make sure you have appropriate RBAC permissions
```

### Running Tests

```bash
# Run all tests
./mvnw test

# Run specific test
./mvnw test -Dtest=LeaseCoordinatorTest
```

### Building Native Image

```bash
# Build native executable with GraalVM
./mvnw package -Pnative

# Build native Podman image
podman build -f src/main/docker/Dockerfile.native -t temporal-autoscaler-operator:native .
```

## Monitoring

### Metrics

The operator exposes Prometheus metrics on port 8080:

```bash
# Access metrics
kubectl port-forward -n temporal-autoscaler-system \
  svc/temporal-autoscaler-operator 8080:8080

curl http://localhost:8080/metrics
```

Key metrics:

- `temporal_queue_size`: Current task queue backlog size
- `temporal_scaler_scale_up_total`: Count of scale-up operations
- `temporal_scaler_scale_down_total`: Count of scale-down operations

### Health Checks

```bash
# Liveness probe
curl http://localhost:8080/q/health/live

# Readiness probe
curl http://localhost:8080/q/health/ready
```

## Troubleshooting

### Operator not scaling workload

1. Check operator logs:

```bash
kubectl logs -n temporal-autoscaler-system \
  -l app=temporal-autoscaler-operator -f
```

1. Check TemporalScaler status:

```bash
kubectl describe temporalscaler my-worker-scaler
```

1. Verify RBAC permissions:

```bash
kubectl auth can-i update deployments --as=system:serviceaccount:temporal-autoscaler-system:temporal-autoscaler-operator
```

### Cannot connect to Temporal

1. Verify endpoint is reachable:

```bash
kubectl run -it --rm debug --image=busybox --restart=Never -- \
  nc -zv temporal-frontend.temporal.svc.cluster.local 7233
```

1. Check authentication configuration
1. Review operator logs for connection errors

### Lease conflicts

Multiple operator instances may compete for leases. Check:

```bash
# View leases
kubectl get leases -A

# Verify only one operator pod is active
kubectl get pods -n temporal-autoscaler-system
```

## Important Notes

### Custom Metrics Implementation Status

The custom metrics feature is currently implemented with stub methods. To enable custom metrics in production:

1. Verify the Temporal gRPC API methods for your version
2. Implement the following methods in `TemporalClientFacade.java`:
   - `getWorkflowExecutionRate()` - Query workflow start events
   - `getWorkflowSuccessRate()` - Calculate completion success percentage
   - `getWorkflowLatency()` - Measure workflow execution duration
   - `getActivityExecutionRate()` - Query activity execution events
   - `getActivityErrorRate()` - Calculate activity failure percentage
   - `getCustomMetric()` - Integration with external metrics systems
3. Consider integrating with Prometheus/Datadog for metrics collection
4. Test metric queries against your Temporal deployment
5. Update CRD schema to include `customMetrics` field validation

**Current behavior**: Custom metrics methods return placeholder values (-1 or 0) and need real implementation.

### Temporal API Compatibility

The `TemporalClientFacade` now prefers Temporal's Worker Versioning APIs (Worker Deployments + Versioning Rules) to measure backlog per build/deployment. If those RPCs are unavailable, it falls back to `DescribeTaskQueue` with enhanced stats. Validate which path your cluster supports and adjust credentials/permissions accordingly.

**TODO for production use:**

1. Verify the Temporal gRPC API methods for your version
2. Implement proper queue size calculation in `calculateBacklogSize()`
3. Validate Worker Deployment + Versioning Rule queries against your cluster (the operator now prefers these APIs and falls back to `DescribeTaskQueue` if they are unavailable)
4. Test with your specific Temporal deployment

### Scaling to Zero Considerations

When `minReplicaCount: 0`, be aware:

- Workers must start quickly to avoid task timeouts
- Consider activation threshold carefully
- Monitor cold start latency
- Temporal tasks may timeout if workers don't start fast enough

### Lease TTL

Default lease duration is 15 seconds. Adjust based on:

- Polling interval (should be longer than interval)
- Desired failover speed
- Network latency

## Contributing

Contributions welcome! Please:

1. Fork the repository
2. Create a feature branch
3. Add tests for new functionality
4. Submit a pull request

## License

[Specify your license here]

## Support

For issues and questions:

- GitHub Issues: <https://github.com/hoggmania/temporal-autoscaler-operator/issues>
- Helm Chart Documentation: [helm/temporal-autoscaler-operator/README.md](helm/temporal-autoscaler-operator/README.md)
- OpenShift Deployment Guide: [helm/OPENSHIFT_DEPLOYMENT.md](helm/OPENSHIFT_DEPLOYMENT.md)

## Documentation

- [Helm Chart README](helm/temporal-autoscaler-operator/README.md) - Complete Helm installation guide
- [OpenShift Deployment](helm/OPENSHIFT_DEPLOYMENT.md) - OpenShift-specific deployment instructions
- [Custom Metrics Implementation](docs/CUSTOM_METRICS_IMPLEMENTATION.md) - Custom metrics feature details
- [Helm Chart Summary](helm/HELM_CHART_SUMMARY.md) - Helm chart architecture and features

## Roadmap

- [ ] Advanced scaling policies (predictive scaling)
- [ ] Multi-cluster support
- [ ] Grafana dashboards
- [ ] Helm chart for easy deployment
