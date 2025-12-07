package io.temporal.operator.controller;

import io.fabric8.kubernetes.api.model.coordination.v1.Lease;
import io.fabric8.kubernetes.api.model.coordination.v1.LeaseBuilder;
import io.fabric8.kubernetes.api.model.coordination.v1.LeaseSpec;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.KubernetesClientException;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * LeaseCoordinator manages Kubernetes Lease objects for distributed coordination
 * among operator replicas to prevent concurrent scaling operations.
 * 
 * Safety considerations:
 * - Lease TTL is set to 15 seconds by default, allowing quick failover
 * - Uses optimistic concurrency control via resourceVersion
 * - Tracks held leases to prevent accidental double-acquisition
 */
@ApplicationScoped
public class LeaseCoordinator {

    private static final Logger log = LoggerFactory.getLogger(LeaseCoordinator.class);

    private static final String LEASE_LABEL_KEY = "app.kubernetes.io/component";
    private static final String LEASE_LABEL_VALUE = "temporal-autoscaler";

    @Inject
    KubernetesClient kubernetesClient;

    // Track leases held by this instance
    private final Map<String, String> heldLeases = new ConcurrentHashMap<>();

    // Operator instance ID (pod name or hostname)
    private final String operatorId;

    public LeaseCoordinator() {
        // Get operator ID from environment or generate one
        this.operatorId = System.getenv("HOSTNAME") != null 
            ? System.getenv("HOSTNAME") 
            : "operator-" + System.currentTimeMillis();
    }

    /**
     * Attempts to acquire a lease for a specific scaling target.
     * 
     * @param namespace Kubernetes namespace
     * @param leaseName Unique lease name for the target
     * @param leaseDuration How long the lease should be held
     * @return true if lease was acquired, false otherwise
     */
    public boolean acquireLease(String namespace, String leaseName, Duration leaseDuration) {
        try {
            // Check if we already hold this lease
            if (heldLeases.containsKey(leaseName)) {
                return renewLease(namespace, leaseName, leaseDuration);
            }

            Lease existingLease = kubernetesClient.leases()
                .inNamespace(namespace)
                .withName(leaseName)
                .get();

            ZonedDateTime now = getNow();           
            if (existingLease == null) {
                // Create new lease
                return createLease(namespace, leaseName, leaseDuration);
            } else {
                // Check if lease is expired
                if (isLeaseExpired(existingLease, now)) {
                    // Try to take over the lease
                    return takeOverLease(namespace, existingLease, leaseDuration);
                } else {
                    // Check if we're the current holder
                    String currentHolder = existingLease.getSpec().getHolderIdentity();
                    if (operatorId.equals(currentHolder)) {
                        heldLeases.put(leaseName, existingLease.getMetadata().getResourceVersion());
                        return renewLease(namespace, leaseName, leaseDuration);
                    }
                    log.debug("Lease {} is held by {} until approximately {}", 
                        leaseName, currentHolder, getLeaseExpiration(existingLease));
                    return false;
                }
            }
        } catch (KubernetesClientException e) {
            log.error("Failed to acquire lease {}: {}", leaseName, e.getMessage());
            return false;
        }
    }

    ZonedDateTime getNow() {
        return Instant.now().atZone(java.time.ZoneOffset.UTC);
    }


    /**
     * Creates a new lease.
     */
    private boolean createLease(String namespace, String leaseName, Duration leaseDuration) {
        try {
            ZonedDateTime now = getNow();
            Lease newLease = new LeaseBuilder()
                .withNewMetadata()
                    .withName(leaseName)
                    .withNamespace(namespace)
                    .addToLabels(LEASE_LABEL_KEY, LEASE_LABEL_VALUE)
                .endMetadata()
                .withNewSpec()
                    .withHolderIdentity(operatorId)
                    .withLeaseDurationSeconds((int) leaseDuration.getSeconds())
                    .withAcquireTime(now)
                    .withRenewTime(now)
                .endSpec()
                .build();

            Lease created = kubernetesClient.leases()
                .inNamespace(namespace)
                .resource(newLease)
                .create();

            heldLeases.put(leaseName, created.getMetadata().getResourceVersion());
            log.info("Acquired new lease: {} in namespace {}", leaseName, namespace);
            return true;
        } catch (KubernetesClientException e) {
            // Another instance may have created it concurrently
            log.debug("Failed to create lease {}: {}", leaseName, e.getMessage());
            return false;
        }
    }

