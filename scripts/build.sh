#!/bin/bash

# Build script for Temporal Autoscaler Operator

set -e

echo "Building Temporal Autoscaler Operator..."

# Build with Maven
echo "Running Maven build..."
./mvnw clean package -DskipTests

# Build Podman image
echo "Building Podman image..."
podman build -f src/main/docker/Dockerfile.jvm -t temporal-autoscaler-operator:latest .

echo "Build complete!"
echo "Podman image: temporal-autoscaler-operator:latest"
echo ""
echo "Next steps:"
echo "1. Push to registry: podman tag temporal-autoscaler-operator:latest <your-registry>/temporal-autoscaler-operator:latest"
echo "2. Deploy to K8s: kubectl apply -f deploy/"
