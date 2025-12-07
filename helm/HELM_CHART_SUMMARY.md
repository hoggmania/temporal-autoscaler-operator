# Helm Chart Summary

## Overview

A complete Helm chart has been created for deploying the Temporal Autoscaler Operator on Kubernetes and OpenShift clusters.

## Directory Structure

```
helm/temporal-autoscaler-operator/
├── Chart.yaml                      # Chart metadata
├── values.yaml                     # Default configuration values
├── values-openshift.yaml           # OpenShift-specific values
├── values-dev.yaml                 # Development/testing values
├── .helmignore                     # Files to exclude from packaging
├── README.md                       # Chart documentation
├── templates/                      # Kubernetes manifest templates
│   ├── _helpers.tpl                # Template helper functions
│   ├── NOTES.txt                   # Post-installation notes
│   ├── serviceaccount.yaml         # ServiceAccount resource
│   ├── rbac.yaml                   # ClusterRole and ClusterRoleBinding
│   ├── crd.yaml                    # CustomResourceDefinition
│   ├── deployment.yaml             # Operator Deployment
│   ├── service.yaml                # Service for metrics
│   ├── configmap.yaml              # Configuration ConfigMap
│   ├── servicemonitor.yaml         # Prometheus ServiceMonitor
│   ├── route.yaml                  # OpenShift Route
│   ├── poddisruptionbudget.yaml    # PodDisruptionBudget
│   └── hpa.yaml                    # HorizontalPodAutoscaler
└── OPENSHIFT_DEPLOYMENT.md         # OpenShift deployment guide

```

## Key Features

### 1. OpenShift Compatibility

- **SecurityContextConstraints**: Compatible with restricted-v2 SCC
- **Route Support**: Optional OpenShift Route for external access
- **Non-root Containers**: Runs with OpenShift-assigned UIDs
- **Security Context**: Drops all capabilities, no privilege escalation
- **Monitoring Integration**: ServiceMonitor for OpenShift monitoring stack

### 2. Flexible Configuration

- **Values Files**: Default, OpenShift-specific, and development configurations
- **Environment Variables**: Configurable via values or secrets/configmaps
- **Resource Management**: Customizable CPU/memory limits and requests
- **Namespace Watching**: Cluster-wide or namespace-scoped operation
- **Authentication**: Support for API keys and mTLS certificates

### 3. Production Ready

- **High Availability**: Multiple replicas with pod disruption budgets
- **Health Checks**: Liveness, readiness, and startup probes
- **Resource Limits**: Sensible defaults with customization options
- **Rolling Updates**: Zero-downtime deployments
- **Monitoring**: Prometheus metrics and ServiceMonitor

### 4. Security Features

- **RBAC**: Least-privilege ClusterRole and ClusterRoleBinding
- **Security Context**: Non-root, read-only root filesystem support
- **Secret Management**: Support for external secrets via envFrom
- **TLS/mTLS**: Support for secure Temporal connections
- **Pod Security**: Compatible with restricted security standards

### 5. Worker Versioning Awareness

- **Backlog source**: Prefers Temporal's Worker Deployment + Versioning Rules APIs for queue stats
- **Automatic discovery**: Resolves build IDs/deployments directly from Temporal instead of requiring CRD fields
- **Graceful fallback**: Uses `DescribeTaskQueue` with enhanced stats only when Worker Versioning RPCs are unavailable

## Installation

### Quick Install on Kubernetes

```bash
helm install temporal-autoscaler ./helm/temporal-autoscaler-operator \
  --namespace temporal-autoscaler-system \
  --create-namespace
```

### Quick Install on OpenShift

```bash
oc new-project temporal-autoscaler-system

helm install temporal-autoscaler ./helm/temporal-autoscaler-operator \
  --namespace temporal-autoscaler-system \
  --values ./helm/temporal-autoscaler-operator/values-openshift.yaml
```

## Configuration Highlights

### Default Values (values.yaml)

- **Image**: quay.io/hoggmania/temporal-autoscaler-operator
- **Replicas**: 1
- **Resources**: 100m CPU / 128Mi memory requests, 500m CPU / 512Mi memory limits
- **Security**: Non-root, capabilities dropped, seccomp profile
- **CRD**: Installed and kept on uninstall
- **RBAC**: Cluster-wide permissions

### OpenShift Values (values-openshift.yaml)

- **Route**: Enabled with edge TLS termination
- **SCC**: Uses restricted-v2
- **Monitoring**: ServiceMonitor enabled with cluster-monitoring label
- **Security**: OpenShift-compatible security contexts
- **PodDisruptionBudget**: Enabled with minAvailable=1

### Development Values (values-dev.yaml)

- **Logging**: DEBUG level
- **Resources**: Lower limits for dev environments
- **Namespace**: Watches only 'default' namespace
- **CRD**: Not kept on uninstall
- **Image**: Uses localhost registry

## Key Templates

### 1. deployment.yaml

- Main operator deployment
- Configurable replicas, resources, security contexts
- Health probes for liveness, readiness, startup
- Volume mounts for secrets (certificates)
- Environment variables from values and secrets

### 2. rbac.yaml

- ClusterRole with permissions for:
  - TemporalScaler CRD operations
  - Scaling Deployments, StatefulSets, ReplicaSets
  - Lease coordination for distributed locking
  - Events for audit trail
  - ConfigMaps for leader election
- ClusterRoleBinding to ServiceAccount

### 3. crd.yaml

- Complete TemporalScaler CRD definition
- OpenAPI v3 schema validation
- Custom metrics support
- Status subresource
- Additional printer columns
- Optional keep annotation

