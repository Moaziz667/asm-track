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

    @Column(length = 120)
    private String city;

    @Column(name = "primary_color", length = 7)
    @Builder.Default
    private String primaryColor = "#FF5722";

    @Column(length = 40)
    private String phone;

    /** Legal tax id printed on the delivery note (Tunisia: matricule fiscal). */
    @Column(name = "tax_id", length = 50)
    private String taxId;

    /** Trade register number printed on the delivery note (registre de commerce / RCS). */
    @Column(name = "registration_number", length = 50)
    private String registrationNumber;

    @Column(name = "support_email", length = 255)
    private String supportEmail;

    @Builder.Default
    @Column(nullable = false)
    private Boolean active = true;

    @Column(name = "created_at", updatable = false)
    @Builder.Default
    private LocalDateTime createdAt = LocalDateTime.now();
}
