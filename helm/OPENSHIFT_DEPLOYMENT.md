# Deploying Temporal Autoscaler Operator on OpenShift

This guide walks through deploying the Temporal Autoscaler Operator on OpenShift using Helm.

## Prerequisites

- OpenShift 4.8 or later
- Helm 3.0 or later installed
- `oc` CLI configured and authenticated
- Cluster admin privileges (for CRD installation)
- Access to a Temporal cluster

## Quick Start

### 1. Create a Project

```bash
oc new-project temporal-autoscaler-system
```

### 2. Install the Operator with Helm

```bash
helm install temporal-autoscaler ./helm/temporal-autoscaler-operator \
  --namespace temporal-autoscaler-system \
  --values ./helm/temporal-autoscaler-operator/values-openshift.yaml
```

### 3. Verify Installation

```bash
# Check operator pod
oc get pods -n temporal-autoscaler-system

# Check operator logs
oc logs -f -n temporal-autoscaler-system deployment/temporal-autoscaler-operator

# Check CRD installation
oc get crd temporalscalers.scaling.example.com

# Check route (if enabled)
oc get route -n temporal-autoscaler-system
```

### 4. Create a TemporalScaler Resource

```bash
cat <<EOF | oc apply -f -
apiVersion: scaling.example.com/v1alpha1
kind: TemporalScaler
metadata:
  name: example-worker-scaler
  namespace: default
spec:
  scaleTargetRef:
    apiVersion: apps/v1
    kind: Deployment
    name: my-temporal-worker
  pollingInterval: 5
  cooldownPeriod: 10
  minReplicaCount: 1
  maxReplicaCount: 10
  triggers:
    - type: temporal
      metadata:
        endpoint: temporal-frontend.temporal.svc.cluster.local:7233
        namespace: default
        taskQueue: my-workflow-queue
        queueTypes: workflow
        targetQueueSize: "5"
        activationTargetQueueSize: "1"
EOF
```

### 5. Monitor Scaling

```bash
# Watch TemporalScaler status
oc get temporalscalers -n default -w

# Check target deployment
oc get deployment my-temporal-worker -n default

# View scaling events
oc get events -n default --sort-by='.lastTimestamp'
```

## Configuration Options

### Enable OpenShift Route

```bash
helm upgrade temporal-autoscaler ./helm/temporal-autoscaler-operator \
  --namespace temporal-autoscaler-system \
  --reuse-values \
  --set openshift.route.enabled=true \
  --set openshift.route.host=temporal-operator.apps.your-cluster.com
```

### Enable Monitoring with Prometheus

```bash
helm upgrade temporal-autoscaler ./helm/temporal-autoscaler-operator \
  --namespace temporal-autoscaler-system \
  --reuse-values \
  --set monitoring.serviceMonitor.enabled=true \
  --set monitoring.serviceMonitor.labels."openshift\.io/cluster-monitoring"=true
```

### Use Custom Security Context Constraints

```bash
# Create custom SCC if needed
oc create -f - <<EOF
apiVersion: security.openshift.io/v1
kind: SecurityContextConstraints
metadata:
  name: temporal-autoscaler-scc
allowHostDirVolumePlugin: false
allowHostIPC: false
allowHostNetwork: false
allowHostPID: false
allowHostPorts: false
allowPrivilegeEscalation: false
allowPrivilegedContainer: false
allowedCapabilities: []
defaultAddCapabilities: []
fsGroup:
  type: MustRunAs
priority: 10
readOnlyRootFilesystem: false
requiredDropCapabilities:
  - ALL
runAsUser:
  type: MustRunAsRange
seLinuxContext:
  type: MustRunAs
supplementalGroups:
  type: RunAsAny
volumes:
  - configMap
  - downwardAPI
  - emptyDir
  - persistentVolumeClaim
  - projected
  - secret
users:
  - system:serviceaccount:temporal-autoscaler-system:temporal-autoscaler-operator
EOF

# Update Helm deployment to use custom SCC
helm upgrade temporal-autoscaler ./helm/temporal-autoscaler-operator \
  --namespace temporal-autoscaler-system \
  --reuse-values \
  --set openshift.scc.name=temporal-autoscaler-scc
```

