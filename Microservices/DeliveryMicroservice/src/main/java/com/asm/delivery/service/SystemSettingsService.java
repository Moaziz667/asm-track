package com.asm.delivery.service;

import com.asm.delivery.entity.SystemSetting;
import com.asm.delivery.repository.SystemSettingRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Service
@RequiredArgsConstructor
public class SystemSettingsService {

    private final SystemSettingRepository repository;
    private final Map<String, String> cache = new ConcurrentHashMap<>();

    @Transactional
    public void upsert(String key, String value) {
        SystemSetting setting = repository.findById(key)
                .orElse(new SystemSetting(key, value, null));
        setting.setSettingValue(value);
        repository.save(setting);
        cache.put(key, value);
    }

    public int getInt(String key, int defaultValue) {
        String value = cache.computeIfAbsent(key, k -> 
            repository.findById(k).map(SystemSetting::getSettingValue).orElse(String.valueOf(defaultValue))
        );
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }

    public Map<String, String> getAll() {
        return repository.findAll().stream()
                .collect(java.util.stream.Collectors.toMap(SystemSetting::getSettingKey, SystemSetting::getSettingValue));
    }
}
