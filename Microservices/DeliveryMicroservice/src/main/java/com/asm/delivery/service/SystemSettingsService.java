package com.asm.delivery.service;


import com.asm.delivery.entity.SystemSetting;
import com.asm.delivery.repository.SystemSettingRepository;
import com.asm.tenant.TenantContext;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Reads/writes the tenant's {@code system_settings} key-value table (e.g. {@code erp.provider}).
 *
 * <p><b>Multi-tenant:</b> the table lives in each tenant's schema (search_path), so the DB reads are
 * already isolated — but the in-memory cache is process-wide and MUST be keyed by {@code companyId}
 * too. Keying it by the bare setting key alone would let the first tenant to read a key pin its value
 * for every other tenant (a cross-tenant leak — e.g. one company's ERP provider bleeding into another's).
 */
@Service
@RequiredArgsConstructor
public class SystemSettingsService {

    private final SystemSettingRepository repository;
    // Key = "<companyId>::<settingKey>" so no two tenants ever share a cached value. See class doc.
    private final Map<String, String> cache = new ConcurrentHashMap<>();

    /** Namespace a setting key by the current tenant so the cache can't cross tenants. */
    private String cacheKey(String key) {
        UUID companyId = TenantContext.get();
        return (companyId != null ? companyId.toString() : "__no_tenant__") + "::" + key;
    }

    @Transactional
    public void upsert(String key, String value) {
        SystemSetting setting = repository.findById(key)
                .orElse(new SystemSetting(key, value, null));
        setting.setSettingValue(value);
        repository.save(setting);
        cache.put(cacheKey(key), value);
    }

    public String get(String key) {
        return cache.computeIfAbsent(cacheKey(key), ck ->
                repository.findById(key).map(SystemSetting::getSettingValue).orElse(null));
    }

    public int getInt(String key, int defaultValue) {
        String value = cache.computeIfAbsent(cacheKey(key), ck ->
                repository.findById(key).map(SystemSetting::getSettingValue).orElse(String.valueOf(defaultValue)));
        return parseIntSafe(value, defaultValue);
    }

    public Map<String, String> getAll() {
        Map<String, String> result = new LinkedHashMap<>();
        repository.findAll().forEach(s -> result.put(s.getSettingKey(), s.getSettingValue()));
        return result;
    }

    private int parseIntSafe(String value, int defaultValue) {
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }
}