### 4. route.yaml (OpenShift)

- Conditional creation based on openshift.route.enabled
- Configurable hostname
- TLS termination (edge, passthrough, reencrypt)
- Insecure redirect policy

### 5. servicemonitor.yaml

- Prometheus ServiceMonitor for metrics
- Configurable scrape interval and timeout
- Label selectors for monitoring
- OpenShift cluster-monitoring label support

## Validation

To validate the chart before installation:

```bash
# Lint the chart
helm lint ./helm/temporal-autoscaler-operator

# Dry-run installation
helm install temporal-autoscaler ./helm/temporal-autoscaler-operator \
  --namespace temporal-autoscaler-system \
  --dry-run --debug

# Template output
helm template temporal-autoscaler ./helm/temporal-autoscaler-operator \
  --namespace temporal-autoscaler-system
```

## Customization Examples

### 1. High Availability

```bash
helm install temporal-autoscaler ./helm/temporal-autoscaler-operator \
  --set replicaCount=3 \
  --set podDisruptionBudget.enabled=true \
  --set podDisruptionBudget.minAvailable=2
```

### 2. Custom Resources

```bash
helm install temporal-autoscaler ./helm/temporal-autoscaler-operator \
  --set resources.requests.cpu=200m \
  --set resources.requests.memory=256Mi \
  --set resources.limits.cpu=1000m \
  --set resources.limits.memory=1Gi
```

### 3. With Monitoring

```bash
helm install temporal-autoscaler ./helm/temporal-autoscaler-operator \
  --set monitoring.serviceMonitor.enabled=true \
  --set monitoring.serviceMonitor.labels."prometheus"=kube-prometheus
```

### 4. Namespace-Scoped

```bash
helm install temporal-autoscaler ./helm/temporal-autoscaler-operator \
  --set rbac.clusterWide=false \
  --set env.quarkusOperatorSdkControllersTemporalscalerNamespaces="app1,app2"
```

### 5. With External Secrets

```bash
helm install temporal-autoscaler ./helm/temporal-autoscaler-operator \
  --set envFrom[0].secretRef.name=temporal-credentials \
  --set volumes[0].name=certs \
  --set volumes[0].secret.secretName=temporal-certs \
  --set volumeMounts[0].name=certs \
  --set volumeMounts[0].mountPath=/certs
```

## OpenShift Specific Features

### 1. SecurityContextConstraints

The chart is compatible with OpenShift's restricted-v2 SCC by default:

- Non-root user (OpenShift assigns UID)
- No privilege escalation
- All capabilities dropped
- Seccomp profile enabled

### 2. Route

OpenShift Route provides external access:

- Automatic hostname generation or custom host
- TLS termination (edge, passthrough, reencrypt)
- Insecure traffic redirection

### 3. Monitoring

Integrates with OpenShift monitoring stack:

- ServiceMonitor with cluster-monitoring label
- Metrics available in OpenShift console
- Grafana dashboard integration

## Upgrade and Rollback

### Upgrade

```bash
helm upgrade temporal-autoscaler ./helm/temporal-autoscaler-operator \
  --namespace temporal-autoscaler-system \
  --values my-values.yaml
```

### Rollback

```bash
helm rollback temporal-autoscaler -n temporal-autoscaler-system
```

### View History

```bash
helm history temporal-autoscaler -n temporal-autoscaler-system
```

## Uninstall

```bash
# Uninstall release (keeps CRDs by default)
helm uninstall temporal-autoscaler -n temporal-autoscaler-system

# Manually delete CRD if needed
kubectl delete crd temporalscalers.scaling.example.com
```

## Testing

### Unit Testing

```bash
# Lint chart
helm lint ./helm/temporal-autoscaler-operator

# Test with different values
helm lint ./helm/temporal-autoscaler-operator \
  --values ./helm/temporal-autoscaler-operator/values-openshift.yaml
```

### Integration Testing

```bash
# Install in test namespace
helm install test ./helm/temporal-autoscaler-operator \
  --namespace test-operator \
  --create-namespace \
  --wait

# Verify installation
kubectl get all -n test-operator
kubectl get crd temporalscalers.scaling.example.com

# Cleanup
helm uninstall test -n test-operator
kubectl delete namespace test-operator
```

## Best Practices Implemented

1. **Template Helpers**: Reusable template functions in _helpers.tpl
2. **Default Values**: Sensible defaults with override capability
3. **Documentation**: Comprehensive README and NOTES.txt
4. **Security**: Restrictive security contexts by default
5. **Monitoring**: Built-in Prometheus integration
6. **High Availability**: Optional multi-replica deployment
7. **Resource Management**: CPU/memory limits defined
8. **Health Checks**: Liveness, readiness, startup probes
9. **Graceful Shutdown**: Termination grace period configured
10. **CRD Management**: Keep CRDs on uninstall option

## Troubleshooting

Common issues and solutions are documented in:

- `helm/temporal-autoscaler-operator/README.md` - General troubleshooting
- `helm/OPENSHIFT_DEPLOYMENT.md` - OpenShift-specific troubleshooting

## Future Enhancements

Potential improvements:

1. OLM (Operator Lifecycle Manager) bundle for OpenShift
2. Additional examples for common scenarios
3. Pre-upgrade/post-upgrade hooks
4. Chart tests for automated validation
5. ArgoCD/Flux GitOps integration examples
6. Multi-cluster deployment patterns

## Support

For issues and questions:

- Chart Issues: GitHub Issues
- OpenShift Documentation: `helm/OPENSHIFT_DEPLOYMENT.md`
- General Documentation: Project README.md
