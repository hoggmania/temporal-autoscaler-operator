@echo off
REM Build script for Temporal Autoscaler Operator (Windows)

echo Building Temporal Autoscaler Operator...

echo Running Maven build...
call mvnw.cmd clean package -DskipTests
if %ERRORLEVEL% neq 0 exit /b %ERRORLEVEL%

echo Building Podman image...
podman build -f src/main/docker/Dockerfile.jvm -t temporal-autoscaler-operator:latest .
if %ERRORLEVEL% neq 0 exit /b %ERRORLEVEL%

echo Build complete!
echo Podman image: temporal-autoscaler-operator:latest
echo.
echo Next steps:
echo 1. Push to registry: podman tag temporal-autoscaler-operator:latest ^<your-registry^>/temporal-autoscaler-operator:latest
echo 2. Deploy to K8s: kubectl apply -f deploy/
