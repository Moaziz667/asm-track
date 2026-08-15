package com.asm.assistant.retrieval;

import org.springframework.jdbc.core.RowMapper;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.UUID;

/** Shared mapping for both search strategies; {@code score} column name differs, so it's passed in. */
class ChunkRowMapper implements RowMapper<RetrievedChunk> {

    @Override
    public RetrievedChunk mapRow(ResultSet rs, int rowNum) throws SQLException {
        return new RetrievedChunk(
                (UUID) rs.getObject("id"),
                (UUID) rs.getObject("document_id"),
                rs.getString("external_id"),
                rs.getString("path"),
                rs.getString("section"),
                rs.getString("authority"),
                rs.getString("content"),
                rs.getDouble("score"),
                0.0
        );
    }
}
