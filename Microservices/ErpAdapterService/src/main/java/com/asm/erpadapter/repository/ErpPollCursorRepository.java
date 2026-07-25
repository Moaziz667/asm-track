package com.asm.erpadapter.repository;

import com.asm.erpadapter.entity.ErpPollCursor;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface ErpPollCursorRepository extends JpaRepository<ErpPollCursor, UUID> {
}
