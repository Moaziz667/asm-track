package com.asm.assistant.ingestion.model;

/** A retrievable unit produced by a parser: a heading section, or one OpenAPI operation. */
public record ParsedChunk(String section, int ordinal, String content) {}