## Authentication with Temporal Cloud

### Using API Key

```bash
# Create secret with API key
oc create secret generic temporal-credentials \
  --from-literal=TEMPORAL_API_KEY=your-api-key \
  --namespace temporal-autoscaler-system

# Install operator with secret reference
helm install temporal-autoscaler ./helm/temporal-autoscaler-operator \
  --namespace temporal-autoscaler-system \
  --values ./helm/temporal-autoscaler-operator/values-openshift.yaml \
  --set envFrom[0].secretRef.name=temporal-credentials
```

### Using mTLS Certificates

```bash
# Create secret with certificates
oc create secret generic temporal-mtls-certs \
  --from-file=ca.crt=path/to/ca.crt \
  --from-file=tls.crt=path/to/client.crt \
  --from-file=tls.key=path/to/client.key \
  --namespace temporal-autoscaler-system

# Install operator with volume mounts
helm install temporal-autoscaler ./helm/temporal-autoscaler-operator \
  --namespace temporal-autoscaler-system \
  --values ./helm/temporal-autoscaler-operator/values-openshift.yaml \
  --set volumes[0].name=temporal-certs \
  --set volumes[0].secret.secretName=temporal-mtls-certs \
  --set volumeMounts[0].name=temporal-certs \
  --set volumeMounts[0].mountPath=/certs \
  --set volumeMounts[0].readOnly=true
```

## High Availability Setup

For production deployments with high availability:

```bash
helm install temporal-autoscaler ./helm/temporal-autoscaler-operator \
  --namespace temporal-autoscaler-system \
  --values ./helm/temporal-autoscaler-operator/values-openshift.yaml \
  --set replicaCount=3 \
  --set podDisruptionBudget.enabled=true \
  --set podDisruptionBudget.minAvailable=2 \
  --set resources.requests.cpu=200m \
  --set resources.requests.memory=256Mi \
  --set resources.limits.cpu=1000m \
  --set resources.limits.memory=1Gi \
  --set affinity.podAntiAffinity.preferredDuringSchedulingIgnoredDuringExecution[0].weight=100 \
  --set affinity.podAntiAffinity.preferredDuringSchedulingIgnoredDuringExecution[0].podAffinityTerm.labelSelector.matchLabels."app\.kubernetes\.io/name"=temporal-autoscaler-operator \
  --set affinity.podAntiAffinity.preferredDuringSchedulingIgnoredDuringExecution[0].podAffinityTerm.topologyKey=kubernetes.io/hostname
```

## Namespace-Scoped Deployment

To watch only specific namespaces instead of cluster-wide:

```bash
# Create role and rolebinding in target namespaces
oc create rolebinding temporal-autoscaler-operator \
  --clusterrole=temporal-autoscaler-operator \
  --serviceaccount=temporal-autoscaler-system:temporal-autoscaler-operator \
  --namespace=my-app-namespace

# Install operator to watch specific namespaces
helm install temporal-autoscaler ./helm/temporal-autoscaler-operator \
  --namespace temporal-autoscaler-system \
  --values ./helm/temporal-autoscaler-operator/values-openshift.yaml \
  --set rbac.clusterWide=false \
  --set env.quarkusOperatorSdkControllersTemporalscalerNamespaces="my-app-namespace,another-namespace"
```

## Troubleshooting

### Check Operator Status

```bash
# Check deployment
oc get deployment -n temporal-autoscaler-system

# Check pod status
oc get pods -n temporal-autoscaler-system

# Check pod logs
oc logs -f -n temporal-autoscaler-system deployment/temporal-autoscaler-operator

# Describe pod for events
oc describe pod -n temporal-autoscaler-system -l app.kubernetes.io/name=temporal-autoscaler-operator
```

### Verify RBAC Permissions

```bash
# Check service account
oc get sa temporal-autoscaler-operator -n temporal-autoscaler-system

# Check cluster role
oc get clusterrole temporal-autoscaler-operator

# Check cluster role binding
oc get clusterrolebinding temporal-autoscaler-operator

# Test permissions
oc auth can-i update deployments \
  --as=system:serviceaccount:temporal-autoscaler-system:temporal-autoscaler-operator
```

