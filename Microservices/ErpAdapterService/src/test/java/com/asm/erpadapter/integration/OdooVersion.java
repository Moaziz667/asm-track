package com.asm.erpadapter.integration;

/**
 * Supported Odoo versions for integration testing.
 *
 * <p>Each version maps to its official Docker Hub image. The {@code skip} flag
 * allows gracefully skipping a version when its image is unavailable.
 */
public enum OdooVersion {

    V16("odoo:16", false),
    V17("odoo:17", true),
    V18("odoo:18", true),
    V19("odoo:19", false);

    private final String imageName;
    private final boolean skipByDefault;

    OdooVersion(String imageName, boolean skipByDefault) {
        this.imageName = imageName;
        this.skipByDefault = skipByDefault;
    }

    public String getImageName() {
        return imageName;
    }

    public boolean isSkipByDefault() {
        return skipByDefault;
    }

    @Override
    public String toString() {
        return name() + " (" + imageName + ")";
    }
}
