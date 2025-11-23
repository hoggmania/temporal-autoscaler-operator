package io.temporal.operator.controller;

import io.temporal.operator.model.*;
import io.fabric8.kubernetes.api.model.ObjectMetaBuilder;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for TemporalScaler model classes.
 */
@QuarkusTest
class TemporalScalerModelTest {

    @Test
    void testTemporalScalerCreation() {
        TemporalScaler scaler = new TemporalScaler();
        scaler.setMetadata(new ObjectMetaBuilder()
            .withName("test-scaler")
            .withNamespace("default")
            .build());

        TemporalScalerSpec spec = new TemporalScalerSpec();
        spec.setMinReplicaCount(0);
        spec.setMaxReplicaCount(10);
        spec.setPollingInterval(5);
        spec.setCooldownPeriod(10);

        ScaleTargetRef targetRef = new ScaleTargetRef();
        targetRef.setApiVersion("apps/v1");
        targetRef.setKind("Deployment");
        targetRef.setName("test-deployment");
        spec.setScaleTargetRef(targetRef);

        List<Trigger> triggers = new ArrayList<>();
        Trigger trigger = new Trigger();
        trigger.setType("temporal");
        
        Map<String, String> metadata = new HashMap<>();
        metadata.put("endpoint", "temporal:7233");
        metadata.put("namespace", "default");
        metadata.put("taskQueue", "test-queue");
        metadata.put("targetQueueSize", "5");
        trigger.setMetadata(metadata);
        
        triggers.add(trigger);
        spec.setTriggers(triggers);

        scaler.setSpec(spec);

        assertThat(scaler.getMetadata().getName()).isEqualTo("test-scaler");
        assertThat(scaler.getSpec().getMinReplicaCount()).isEqualTo(0);
        assertThat(scaler.getSpec().getMaxReplicaCount()).isEqualTo(10);
        assertThat(scaler.getSpec().getScaleTargetRef().getName()).isEqualTo("test-deployment");
        assertThat(scaler.getSpec().getTriggers()).hasSize(1);
    }

    @Test
    void testTriggerMetadataAccess() {
        Trigger trigger = new Trigger();
        Map<String, String> metadata = new HashMap<>();
        metadata.put("endpoint", "temporal:7233");
        metadata.put("taskQueue", "test-queue");
        trigger.setMetadata(metadata);

        assertThat(trigger.getMetadataValue("endpoint")).isEqualTo("temporal:7233");
        assertThat(trigger.getMetadataValue("taskQueue")).isEqualTo("test-queue");
        assertThat(trigger.getMetadataValue("nonexistent")).isNull();
        assertThat(trigger.getMetadataValue("nonexistent", "default")).isEqualTo("default");
    }

    @Test
    void testStatusConditions() {
        TemporalScalerStatus status = new TemporalScalerStatus();
        
        TemporalScalerStatus.Condition condition = new TemporalScalerStatus.Condition(
            "Ready", "True", "AllTriggersHealthy", "All triggers are functioning normally"
        );

        status.getConditions().add(condition);
        status.setCurrentReplicas(3);
        status.setDesiredReplicas(5);

        assertThat(status.getConditions()).hasSize(1);
        assertThat(status.getConditions().get(0).getType()).isEqualTo("Ready");
        assertThat(status.getConditions().get(0).getStatus()).isEqualTo("True");
        assertThat(status.getCurrentReplicas()).isEqualTo(3);
        assertThat(status.getDesiredReplicas()).isEqualTo(5);
    }

    @Test
    void testAdvancedConfigWithStabilizationWindow() {
        AdvancedConfig advanced = new AdvancedConfig();
        AdvancedConfig.HorizontalPodAutoscalerConfig hpaConfig = new AdvancedConfig.HorizontalPodAutoscalerConfig();
        AdvancedConfig.Behavior behavior = new AdvancedConfig.Behavior();
        
        AdvancedConfig.ScalingBehavior scaleDown = new AdvancedConfig.ScalingBehavior();
        scaleDown.setStabilizationWindowSeconds(30);
        behavior.setScaleDown(scaleDown);
        
        hpaConfig.setBehavior(behavior);
        advanced.setHorizontalPodAutoscalerConfig(hpaConfig);

        assertThat(advanced.getHorizontalPodAutoscalerConfig()).isNotNull();
        assertThat(advanced.getHorizontalPodAutoscalerConfig().getBehavior()).isNotNull();
        assertThat(advanced.getHorizontalPodAutoscalerConfig().getBehavior().getScaleDown()).isNotNull();
        assertThat(advanced.getHorizontalPodAutoscalerConfig().getBehavior()
            .getScaleDown().getStabilizationWindowSeconds()).isEqualTo(30);
    }
}
