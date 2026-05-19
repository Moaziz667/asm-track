package com.asm.delivery.service;

import com.asm.delivery.config.TenantContext;
import com.asm.delivery.entity.SystemSetting;
import com.asm.delivery.repository.SystemSettingRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Service
@RequiredArgsConstructor
public class SystemSettingsService {

    private final SystemSettingRepository repository;
    private final Map<String, String> cache = new ConcurrentHashMap<>();

    @Transactional
    public void upsert(String key, String value) {
        String companyId = TenantContext.get();
        String storedKey = companyId != null ? companyId + ":" + key : key;
        SystemSetting setting = repository.findById(storedKey)
                .orElse(new SystemSetting(storedKey, value, null));
        setting.setSettingValue(value);
        repository.save(setting);
        cache.put(storedKey, value);
    }

    public int getInt(String key, int defaultValue) {
        String companyId = TenantContext.get();
        if (companyId != null) {
            String companyKey = companyId + ":" + key;
            if (cache.containsKey(companyKey)) return parseIntSafe(cache.get(companyKey), defaultValue);
            String dbVal = repository.findById(companyKey).map(SystemSetting::getSettingValue).orElse(null);
            if (dbVal != null) {
                cache.put(companyKey, dbVal);
                return parseIntSafe(dbVal, defaultValue);
            }
        }
        String value = cache.computeIfAbsent(key, k ->
                repository.findById(k).map(SystemSetting::getSettingValue).orElse(String.valueOf(defaultValue)));
        return parseIntSafe(value, defaultValue);
    }

    public Map<String, String> getAll() {
        String companyId = TenantContext.get();
        Map<String, String> result = new LinkedHashMap<>();
        // Load global defaults (keys without ":" prefix)
        repository.findAll().stream()
                .filter(s -> !s.getSettingKey().contains(":"))
                .forEach(s -> result.put(s.getSettingKey(), s.getSettingValue()));
        // Overlay with company-specific values if in a company context
        if (companyId != null) {
            String prefix = companyId + ":";
            repository.findAll().stream()
                    .filter(s -> s.getSettingKey().startsWith(prefix))
                    .forEach(s -> result.put(s.getSettingKey().substring(prefix.length()), s.getSettingValue()));
        }
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
