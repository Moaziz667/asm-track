package com.asm.delivery.entity;

import com.fasterxml.jackson.databind.JsonNode;
import io.hypersistence.utils.hibernate.type.json.JsonType;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.Type;

import java.time.LocalDateTime;
import java.util.UUID;

@Entity
@Table(name = "route_report")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class RouteReport {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "route_id", nullable = false, unique = true)
    private UUID routeId;

    @Type(JsonType.class)
    @Column(name = "payload", columnDefinition = "jsonb", nullable = false)
    private JsonNode payload;

    @Column(name = "generated_at", nullable = false)
    private LocalDateTime generatedAt;

    @PrePersist
    void prePersist() {
        if (generatedAt == null) generatedAt = LocalDateTime.now();
    }
}
