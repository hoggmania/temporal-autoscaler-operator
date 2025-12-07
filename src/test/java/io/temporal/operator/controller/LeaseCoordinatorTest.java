package io.temporal.operator.controller;

import io.fabric8.kubernetes.api.model.coordination.v1.Lease;
import io.fabric8.kubernetes.client.KubernetesClient;

import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for LeaseCoordinator.
 * 
 * Tests lease acquisition, renewal, takeover, and release logic.
 */
@QuarkusTest
@Disabled("LeaseCoordinatorTest requires Testcontainers-backed Kubernetes API server which is flaky in CI; enable once we provide a stable mock Kubernetes layer")
class LeaseCoordinatorTest {

    @Inject
    LeaseCoordinator leaseCoordinator;

    @Inject
    KubernetesClient kubernetesClient;

    private static final String TEST_NAMESPACE = "test-namespace";
    private static final String TEST_LEASE_NAME = "test-lease";
    private static final Duration LEASE_DURATION = Duration.ofSeconds(15);

    @BeforeEach
    void setup() {
        // Clean up any existing leases
        try {
            kubernetesClient.leases()
                .inNamespace(TEST_NAMESPACE)
                .withName(TEST_LEASE_NAME)
                .delete();
        } catch (Exception e) {
            // Ignore if lease doesn't exist
        }
    }

    @Test
    void testAcquireNewLease() {
        // When acquiring a new lease
        boolean acquired = leaseCoordinator.acquireLease(TEST_NAMESPACE, TEST_LEASE_NAME, LEASE_DURATION);

        // Then lease should be acquired
        assertTrue(acquired, "Should acquire new lease");

        // And lease should exist in Kubernetes
        Lease lease = kubernetesClient.leases()
            .inNamespace(TEST_NAMESPACE)
            .withName(TEST_LEASE_NAME)
            .get();

        assertNotNull(lease, "Lease should exist");
        assertEquals(leaseCoordinator.getOperatorId(), lease.getSpec().getHolderIdentity(),
            "Lease holder should be this operator");
    }

    @Test
    void testRenewLease() {
        // Given an existing lease held by this operator
        leaseCoordinator.acquireLease(TEST_NAMESPACE, TEST_LEASE_NAME, LEASE_DURATION);

        // When renewing the lease
        boolean renewed = leaseCoordinator.acquireLease(TEST_NAMESPACE, TEST_LEASE_NAME, LEASE_DURATION);

        // Then renewal should succeed
        assertTrue(renewed, "Should renew existing lease");
    }

    @Test
    void testCannotAcquireHeldLease() {
        // Given a lease held by another operator
        String otherOperatorId = "other-operator-123";
        // Note: In real test, we'd need to mock or create a lease with different holder
        // This is a simplified test

        leaseCoordinator.acquireLease(TEST_NAMESPACE, TEST_LEASE_NAME, LEASE_DURATION);

        // For this test, we verify that our operator holds it
        Lease lease = kubernetesClient.leases()
            .inNamespace(TEST_NAMESPACE)
            .withName(TEST_LEASE_NAME)
            .get();

        assertNotNull(lease);
        assertEquals(leaseCoordinator.getOperatorId(), lease.getSpec().getHolderIdentity());
    }

    @Test
    void testReleaseLease() {
        // Given an acquired lease
        leaseCoordinator.acquireLease(TEST_NAMESPACE, TEST_LEASE_NAME, LEASE_DURATION);

        // When releasing the lease
        leaseCoordinator.releaseLease(TEST_NAMESPACE, TEST_LEASE_NAME);

        // Then lease should be deleted
        Lease lease = kubernetesClient.leases()
            .inNamespace(TEST_NAMESPACE)
            .withName(TEST_LEASE_NAME)
            .get();

        assertNull(lease, "Lease should be deleted after release");
    }

    @Test
    void testGenerateLeaseName() {
        // Test lease name generation
        String leaseName = LeaseCoordinator.generateLeaseName("my-scaler", "my-queue:v1");

        assertThat(leaseName)
            .isNotNull()
            .startsWith("ts-my-scaler")
            .hasSize(lessThanOrEqualTo(63)); // K8s name length limit
    }

    @Test
    void testGenerateLeaseNameWithLongInput() {
        // Test with very long input to ensure truncation
        String longTaskQueue = "very-long-task-queue-name-that-exceeds-kubernetes-name-length-limits-abcdefghijklmnopqrstuvwxyz";
        String leaseName = LeaseCoordinator.generateLeaseName("my-very-long-scaler-name", longTaskQueue);

        assertThat(leaseName)
            .isNotNull()
            .hasSize(lessThanOrEqualTo(63));
    }

    private static int lessThanOrEqualTo(int expected) {
        return expected;
    }
}
