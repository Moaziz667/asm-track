package com.asm.delivery.service;

import com.asm.delivery.entity.Company;
import com.asm.delivery.repository.CompanyRepository;
import com.asm.delivery.storage.MinioStorageService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.awt.Color;

/**
 * Resolves a company-branded PDF page event (header + footer) at report generation time.
 * Loads the active company record, downloads the logo from MinIO if present, and wires
 * the real name / color / logo into BasePdfService.ReportPageEvent.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class CompanyBrandingResolver {

    private final CompanyRepository    companyRepository;
    private final MinioStorageService  minioStorageService;

    public BasePdfService.ReportPageEvent resolve(String docType, String subtitle) {
        Company company = companyRepository.findAllByActiveTrue()
                .stream().findFirst().orElse(null);

        if (company == null) {
            log.warn("No active company found — PDF will use fallback branding");
            return new BasePdfService.ReportPageEvent(docType, subtitle,
                    "ASM Track", null, BasePdfService.FALLBACK_BRAND);
        }

        byte[] logoBytes = null;
        if (company.getLogoUrl() != null && !company.getLogoUrl().isBlank()) {
            try {
                logoBytes = minioStorageService.getBytes(company.getLogoUrl());
            } catch (Exception e) {
                log.warn("Could not load company logo for PDF: {}", e.getMessage());
            }
        }

        Color brandColor = BasePdfService.parseHex(company.getPrimaryColor());

        return new BasePdfService.ReportPageEvent(
                docType, subtitle,
                company.getName(), logoBytes, brandColor);
    }
}
