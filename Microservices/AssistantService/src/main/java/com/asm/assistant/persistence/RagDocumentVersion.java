package com.asm.assistant.persistence;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

/**
 * History of a {@link RagDocument}: each ingestion that changed the checksum records a row here, so a
 * superseded version can be retired without losing the audit trail of what the corpus once said.
 */
@Entity
@Table(name = "rag_document_version")
@Getter
@Setter
@NoArgsConstructor
public class RagDocumentVersion {

    @Id
    @GeneratedValue
    private UUID id;

    @Column(name = "document_id", nullable = false)
    private UUID documentId;

    @Column(name = "version", nullable = false)
    private String version;

    @Column(name = "checksum", nullable = false)
    private String checksum;

    /** current | superseded */
    @Column(name = "status", nullable = false)
    private String status;

    @Column(name = "ingested_at", nullable = false)
    private Instant ingestedAt;
}
