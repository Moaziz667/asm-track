package com.asm.delivery.service;

import com.asm.delivery.entity.Company;
import com.asm.delivery.exception.AppException;
import com.asm.delivery.repository.CompanyRepository;
import com.asm.delivery.storage.MinioStorageService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class CompanyService {

    private final CompanyRepository repo;
    private final MinioStorageService minioStorageService;
    private final com.asm.delivery.erp.port.ErpPort erpPort;

    public Optional<Company> findById(UUID id) {
        return repo.findById(id);
    }

    public List<Company> findAll() {
        return repo.findAll();
    }

    @Transactional
    public Company create(Company company) {
        return repo.save(company);
    }

    @Transactional
    public Company update(UUID id, Company patch) {
        Company existing = repo.findById(id)
                .orElseThrow(() -> AppException.notFound("Company not found: " + id));
        if (patch.getName()         != null) existing.setName(patch.getName());
        if (patch.getLogoUrl()      != null) existing.setLogoUrl(patch.getLogoUrl());
        if (patch.getAddress()      != null) existing.setAddress(patch.getAddress());
        if (patch.getPrimaryColor() != null) existing.setPrimaryColor(patch.getPrimaryColor());
        if (patch.getSupportEmail() != null) existing.setSupportEmail(patch.getSupportEmail());
        if (patch.getActive() != null) existing.setActive(patch.getActive());
        return repo.save(existing);
    }

    /**
     * Pull the tenant's company info from the connected ERP (Odoo res.company) and overwrite
     * name, address, support email, and logo. Primary color is kept as-is (not in ERP).
     * Throws if the ERP returns nothing.
     */
    @Transactional
    public Company syncFromErp(UUID id) {
        Company existing = repo.findById(id)
                .orElseThrow(() -> AppException.notFound("Company not found: " + id));

        java.util.Map<String, Object> erp = erpPort.getCompany();
        if (erp == null || erp.isEmpty()) {
            throw AppException.badRequest("L'ERP n'a retourné aucune information d'entreprise. Vérifiez la configuration ERP.");
        }

        String name    = str(erp.get("name"));
        String address = composeAddress(str(erp.get("address")), str(erp.get("city")));
        String email   = str(erp.get("email"));

        if (name    != null) existing.setName(name);
        if (address != null) existing.setAddress(address);
        if (email   != null) existing.setSupportEmail(email);

        String logoBase64 = str(erp.get("logo"));
        if (logoBase64 != null) {
            try {
                byte[] imageBytes = java.util.Base64.getDecoder().decode(logoBase64);
                if (imageBytes.length > 0 && imageBytes.length <= 2 * 1024 * 1024) {
                    String logoUrl = minioStorageService.uploadCompanyLogo(existing.getId(), imageBytes);
                    existing.setLogoUrl(logoUrl);
                } else {
                    log.warn("ERP logo skipped: size {} bytes exceeds 2 MB limit", imageBytes.length);
                }
            } catch (Exception e) {
                log.warn("Could not sync company logo from ERP: {}", e.getMessage());
            }
        }

        return repo.save(existing);
    }

    private static String str(Object v) {
        if (v == null) return null;
        String s = String.valueOf(v).trim();
        return s.isEmpty() || "false".equalsIgnoreCase(s) ? null : s;
    }

    private static String composeAddress(String address, String city) {
        if (address == null && city == null) return null;
        if (address == null) return city;
        if (city == null || address.toLowerCase().contains(city.toLowerCase())) return address;
        return address + ", " + city;
    }

    @Transactional
    public Company uploadLogo(UUID id, MultipartFile file) {
        Company company = repo.findById(id)
                .orElseThrow(() -> AppException.notFound("Company not found: " + id));
        try {
            String path = "company-logos/" + id + "/" + System.currentTimeMillis() + "-logo.png";
            String publicUrl = minioStorageService.getPublicUrl(path);
            company.setLogoUrl(publicUrl);
            Company saved = repo.save(company);
            
            byte[] fileBytes = file.getBytes();
            String contentType = file.getContentType();
            runAfterCommit(() -> minioStorageService.uploadFile(fileBytes, contentType, path));
            
            return saved;
        } catch (IOException e) {
            throw AppException.badRequest("Failed to upload logo: " + e.getMessage());
        }
    }

    private void runAfterCommit(Runnable action) {
        if (org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive()) {
            org.springframework.transaction.support.TransactionSynchronizationManager.registerSynchronization(
                new org.springframework.transaction.support.TransactionSynchronization() {
                    @Override
                    public void afterCommit() {
                        action.run();
                    }
                }
            );
        } else {
            action.run();
        }
    }
}