### Check Security Context

```bash
# Check which SCC is being used
oc get pod -n temporal-autoscaler-system -o yaml | grep -A 5 "openshift.io/scc"

# Check pod security context
oc get pod -n temporal-autoscaler-system -o jsonpath='{.items[0].spec.securityContext}'

# Check container security context
oc get pod -n temporal-autoscaler-system -o jsonpath='{.items[0].spec.containers[0].securityContext}'
```

### Verify CRD Installation

```bash
# Check CRD
oc get crd temporalscalers.scaling.example.com

# Describe CRD
oc describe crd temporalscalers.scaling.example.com

# List all TemporalScaler resources
oc get temporalscalers --all-namespaces
```

### Check Route Access

```bash
# Get route URL
oc get route temporal-autoscaler-operator -n temporal-autoscaler-system -o jsonpath='{.spec.host}'

# Test health endpoint
ROUTE_URL=$(oc get route temporal-autoscaler-operator -n temporal-autoscaler-system -o jsonpath='{.spec.host}')
curl -k https://$ROUTE_URL/q/health/live
```

### Debugging Connection Issues

```bash
# Test Temporal connectivity from operator pod
POD_NAME=$(oc get pods -n temporal-autoscaler-system -l app.kubernetes.io/name=temporal-autoscaler-operator -o jsonpath='{.items[0].metadata.name}')

# Check DNS resolution
oc exec -n temporal-autoscaler-system $POD_NAME -- nslookup temporal-frontend.temporal.svc.cluster.local

# Check network connectivity
oc exec -n temporal-autoscaler-system $POD_NAME -- nc -zv temporal-frontend.temporal.svc.cluster.local 7233
```

## Monitoring and Observability

### Access Metrics

```bash
# If ServiceMonitor is enabled
oc get servicemonitor -n temporal-autoscaler-system

# Port-forward to access metrics directly
oc port-forward -n temporal-autoscaler-system service/temporal-autoscaler-operator 8080:8080

# View metrics
curl http://localhost:8080/q/metrics
```

### View in OpenShift Console

1. Navigate to Observe → Metrics in OpenShift console
2. Query operator metrics:
   - `temporal_queue_size{namespace="default"}`
   - `temporal_scaling_events_total`
   - `temporal_operator_reconcile_duration_seconds`

### Create Custom Dashboards

Import Grafana dashboards using Prometheus queries:
- Queue sizes over time
- Scaling events
- Reconciliation latency
- Current vs desired replicas

## Upgrading

```bash
# Upgrade to new version
helm upgrade temporal-autoscaler ./helm/temporal-autoscaler-operator \
  --namespace temporal-autoscaler-system \
  --values ./helm/temporal-autoscaler-operator/values-openshift.yaml

# Upgrade with new values
helm upgrade temporal-autoscaler ./helm/temporal-autoscaler-operator \
  --namespace temporal-autoscaler-system \
  --values my-custom-values.yaml
```

## Uninstalling

```bash
# Delete all TemporalScaler resources first
oc delete temporalscalers --all --all-namespaces

# Uninstall Helm release
helm uninstall temporal-autoscaler -n temporal-autoscaler-system

# Optionally delete CRD
oc delete crd temporalscalers.scaling.example.com

# Delete project
oc delete project temporal-autoscaler-system
```

## Best Practices

1. **Resource Limits**: Always set appropriate resource limits for production
2. **High Availability**: Run multiple replicas with pod disruption budgets
3. **Security**: Use mTLS for Temporal connections in production
4. **Monitoring**: Enable ServiceMonitor for Prometheus integration
5. **Namespace Isolation**: Consider namespace-scoped deployments for multi-tenancy
6. **Backup**: Keep CRDs on uninstall (`crd.keep=true`)
7. **Testing**: Test scaling behavior in non-production before deploying to production

## Support

For issues and questions:
- GitHub: https://github.com/hoggmania/temporal-autoscaler-operator
- OpenShift Documentation: https://docs.openshift.com
