package com.asm.delivery.service;

import com.asm.delivery.entity.Company;
import com.asm.delivery.exception.AppException;
import com.asm.delivery.repository.CompanyRepository;
import com.asm.delivery.storage.MinioStorageService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class CompanyService {

    private final CompanyRepository repo;
    private final MinioStorageService minioStorageService;

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
        if (patch.getErpType()      != null) existing.setErpType(patch.getErpType());
        if (patch.getErpApiUrl()    != null) existing.setErpApiUrl(patch.getErpApiUrl());
        if (patch.getErpApiKey()    != null) existing.setErpApiKey(patch.getErpApiKey());
        if (patch.getErpDbName()    != null) existing.setErpDbName(patch.getErpDbName());
        if (patch.getErpUsername()  != null) existing.setErpUsername(patch.getErpUsername());
        if (patch.getErpUid()       != null) existing.setErpUid(patch.getErpUid());
        existing.setActive(patch.isActive());
        return repo.save(existing);
    }

    @Transactional
    public void deactivate(UUID id) {
        Company company = repo.findById(id)
                .orElseThrow(() -> AppException.notFound("Company not found: " + id));
        company.setActive(false);
        repo.save(company);
    }

    @Transactional
    public Company uploadLogo(UUID id, MultipartFile file) {
        Company company = repo.findById(id)
                .orElseThrow(() -> AppException.notFound("Company not found: " + id));
        try {
            String path = "company-logos/" + id + "/" + System.currentTimeMillis() + "-logo.png";
            String url = minioStorageService.uploadFile(file.getBytes(), file.getContentType(), path);
            company.setLogoUrl(url);
            return repo.save(company);
        } catch (IOException e) {
            throw AppException.badRequest("Failed to upload logo: " + e.getMessage());
        }
    }
}
