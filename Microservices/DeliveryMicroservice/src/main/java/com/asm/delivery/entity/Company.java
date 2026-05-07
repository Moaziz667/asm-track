package com.asm.delivery.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;
import java.util.UUID;

@Entity
@Table(name = "companies")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Company {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(nullable = false, length = 255)
    private String name;

    @Column(name = "logo_url", length = 512)
    private String logoUrl;

    @Column(length = 512)
    private String address;

    @Column(name = "primary_color", length = 7)
    @Builder.Default
    private String primaryColor = "#FF5722";

    @Column(name = "erp_type", length = 20)
    @Builder.Default
    private String erpType = "NONE";

    @Column(name = "erp_api_url", length = 512)
    private String erpApiUrl;

    @Column(name = "erp_api_key", length = 512)
    private String erpApiKey;

    @Column(name = "erp_db_name", length = 255)
    private String erpDbName;

    @Column(name = "erp_username", length = 255)
    private String erpUsername;

    @Column(name = "erp_uid")
    private Integer erpUid;

    @Builder.Default
    private boolean active = true;

    @Column(name = "created_at", updatable = false)
    @Builder.Default
    private LocalDateTime createdAt = LocalDateTime.now();
}
