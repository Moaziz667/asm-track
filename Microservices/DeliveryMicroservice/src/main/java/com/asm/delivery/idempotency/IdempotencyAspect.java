package com.asm.delivery.idempotency;
 
import com.asm.delivery.exception.AppException;
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
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.HexFormat;
 
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
        String scope = request.getMethod() + ":" + request.getRequestURI() + ":" + (StringUtils.hasText(userId) ? userId : "ANON");
        String fingerprint = hash(scope + "::" + Arrays.deepToString(pjp.getArgs()));
 
        IdempotencyService.CacheEntry existing = idempotencyService.get(scope, idempotencyKey);
        if (existing != null) {
            // Reconstruct ResponseEntity if needed
            MethodSignature signature = (MethodSignature) pjp.getSignature();
            Class<?> returnType = signature.getReturnType();
            
            if (ResponseEntity.class.isAssignableFrom(returnType)) {
                try {
                    Type genericReturnType = signature.getMethod().getGenericReturnType();
                    Object cachedResponse = existing.response();
                    if (cachedResponse == null) {
                        return ResponseEntity.ok().build();
                    }
                    String responseBody = cachedResponse.toString();
                    if (genericReturnType instanceof java.lang.reflect.ParameterizedType pt) {
                        Type targetType = pt.getActualTypeArguments()[0];
                        Object body = objectMapper.readValue(responseBody, objectMapper.constructType(targetType));
                        return ResponseEntity.ok(body);
                    } else {
                        Object body = objectMapper.readTree(responseBody);
                        return ResponseEntity.ok(body);
                    }
                } catch (Exception e) {
                    log.warn("Failed to reconstruct ResponseEntity for idempotency", e);
                }
            }
            return existing.response();
        }
 
        Object response = pjp.proceed();
        
        // Save only the body if it's a ResponseEntity
        Object responseToStore = response;
        if (response instanceof ResponseEntity<?> re) {
            responseToStore = re.getBody();
        }
        
        idempotencyService.put(scope, idempotencyKey, fingerprint, responseToStore, idempotentOperation.ttlSeconds());
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
