package com.asm.erpadapter.integration;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.condition.EnabledIf;
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
