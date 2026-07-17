package com.asm.delivery.service;

import com.asm.delivery.entity.Company;
import com.asm.delivery.erp.ErpCompanyDTO;
import com.asm.delivery.erp.port.ErpPort;
import com.asm.delivery.exception.AppException;
import com.asm.delivery.repository.CompanyRepository;
import com.asm.delivery.storage.MinioStorageService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * Characterization test for company sync after ErpPort.getCompany() was typed (Map → ErpCompanyDTO):
 * the ERP record's fields (incl. legal MF from `vat`) land on the Company entity.
 */
@ExtendWith(MockitoExtension.class)
class CompanyServiceSyncTest {

    @Mock CompanyRepository repo;
    @Mock MinioStorageService minioStorageService;
    @Mock ErpPort erpPort;
    @Mock AuditLogService auditLogService;

    private CompanyService service;
    private UUID companyId;

    @BeforeEach
    void setUp() {
        service = new CompanyService(repo, minioStorageService, erpPort, auditLogService);
        companyId = UUID.randomUUID();
    }

    @Test
    void syncMapsErpCompanyFieldsIncludingLegalTaxId() {
        Company existing = Company.builder().id(companyId).name("old").active(true).build();
        when(repo.findById(companyId)).thenReturn(Optional.of(existing));
        when(repo.save(any(Company.class))).thenAnswer(inv -> inv.getArgument(0));
        when(erpPort.getCompany()).thenReturn(ErpCompanyDTO.builder()
                .name("ASM SARL").address("Zone Industrielle").city("Tunis")
                .email("contact@asm.tn").vat("1234567A/M/000").phone("+216 71 000 000").build());

        Company saved = service.syncFromErp(companyId);

        assertThat(saved.getName()).isEqualTo("ASM SARL");
        assertThat(saved.getCity()).isEqualTo("Tunis");
        assertThat(saved.getTaxId()).isEqualTo("1234567A/M/000");
        assertThat(saved.getPhone()).isEqualTo("+216 71 000 000");
        assertThat(saved.getSupportEmail()).isEqualTo("contact@asm.tn");
        assertThat(saved.getAddress()).contains("Zone Industrielle");
    }

    @Test
    void throwsWhenErpReturnsNoCompany() {
        Company existing = Company.builder().id(companyId).name("old").build();
        when(repo.findById(companyId)).thenReturn(Optional.of(existing));
        when(erpPort.getCompany()).thenReturn(null);

        assertThatThrownBy(() -> service.syncFromErp(companyId)).isInstanceOf(AppException.class);
    }
}
