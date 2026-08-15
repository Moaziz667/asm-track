package com.asm.assistant.retrieval;

import com.asm.assistant.AbstractPostgresIT;
import com.asm.assistant.persistence.RagDocument;
import com.asm.tenant.TenantContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The hard gate: Tenant A must never retrieve Tenant B's knowledge.
 *
 * <p>Both tenants' chunks are given the <b>identical</b> embedding and the identical search term, so
 * dense and lexical search would each rank them equally — the ONLY thing that can keep B out of A's
 * results is the {@code tenant_id} filter in the retrieval SQL. GLOBAL platform knowledge stays
 * visible to everyone. If this test ever passes B's chunk to A, tenant isolation is broken.
 */
class TenantIsolationIT extends AbstractPostgresIT {

    @Autowired JdbcTemplate jdbc;
    @Autowired HybridRetriever retriever;

    static final UUID TENANT_A = UUID.fromString("aaaaaaaa-0000-0000-0000-000000000001");
    static final UUID TENANT_B = UUID.fromString("bbbbbbbb-0000-0000-0000-000000000002");

    @BeforeEach
    void seed() {
        jdbc.update("DELETE FROM rag_chunk");
        jdbc.update("DELETE FROM rag_document");
        seedDoc("secret-a", TENANT_A, "Tarif secret du tenant A: code SECRET-A.");
        seedDoc("secret-b", TENANT_B, "Tarif secret du tenant B: code SECRET-B.");
        seedDoc("global-doc", RagDocument.GLOBAL_TENANT, "Règle SLA partagée: le secret est documenté ici.");
    }

    @AfterEach
    void clear() {
        TenantContext.clear();
    }

    @Test
    void tenantA_cannotRetrieveTenantB_knowledge() {
        TenantContext.set(TENANT_A);
        List<RetrievedChunk> results = retriever.retrieve("secret");

        assertThat(results).isNotEmpty();
        assertThat(results).allSatisfy(c ->
                assertThat(c.externalId()).isNotEqualTo("secret-b"));
        assertThat(results).anySatisfy(c ->
                assertThat(c.externalId()).isEqualTo("secret-a"));
        // Shared platform knowledge remains visible to the tenant.
        assertThat(results).anySatisfy(c ->
                assertThat(c.externalId()).isEqualTo("global-doc"));
    }

    @Test
    void tenantB_seesOwnAndGlobal_notTenantA() {
        TenantContext.set(TENANT_B);
        List<RetrievedChunk> results = retriever.retrieve("secret");

        assertThat(results).isNotEmpty();
        assertThat(results).allSatisfy(c ->
                assertThat(c.externalId()).isNotEqualTo("secret-a"));
        assertThat(results).anySatisfy(c ->
                assertThat(c.externalId()).isEqualTo("secret-b"));
    }

    private void seedDoc(String externalId, UUID tenantId, String content) {
        UUID docId = UUID.randomUUID();
        jdbc.update("INSERT INTO rag_document " +
                "(id, external_id, source_type, path, title, authority, status, tenant_id, version, checksum) " +
                "VALUES (?,?, 'markdown', ?, ?, 'SECONDARY', 'current', ?, 'v1', ?)",
                docId, externalId, externalId + ".md", externalId, tenantId, externalId);
        jdbc.update("INSERT INTO rag_chunk " +
                "(id, document_id, tenant_id, authority, section, ordinal, content, embedding, content_tsv, embedding_model_version) " +
                "VALUES (?,?,?, 'SECONDARY', ?, 0, ?, ?::vector, to_tsvector('french', ?), 'stub:test')",
                UUID.randomUUID(), docId, tenantId, externalId, content,
                VectorSearch.toVectorLiteral(fixedVector()), content);
    }
}
