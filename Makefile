# Makefile for Temporal Autoscaler Operator

.PHONY: help build test clean docker-build docker-push deploy undeploy install-crd uninstall-crd

# Default target
help:
	@echo "Temporal Autoscaler Operator - Makefile targets:"
	@echo ""
	@echo "  build              - Build the operator with Maven"
	@echo "  test               - Run unit tests"
	@echo "  clean              - Clean build artifacts"
	@echo "  podman-build       - Build Podman image"
	@echo "  podman-push        - Push Podman image to registry"
	@echo "  install-crd        - Install CRD to cluster"
	@echo "  uninstall-crd      - Remove CRD from cluster"
	@echo "  deploy             - Deploy operator to cluster"
	@echo "  undeploy           - Remove operator from cluster"
	@echo "  dev                - Run in development mode"
	@echo ""

# Build
build:
	./mvnw clean package -DskipTests

# Test
test:
	./mvnw test

# Clean
clean:
	./mvnw clean

# Podman build
podman-build:
	podman build -f src/main/docker/Dockerfile.jvm -t temporal-autoscaler-operator:latest .

# Podman push (customize with your registry)
podman-push: podman-build
	@echo "Customize REGISTRY variable and uncomment the following line:"
	# podman tag temporal-autoscaler-operator:latest $(REGISTRY)/temporal-autoscaler-operator:latest
	# podman push $(REGISTRY)/temporal-autoscaler-operator:latest

# Install CRD
install-crd:
	kubectl apply -f deploy/crd/temporal-autoscaler-crd.yaml

# Uninstall CRD
uninstall-crd:
	kubectl delete -f deploy/crd/temporal-autoscaler-crd.yaml

# Deploy operator
deploy: install-crd
	kubectl create namespace temporal-autoscaler-system --dry-run=client -o yaml | kubectl apply -f -
	kubectl apply -f deploy/rbac.yaml
	kubectl apply -f deploy/operator-deployment.yaml

# Undeploy operator
undeploy:
	kubectl delete -f deploy/operator-deployment.yaml
	kubectl delete -f deploy/rbac.yaml
	kubectl delete namespace temporal-autoscaler-system

# Development mode
dev:
	./mvnw quarkus:dev
