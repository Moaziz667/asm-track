package com.asm.erpadapter.integration;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.TestInstance;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.EnumSet;
import java.util.Set;

/**
 * Base class for Odoo integration tests. Manages container lifecycle and provides
 * shared infrastructure (OdooTestDataFactory, OdooContainer).
 *
 * <p>By default, runs against Odoo 16. Subclasses can override {@link #getTestVersions()}
 * to target different versions.
 *
 * <p>Usage:
 * <pre>
 *   class MyIT extends AbstractOdooIntegrationTest {
 *       &#64;Override
 *       protected Set&lt;OdooVersion&gt; getTestVersions() {
 *           return EnumSet.of(OdooVersion.V16, OdooVersion.V17);
 *       }
 *
 *       &#64;Test
 *       void myTest() {
 *           OdooContainer odoo = getOdoo(OdooVersion.V16);
 *           OdooTestDataFactory data = getFactory(OdooVersion.V16);
 *           // ... test logic
 *       }
 *   }
 * </pre>
 *
 * <h2>Where these run</h2>
 * They need a live Odoo, so they are not part of {@code gradle test}. The CI job
 * {@code integration-test-erp-adapter-odoo} starts one Odoo 16 and one Odoo 19 as side-cars and runs
 * the whole suite against both.
 *
 * <p>That job is <b>not</b> on every pipeline: installing {@code sale} and {@code stock} on two fresh
 * instances costs minutes, so it is bound to changes under {@code Microservices/ErpAdapterService/}
 * — it fires exactly when it can catch something. A commit that touches only the frontend will not
 * show it, and that is not a failure. To run it on demand, use <b>Run pipeline</b> from the GitLab
 * UI: the job's second rule matches {@code CI_PIPELINE_SOURCE == "web"}.
 *
 * <p>Locally, point the suite at any two running instances and run {@code gradle integrationTest}:
 * <pre>
 *   ODOO_V16_URL=http://localhost:8069/jsonrpc  ODOO_V16_DB=odoo16
 *   ODOO_V19_URL=http://localhost:8070/jsonrpc  ODOO_V19_DB=odoo19
 * </pre>
 * Use throwaway databases. The suite creates partners, products and sale orders, and validates
 * transfers — it is not something to aim at an instance whose data matters.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
public abstract class AbstractOdooIntegrationTest {

    private static final Logger log = LoggerFactory.getLogger(AbstractOdooIntegrationTest.class);

    /** Containers and factories keyed by version. */
    private final java.util.Map<OdooVersion, OdooContainer> containers = new java.util.EnumMap<>(OdooVersion.class);
    private final java.util.Map<OdooVersion, OdooTestDataFactory> factories = new java.util.EnumMap<>(OdooVersion.class);

    /**
     * Override to specify which Odoo versions to test against.
     * Default: V16 only.
     */
    protected Set<OdooVersion> getTestVersions() {
        return EnumSet.of(OdooVersion.V16);
    }

    @BeforeAll
    void startContainers() {
        for (OdooVersion version : getTestVersions()) {
            if (version.isSkipByDefault()) {
                log.info("Skipping {} — image may not exist", version);
                continue;
            }
            log.info("Starting Odoo container for {}", version);
            OdooContainer container = new OdooContainer(version);
            container.start();
            containers.put(version, container);
            factories.put(version, new OdooTestDataFactory(container));
        }
    }

    @AfterAll
    void stopContainers() {
        for (OdooContainer container : containers.values()) {
            try {
                container.close();
            } catch (Exception e) {
                log.warn("Error stopping container: {}", e.getMessage());
            }
        }
        containers.clear();
        factories.clear();
    }

    /**
     * Get the Odoo container for a given version.
     *
     * @throws IllegalStateException if the container for that version wasn't started
     */
    protected OdooContainer getOdoo(OdooVersion version) {
        OdooContainer c = containers.get(version);
        if (c == null) throw new IllegalStateException(
                "No container for " + version + " — add it to getTestVersions()");
        return c;
    }

    /**
     * Get the test data factory for a given version.
     */
    protected OdooTestDataFactory getFactory(OdooVersion version) {
        OdooTestDataFactory f = factories.get(version);
        if (f == null) throw new IllegalStateException(
                "No factory for " + version + " — add it to getTestVersions()");
        return f;
    }

    /**
     * Check if a version's container is available (for conditional tests).
     */
    protected boolean hasOdoo(OdooVersion version) {
        return containers.containsKey(version);
    }
}
