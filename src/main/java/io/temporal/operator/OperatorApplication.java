package io.temporal.operator;

import io.quarkus.runtime.Quarkus;
import io.quarkus.runtime.QuarkusApplication;
import io.quarkus.runtime.annotations.QuarkusMain;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Main application class for the Temporal Autoscaler Operator.
 * 
 * This Quarkus application uses Java Operator SDK to manage TemporalScaler
 * custom resources. The operator framework automatically registers controllers
 * and manages the watch/reconcile loop.
 */
@QuarkusMain
public class OperatorApplication implements QuarkusApplication {

    private static final Logger log = LoggerFactory.getLogger(OperatorApplication.class);

    public static void main(String... args) {
        log.info("Starting Temporal Autoscaler Operator...");
        Quarkus.run(OperatorApplication.class, args);
    }

    @Override
    public int run(String... args) {
        log.info("Temporal Autoscaler Operator started successfully");
        log.info("Watching for TemporalScaler resources...");
        
        Quarkus.waitForExit();
        return 0;
    }
}
