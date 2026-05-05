package com.asm.delivery.idempotency;

import com.asm.delivery.exception.AppException;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.HexFormat;

@Aspect
@Component
@RequiredArgsConstructor
public class IdempotencyAspect {

    private final IdempotencyService idempotencyService;

    @Around("@annotation(idempotentOperation)")
    public Object guard(ProceedingJoinPoint pjp, IdempotentOperation idempotentOperation) throws Throwable {
        RequestAttributes attrs = RequestContextHolder.getRequestAttributes();
        if (!(attrs instanceof ServletRequestAttributes servletAttrs)) {
            return pjp.proceed();
        }

        HttpServletRequest request = servletAttrs.getRequest();
        String idempotencyKey = request.getHeader("Idempotency-Key");
        if (!StringUtils.hasText(idempotencyKey)) {
            return pjp.proceed();
        }

        String userId = request.getHeader("X-User-Id");
        String scope = request.getMethod() + ":" + request.getRequestURI() + ":" + (StringUtils.hasText(userId) ? userId : "ANON");
        String fingerprint = hash(scope + "::" + Arrays.deepToString(pjp.getArgs()));

        IdempotencyService.CacheEntry existing = idempotencyService.get(scope, idempotencyKey);
        if (existing != null) {
            if (!existing.fingerprint().equals(fingerprint)) {
                throw AppException.conflict("Idempotency key reused with a different request payload");
            }
            return existing.response();
        }

        Object response = pjp.proceed();
        idempotencyService.put(scope, idempotencyKey, fingerprint, response, idempotentOperation.ttlSeconds());
        return response;
    }

    private static String hash(String value) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(md.digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception ex) {
            return Integer.toHexString(value.hashCode());
        }
    }
}
