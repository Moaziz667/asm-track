package com.asm.assistant.persistence;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

/**
 * A single ingested source (one Markdown file, one OpenAPI operation set, one code-derived card…).
 * Its chunks are the retrievable units ({@link RagChunk}); its versions track supersession
 * ({@link RagDocumentVersion}). Authority + status + tenant are what keep an obsolete document from
 * silently overriding a current authoritative one.
 */
@Entity
@Table(name = "rag_document")
@Getter
@Setter
@NoArgsConstructor
public class RagDocument {

    @Id
    @GeneratedValue
    private UUID id;

    /** Reserved sentinel for platform-wide knowledge readable by every tenant. */
    public static final UUID GLOBAL_TENANT = new UUID(0L, 0L);

    /** Stable identity across re-ingestions (e.g. the source path). */
    @Column(name = "external_id", nullable = false)
    private String externalId;

    /** markdown | adr | openapi | code_card | config | runbook … */
    @Column(name = "source_type", nullable = false)
    private String sourceType;

    @Column(name = "path", nullable = false)
    private String path;

    @Column(name = "title")
    private String title;

    /** AUTHORITATIVE | SECONDARY | LOW | EXCLUDED */
    @Column(name = "authority", nullable = false)
    private String authority;

    /** current | deprecated | archived */
    @Column(name = "status", nullable = false)
    private String status;

    /** Company UUID, or {@link #GLOBAL_TENANT} for platform-wide knowledge. */
    @Column(name = "tenant_id", nullable = false)
    private UUID tenantId;

    /** Git SHA / doc version at ingestion time. */
    @Column(name = "version")
    private String version;

    /** Content hash — drives update detection and supersession. */
    @Column(name = "checksum", nullable = false)
    private String checksum;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;
}
