package com.asm.assistant.persistence;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface RagDocumentVersionRepository extends JpaRepository<RagDocumentVersion, UUID> {

    List<RagDocumentVersion> findByDocumentId(UUID documentId);
}
