package com.asm.assistant.ingestion.model;

/**
 * One discovered source on disk, already classified by {@code SourcePolicy}. {@code externalId} is
 * the stable identity across re-ingestions (the corpus-relative path); {@code type} selects the
 * parser; {@code authority}/{@code tenantId} become chunk metadata.
 */
public record SourceFile(
        String externalId,
        SourceType type,
        String path,
        String authority,
        String content
) {
    public enum SourceType { MARKDOWN, OPENAPI }
}
