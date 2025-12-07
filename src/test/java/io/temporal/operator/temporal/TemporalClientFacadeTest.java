package io.temporal.operator.temporal;

import io.temporal.operator.temporal.TemporalClientFacade.TemporalConnectionConfig;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for TemporalClientFacade.
 * 
 * Note: These are basic tests. Full integration testing requires a running Temporal server.
 */
@QuarkusTest
class TemporalClientFacadeTest {

    @Inject
    TemporalClientFacade temporalClient;

    @Test
    void testTemporalConnectionConfigBuilder() {
        // Test building a connection config
        TemporalConnectionConfig config = new TemporalConnectionConfig();
        config.setEndpoint("localhost:7233");
        config.setNamespace("default");
        config.setTaskQueue("test-queue");
        config.setQueueTypes("workflow");
        config.setApiKey("test-api-key");

        assertThat(config.getEndpoint()).isEqualTo("localhost:7233");
        assertThat(config.getNamespace()).isEqualTo("default");
        assertThat(config.getTaskQueue()).isEqualTo("test-queue");
        assertThat(config.getQueueTypes()).isEqualTo("workflow");
        assertThat(config.getApiKey()).isEqualTo("test-api-key");
        assertThat(config.hasMtlsConfig()).isFalse();
    }

    @Test
    void testMtlsConfigDetection() {
        TemporalConnectionConfig config = new TemporalConnectionConfig();
        config.setCaCert("ca-cert-content");
        config.setClientCert("client-cert-content");
        config.setClientKey("client-key-content");

        assertThat(config.hasMtlsConfig()).isTrue();
    }

    @Test
    void testConnectionConfigWithTimeout() {
        TemporalConnectionConfig config = new TemporalConnectionConfig();
        config.setMinConnectTimeout(Duration.ofSeconds(30));

        assertThat(config.getMinConnectTimeout()).isEqualTo(Duration.ofSeconds(30));
    }

    @Test
    void testUnsafeSslConfig() {
        TemporalConnectionConfig config = new TemporalConnectionConfig();
        config.setUnsafeSsl(true);

        assertThat(config.isUnsafeSsl()).isTrue();
    }

    @Test
    void testTlsServerName() {
        TemporalConnectionConfig config = new TemporalConnectionConfig();
        config.setTlsServerName("temporal.example.com");

        assertThat(config.getTlsServerName()).isEqualTo("temporal.example.com");
    }

    /**
     * Note: Testing actual Temporal connections requires a running Temporal server.
     * In production, you would:
     * 1. Set up a test Temporal server or use Temporal's test server
     * 2. Create integration tests that verify actual queue queries
     * 3. Mock the gRPC responses for unit tests
     * 
     * For now, this test validates the configuration setup.
     */
    @Test
    void testGetTaskQueueSize_WithInvalidConfig() {
        // Given an invalid config (no endpoint)
        TemporalConnectionConfig config = new TemporalConnectionConfig();
        config.setNamespace("default");
        config.setTaskQueue("test-queue");

        // When querying queue size
        long queueSize = temporalClient.getTaskQueueSize(config);

        // Then should return error value (-1)
        assertThat(queueSize).isEqualTo(-1);
    }

    /**
     * TODO: Add integration test with mock Temporal gRPC server
     * 
     * Example test structure:
     * 
     * @Test
     * void testGetTaskQueueSize_WithMockServer() {
     *     // Set up mock gRPC server
     *     // Configure expected request/response
     *     // Execute getTaskQueueSize
     *     // Verify response
     * }
     */
}
