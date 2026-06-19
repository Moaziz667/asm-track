package com.asm.delivery.entity;

/**
 * Stable analytics category for a delivery failure. The {@link #getLabel() label} is the canonical
 * French display name used by all server-rendered output (PDF reports, route reports) — a single source
 * so labels can't drift (the admin web localizes separately via its i18n copy). If multi-language
 * server output is ever needed, graduate this to a Spring MessageSource.
 */
public enum FailureCode {
    CLIENT_ABSENT("Client absent"),
    REFUSED("Refus du client"),
    WRONG_ADDRESS("Adresse incorrecte"),
    DAMAGED("Article endommagé"),
    MISSING("Manquant / Rupture de stock"),
    OTHER("Autre motif");

    private final String label;

    FailureCode(String label) {
        this.label = label;
    }

    public String getLabel() {
        return label;
    }
}