    /**
     * Attempts to take over an expired lease.
     */
    private boolean takeOverLease(String namespace, Lease lease, Duration leaseDuration) {
        try {
            ZonedDateTime now = getNow();
            String leaseName = lease.getMetadata().getName();
            
            Lease updated = new LeaseBuilder(lease)
                .editSpec()
                    .withHolderIdentity(operatorId)
                    .withLeaseDurationSeconds((int) leaseDuration.getSeconds())
                    .withAcquireTime(now)
                    .withRenewTime(now)
                .endSpec()
                .build();

            Lease result = kubernetesClient.leases()
                .inNamespace(namespace)
                .resource(updated)
                .update();

            heldLeases.put(leaseName, result.getMetadata().getResourceVersion());
            log.info("Took over expired lease: {} from holder {}", leaseName, lease.getSpec().getHolderIdentity());
            return true;
        } catch (KubernetesClientException e) {
            // Conflict - another instance took it
            log.debug("Failed to take over lease: {}", e.getMessage());
            return false;
        }
    }

    /**
     * Renews an existing lease that we hold.
     */
    private boolean renewLease(String namespace, String leaseName, Duration leaseDuration) {
        try {
            Lease currentLease = kubernetesClient.leases()
                .inNamespace(namespace)
                .withName(leaseName)
                .get();

            if (currentLease == null) {
                heldLeases.remove(leaseName);
                return acquireLease(namespace, leaseName, leaseDuration);
            }

            // Verify we still hold it
            if (!operatorId.equals(currentLease.getSpec().getHolderIdentity())) {
                heldLeases.remove(leaseName);
                log.warn("Lost ownership of lease {}", leaseName);
                return false;
            }

            ZonedDateTime now = getNow();
            Lease updated = new LeaseBuilder(currentLease)
                .editSpec()
                    .withRenewTime(now)
                    .withLeaseDurationSeconds((int) leaseDuration.getSeconds())
                .endSpec()
                .build();

            Lease result = kubernetesClient.leases()
                .inNamespace(namespace)
                .resource(updated)
                .update();

            heldLeases.put(leaseName, result.getMetadata().getResourceVersion());
            log.debug("Renewed lease: {}", leaseName);
            return true;
        } catch (KubernetesClientException e) {
            log.error("Failed to renew lease {}: {}", leaseName, e.getMessage());
            heldLeases.remove(leaseName);
            return false;
        }
    }

    /**
     * Releases a lease held by this operator instance.
     */
    public void releaseLease(String namespace, String leaseName) {
        try {
            if (!heldLeases.containsKey(leaseName)) {
                log.debug("Not holding lease {}, nothing to release", leaseName);
                return;
            }

            Lease lease = kubernetesClient.leases()
                .inNamespace(namespace)
                .withName(leaseName)
                .get();

            if (lease != null && operatorId.equals(lease.getSpec().getHolderIdentity())) {
                // Option 1: Delete the lease
                kubernetesClient.leases()
                    .inNamespace(namespace)
                    .withName(leaseName)
                    .delete();
                
                log.info("Released lease: {}", leaseName);
            }
            
            heldLeases.remove(leaseName);
        } catch (KubernetesClientException e) {
            log.error("Failed to release lease {}: {}", leaseName, e.getMessage());
            heldLeases.remove(leaseName);
        }
    }

    /**
     * Checks if a lease has expired.
     */
    private boolean isLeaseExpired(Lease lease, ZonedDateTime now) {
        LeaseSpec spec = lease.getSpec();
        if (spec.getRenewTime() == null || spec.getLeaseDurationSeconds() == null) {
            return true;
        }

        try {
            ZonedDateTime renewTime = spec.getRenewTime();
            long durationSeconds = spec.getLeaseDurationSeconds();
            ZonedDateTime expirationTime = renewTime.plusSeconds(durationSeconds);
            
            return now.isAfter(expirationTime);
        } catch (Exception e) {
            log.warn("Failed to parse lease times: {}", e.getMessage());
            return true;
        }
    }

    /**
     * Gets the expiration time of a lease.
     */
    private ZonedDateTime getLeaseExpiration(Lease lease) {
        LeaseSpec spec = lease.getSpec();
        if (spec.getRenewTime() == null || spec.getLeaseDurationSeconds() == null) {
            return getNow();
        }

        try {
            ZonedDateTime renewTime = spec.getRenewTime();
            return renewTime.plusSeconds(spec.getLeaseDurationSeconds());
        } catch (Exception e) {
            return getNow();
        }
    }

    /**
     * Generates a lease name for a scaling target.
     */
    public static String generateLeaseName(String scalerName, String taskQueue) {
        String sanitized = taskQueue.replaceAll("[^a-z0-9-]", "-").toLowerCase();
        String name = String.format("ts-%s-%s", scalerName, sanitized);
        // Ensure name doesn't exceed 63 characters (K8s limit)
        if (name.length() > 63) {
            name = name.substring(0, 63);
        }
        return name;
    }

    public String getOperatorId() {
        return operatorId;
    }

    /**
     * Cleanup method to release all held leases.
     */
    public void releaseAllLeases(String namespace) {
        heldLeases.keySet().forEach(leaseName -> releaseLease(namespace, leaseName));
    }
}
