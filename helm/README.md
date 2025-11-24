# Temporal Autoscaler Operator - Helm Deployment

This directory contains the Helm chart for deploying the Temporal Autoscaler Operator.

## Quick Links

- **[Helm Chart](temporal-autoscaler-operator/README.md)** - Complete Helm chart documentation with all configuration options
- **[OpenShift Deployment Guide](OPENSHIFT_DEPLOYMENT.md)** - Detailed OpenShift deployment instructions
- **[Helm Chart Summary](HELM_CHART_SUMMARY.md)** - Architecture overview and feature list

## Quick Start

### Kubernetes

```bash
helm install temporal-autoscaler ./temporal-autoscaler-operator \
  --namespace temporal-autoscaler-system \
  --create-namespace
```

### OpenShift

```bash
oc new-project temporal-autoscaler-system

helm install temporal-autoscaler ./temporal-autoscaler-operator \
  --namespace temporal-autoscaler-system \
  --values ./temporal-autoscaler-operator/values-openshift.yaml
```

## Files

- `temporal-autoscaler-operator/` - Main Helm chart directory
  - `Chart.yaml` - Chart metadata
  - `values.yaml` - Default configuration values
  - `values-openshift.yaml` - OpenShift-optimized values
  - `values-dev.yaml` - Development/testing values
  - `templates/` - Kubernetes manifest templates
  - `charts/` - Chart dependencies (empty)
  - `.helmignore` - Files to exclude from packaging
  - `README.md` - Complete chart documentation

- `OPENSHIFT_DEPLOYMENT.md` - OpenShift deployment walkthrough
- `HELM_CHART_SUMMARY.md` - Chart features and architecture

## Key Features

### OpenShift Support

- Compatible with restricted-v2 SecurityContextConstraints
- Optional OpenShift Route for external access
- ServiceMonitor for OpenShift monitoring integration
- Non-root containers with proper security contexts

### Production Ready

- High availability with multiple replicas
- Pod disruption budgets
- Health probes (liveness, readiness, startup)
- Resource limits and requests
- Rolling updates

### Flexible Configuration

- Multiple values files for different environments
- Support for external secrets
- Namespace-scoped or cluster-wide operation
- Custom security contexts and RBAC

## Configuration Examples

### With Monitoring

```bash
helm install temporal-autoscaler ./temporal-autoscaler-operator \
  --set monitoring.serviceMonitor.enabled=true
```

### High Availability

```bash
helm install temporal-autoscaler ./temporal-autoscaler-operator \
  --set replicaCount=3 \
  --set podDisruptionBudget.enabled=true
```

### With External Secrets

```bash
helm install temporal-autoscaler ./temporal-autoscaler-operator \
  --set envFrom[0].secretRef.name=temporal-credentials
```

## Validation

```bash
# Lint the chart
helm lint ./temporal-autoscaler-operator

# Dry-run installation
helm install temporal-autoscaler ./temporal-autoscaler-operator \
  --namespace temporal-autoscaler-system \
  --dry-run --debug

# Template output
helm template temporal-autoscaler ./temporal-autoscaler-operator
```

## Support

For detailed documentation, see:

- [Helm Chart README](temporal-autoscaler-operator/README.md)
- [OpenShift Guide](OPENSHIFT_DEPLOYMENT.md)
- [GitHub Issues](https://github.com/hoggmania/temporal-autoscaler-operator/issues)
