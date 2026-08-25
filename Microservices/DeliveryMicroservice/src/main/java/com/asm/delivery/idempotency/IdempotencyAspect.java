package com.asm.delivery.idempotency;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.reflect.MethodSignature;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.lang.reflect.Type;

/**
 * Replays the stored answer when a write arrives a second time under the same
 * {@code X-Idempotency-Key} — a double tap, a client retry after a slow response, or the driver
 * app draining its offline queue.
 *
 * <p>The key alone decides. The caller owns it and keeps it stable per logical operation
 * ({@code pod-<deliveryId>}, {@code fail-<deliveryId>}), so a driver who changes a failure reason
 * before reconnecting replaces the queued write instead of sending a second, contradictory one.
 * The disagreement is settled on the device, where he can still see it, rather than on the server,
 * where it could only be refused.
 *
 * <p>{@code X-User-Id} enters the scope; the gateway strips whatever the client sent and injects
 * the identity carried by the token, so the scope cannot be forged.
 */
@Aspect
@Component
@RequiredArgsConstructor
@Slf4j
public class IdempotencyAspect {

    private final IdempotencyService idempotencyService;
    private final ObjectMapper objectMapper;

    @Around("@annotation(idempotentOperation)")
    public Object guard(ProceedingJoinPoint pjp, IdempotentOperation idempotentOperation) throws Throwable {
        RequestAttributes attrs = RequestContextHolder.getRequestAttributes();
        if (!(attrs instanceof ServletRequestAttributes servletAttrs)) {
            return pjp.proceed();
        }

        HttpServletRequest request = servletAttrs.getRequest();
        String idempotencyKey = request.getHeader("X-Idempotency-Key");
        if (!StringUtils.hasText(idempotencyKey)) {
            return pjp.proceed();
        }

        String userId = request.getHeader("X-User-Id");
        String scope = request.getMethod() + ":" + request.getRequestURI() + ":"
                + (StringUtils.hasText(userId) ? userId : "ANON");

        String cached = idempotencyService.get(scope, idempotencyKey);
        if (cached != null) {
            MethodSignature signature = (MethodSignature) pjp.getSignature();
            if (ResponseEntity.class.isAssignableFrom(signature.getReturnType())) {
                try {
                    Type genericReturnType = signature.getMethod().getGenericReturnType();
                    if (genericReturnType instanceof java.lang.reflect.ParameterizedType pt) {
                        Type targetType = pt.getActualTypeArguments()[0];
                        return ResponseEntity.ok(
                                objectMapper.readValue(cached, objectMapper.constructType(targetType)));
                    }
                    return ResponseEntity.ok(objectMapper.readTree(cached));
                } catch (Exception e) {
                    log.warn("Failed to reconstruct ResponseEntity for idempotency", e);
                }
            }
            return cached;
        }

        Object response = pjp.proceed();

        // Store the body only: a ResponseEntity also carries headers and a status, replayed out of
        // context on the second call.
        Object responseToStore = (response instanceof ResponseEntity<?> re) ? re.getBody() : response;
        idempotencyService.put(scope, idempotencyKey, responseToStore);
        return response;
    }
}
