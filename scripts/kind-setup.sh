#!/bin/bash

# Deploy script for Temporal Autoscaler Operator to Kind cluster

set -e

CLUSTER_NAME=${1:-temporal-autoscaler}

echo "Setting up Kind cluster: $CLUSTER_NAME"

# Create Kind cluster if it doesn't exist
if ! kind get clusters | grep -q "^${CLUSTER_NAME}$"; then
    echo "Creating Kind cluster..."
    kind create cluster --name "$CLUSTER_NAME"
else
    echo "Kind cluster already exists"
fi

# Set kubectl context
kubectl cluster-info --context "kind-${CLUSTER_NAME}"

echo "Creating namespace..."
kubectl create namespace temporal-autoscaler-system --dry-run=client -o yaml | kubectl apply -f -

echo "Deploying CRD..."
kubectl apply -f deploy/crd/temporal-autoscaler-crd.yaml

echo "Deploying RBAC..."
kubectl apply -f deploy/rbac.yaml

echo "Loading Podman image to Kind..."
kind load image-archive <(podman save temporal-autoscaler-operator:latest) --name "$CLUSTER_NAME"

echo "Deploying operator..."
kubectl apply -f deploy/operator-deployment.yaml

echo "Waiting for operator to be ready..."
kubectl wait --for=condition=ready pod -l app=temporal-autoscaler-operator -n temporal-autoscaler-system --timeout=120s

echo ""
echo "Deployment complete!"
echo ""
echo "Verify deployment:"
echo "  kubectl get pods -n temporal-autoscaler-system"
echo ""
echo "View logs:"
echo "  kubectl logs -f -n temporal-autoscaler-system -l app=temporal-autoscaler-operator"
echo ""
echo "Deploy example:"
echo "  kubectl apply -f deploy/examples/temporal-scaler-example.yaml"
