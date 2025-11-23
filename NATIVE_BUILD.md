# Building Native Executable

## Prerequisites

- GraalVM 21 or Mandrel 21
- Native image toolchain (gcc, glibc-devel, zlib-devel)

## Build Native Executable

### Option 1: Local Build (requires GraalVM installed)

```bash
# Set GRAALVM_HOME
export GRAALVM_HOME=/path/to/graalvm

# Build native executable
./mvnw package -Pnative -DskipTests

# The native executable will be at:
# target/temporal-autoscaler-operator-1.0.0-SNAPSHOT-runner
```

### Option 2: Container Build (recommended, no local GraalVM needed)

```bash
# Build using container
./mvnw package -Pnative -Dquarkus.native.container-build=true -DskipTests

# Build Podman image with native executable
podman build -f src/main/docker/Dockerfile.native -t temporal-autoscaler-operator:native .
```

### Option 3: Multistage Podman Build

```bash
# Build everything in Podman
podman build -f src/main/docker/Dockerfile.native-multistage -t temporal-autoscaler-operator:native .
```

## Test Native Executable Locally

```bash
# Run the native executable
./target/temporal-autoscaler-operator-1.0.0-SNAPSHOT-runner
```

## Native Build Configuration

Native compilation is configured via:
- `application-native.properties` - Native-specific runtime configuration
- `META-INF/native-image/reflect-config.json` - Reflection configuration for Kubernetes and Temporal models
- `META-INF/native-image/jni-config.json` - JNI configuration for SSL/security
- `META-INF/native-image/resource-config.json` - Resource includes

## Common Issues

### SSL/TLS Issues
If you encounter SSL issues, ensure:
- `quarkus.ssl.native=true` is set
- All security providers are registered in jni-config.json

### Reflection Issues
Add missing classes to `reflect-config.json`:
```json
{
  "name": "your.missing.Class",
  "allDeclaredFields": true,
  "allDeclaredMethods": true,
  "allDeclaredConstructors": true
}
```

### Memory Issues During Build
Increase native image memory:
```bash
./mvnw package -Pnative -Dquarkus.native.native-image-xmx=8g
```

## Performance

Native executables offer:
- **Fast startup**: ~50ms vs several seconds for JVM
- **Low memory**: ~50MB RSS vs 200-300MB for JVM
- **Instant peak performance**: No warmup needed

Perfect for Kubernetes operators with many replicas!
