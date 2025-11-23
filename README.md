# Temporal Autoscaler Operator

A production-ready Kubernetes Operator built with Quarkus and Java Operator SDK that provides autoscaling for workloads based on Temporal task queue metrics.

## Overview

The Temporal Autoscaler Operator monitors Temporal task queues and automatically scales Kubernetes workloads (Deployments, StatefulSets, ReplicaSets) based on queue backlog. It provides:

- **Temporal-driven scaling**: Scale based on workflow or activity queue sizes
- **Distributed coordination**: Uses Kubernetes Leases API to prevent concurrent scaling operations
- **Multiple trigger support**: Monitor multiple task queues with different scaling policies
- **Authentication support**: mTLS and API key authentication for Temporal Cloud
- **Safety features**: Min/max replica counts, cooldown periods, stabilization windows
- **Scale-to-zero**: Activate workloads from zero replicas when tasks arrive
- **Metrics**: Prometheus metrics for monitoring queue sizes and scaling events

## Architecture

```
┌────────────────────────────────────────────────────────────┐
│                    Kubernetes Cluster                      │
│                                                            │
│  ┌─────────────────────────────────────────────────────┐   │
│  │   Temporal Autoscaler Operator                      │   │
│  │   ┌──────────────────────────────────────────────┐  │   │
│  │   │  TemporalScalerController                    │  │   │
│  │   │  - Watches TemporalScaler CRs                │  │   │
│  │   │  - Queries Temporal task queues              │  │   │
│  │   │  - Calculates desired replicas               │  │   │
│  │   │  - Scales target workloads                   │  │   │
│  │   └──────────────────────────────────────────────┘  │   │
│  │   ┌──────────────────────────────────────────────┐  │   │
│  │   │  LeaseCoordinator                            │  │   │
│  │   │  - Acquires/releases Kubernetes Leases       │  │   │
│  │   │  - Ensures single operator instance scales   │  │   │
│  │   └──────────────────────────────────────────────┘  │   │
│  │   ┌──────────────────────────────────────────────┐  │   │
│  │   │  TemporalClientFacade                        │  │   │
│  │   │  - Connects to Temporal via gRPC             │  │   │
│  │   │  - Queries task queue metrics                │  │   │
│  │   │  - Handles mTLS/API key auth                 │  │   │
│  │   └──────────────────────────────────────────────┘  │   │
│  └─────────────────────────────────────────────────────┘   │
│                           │                                │
│                           │ scales                         │
│                           ▼                                │
│  ┌─────────────────────────────────────────────────────┐   │
│  │   Target Workload (Deployment/StatefulSet)          │   │
│  │   - Worker pods processing Temporal tasks           │   │
│  └─────────────────────────────────────────────────────┘   │
│                                                            │
└────────────────────────────────────────────────────────────┘
                           │
                           │ queries queue metrics
                           ▼
                  ┌─────────────────┐
                  │  Temporal       │
                  │  Frontend       │
                  └─────────────────┘
```

## Features

### Scaling Logic

- **Queue-based scaling**: Calculates replicas as `ceil(queueSize / targetQueueSize)`
- **Multi-trigger support**: Takes max desired replicas across all triggers
- **Activation threshold**: Scale from zero when queue size reaches activation target
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

- Kubernetes 1.21+ cluster
- Java 17+
- Maven 3.8+
- Docker (for building container images)
- Access to a Temporal cluster

## Quick Start

### 1. Build the Operator

```bash
# Clone the repository
git clone <repository-url>
cd temporal-autoscaler-operator

# Build with Maven
./mvnw clean package

# Build Podman image
podman build -f src/main/docker/Dockerfile.jvm -t temporal-autoscaler-operator:latest .
```

### 2. Deploy to Kubernetes

```bash
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

### 3. Create a TemporalScaler Resource

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
        
        # Scaling thresholds
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
        
        # Optional: Versioning
        # buildId: "v1.2.3"
        # selectAllActive: "true"
        # selectUnversioned: "false"
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

2. Check TemporalScaler status:
```bash
kubectl describe temporalscaler my-worker-scaler
```

3. Verify RBAC permissions:
```bash
kubectl auth can-i update deployments --as=system:serviceaccount:temporal-autoscaler-system:temporal-autoscaler-operator
```

### Cannot connect to Temporal

1. Verify endpoint is reachable:
```bash
kubectl run -it --rm debug --image=busybox --restart=Never -- \
  nc -zv temporal-frontend.temporal.svc.cluster.local 7233
```

2. Check authentication configuration
3. Review operator logs for connection errors

### Lease conflicts

Multiple operator instances may compete for leases. Check:

```bash
# View leases
kubectl get leases -A

# Verify only one operator pod is active
kubectl get pods -n temporal-autoscaler-system
```

## Important Notes

### Temporal API Compatibility

The `TemporalClientFacade` includes a placeholder implementation for queue size calculation. The actual backlog calculation depends on your Temporal version and available APIs.

**TODO for production use:**
1. Verify the Temporal gRPC API methods for your version
2. Implement proper queue size calculation in `calculateBacklogSize()`
3. Handle `buildId`, `selectAllActive`, and `selectUnversioned` filters
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
- GitHub Issues: [repository-url]/issues
- Documentation: [docs-url]

## Roadmap

- [ ] Support for custom metrics from Temporal
- [ ] Advanced scaling policies (predictive scaling)
- [ ] Multi-cluster support
- [ ] Grafana dashboards
- [ ] Helm chart for easy deployment
