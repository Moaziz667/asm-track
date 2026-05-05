package com.asm.appbackend.config;

import com.asm.appbackend.entity.AdminUser;
import com.asm.appbackend.repository.AdminUserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
@Slf4j
public class AdminSeeder implements ApplicationRunner {

    private final AdminUserRepository adminUserRepo;
    private final PasswordEncoder passwordEncoder;

    @Override
    public void run(ApplicationArguments args) {
        if (!adminUserRepo.existsByRole("ADMIN")) {
            AdminUser admin = AdminUser.builder()
                    .name("Admin")
                    .email("admin@asm-delivery.com")
                    .passwordHash(passwordEncoder.encode("Admin@2026"))
                    .role("ADMIN")
                    .active(true)
                    .build();
            adminUserRepo.save(admin);
            log.info("Default admin seeded: email=admin@asm-delivery.com");
        }
    }
}
