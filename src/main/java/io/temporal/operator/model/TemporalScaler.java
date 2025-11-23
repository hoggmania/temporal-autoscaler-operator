package io.temporal.operator.model;

import io.fabric8.kubernetes.api.model.Namespaced;
import io.fabric8.kubernetes.client.CustomResource;
import io.fabric8.kubernetes.model.annotation.Group;
import io.fabric8.kubernetes.model.annotation.Version;

@Group("scaling.example.com")
@Version("v1alpha1")
public class TemporalScaler extends CustomResource<TemporalScalerSpec, TemporalScalerStatus> implements Namespaced {

    @Override
    protected TemporalScalerSpec initSpec() {
        return new TemporalScalerSpec();
    }

    @Override
    protected TemporalScalerStatus initStatus() {
        return new TemporalScalerStatus();
    }
}
