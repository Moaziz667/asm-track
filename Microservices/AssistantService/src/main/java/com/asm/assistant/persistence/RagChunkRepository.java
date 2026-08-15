package com.asm.assistant.persistence;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface RagChunkRepository extends JpaRepository<RagChunk, UUID> {

    List<RagChunk> findByDocumentId(UUID documentId);

    void deleteByDocumentId(UUID documentId);
}
