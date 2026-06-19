package com.asm.delivery.entity;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

import java.util.Arrays;
import java.util.EnumSet;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Persists a {@code Set<FailureContext>} as a comma-separated string in a single column
 * (e.g. {@code "FAILURE,ITEM_REFUSED"}). Keeps the tiny failure-reason referential normalized in Java
 * without a join table; unknown tokens are ignored defensively.
 */
@Converter
public class FailureContextSetConverter implements AttributeConverter<Set<FailureContext>, String> {

    @Override
    public String convertToDatabaseColumn(Set<FailureContext> attribute) {
        if (attribute == null || attribute.isEmpty()) return FailureContext.FAILURE.name();
        return attribute.stream().map(Enum::name).collect(Collectors.joining(","));
    }

    @Override
    public Set<FailureContext> convertToEntityAttribute(String dbData) {
        if (dbData == null || dbData.isBlank()) return EnumSet.of(FailureContext.FAILURE);
        Set<FailureContext> result = EnumSet.noneOf(FailureContext.class);
        for (String token : Arrays.asList(dbData.split(","))) {
            String t = token.trim();
            if (t.isEmpty()) continue;
            try {
                result.add(FailureContext.valueOf(t));
            } catch (IllegalArgumentException ignored) {
                // tolerate stale/unknown tokens
            }
        }
        if (result.isEmpty()) result.add(FailureContext.FAILURE);
        return result;
    }
}
