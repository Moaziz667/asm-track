package com.asm.driver.config;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

@Slf4j
@Component
public class LoggingInterceptor implements HandlerInterceptor {

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        request.setAttribute("startTime", System.currentTimeMillis());
        return true;
    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response, Object handler, Exception ex) {
        if (request.getRequestURI().startsWith("/internal/")) {
            long duration = System.currentTimeMillis() - (long) request.getAttribute("startTime");
            log.info("INTERNAL CALL: {} {} - Status: {} - Duration: {}ms",
                    request.getMethod(), request.getRequestURI(), response.getStatus(), duration);
        }
    }
}
