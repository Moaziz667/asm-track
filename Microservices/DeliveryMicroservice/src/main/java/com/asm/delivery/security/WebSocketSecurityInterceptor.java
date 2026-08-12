package com.asm.delivery.security;

import com.asm.tenant.TenantContext;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.MessageDeliveryException;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.ChannelInterceptor;
import org.springframework.messaging.support.MessageHeaderAccessor;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;

import java.security.Principal;
import java.util.List;

@RequiredArgsConstructor
@Slf4j
public class WebSocketSecurityInterceptor implements ChannelInterceptor {

    private final JwtDecoder jwtDecoder;
    private final JwtAuthConverter jwtAuthConverter;

    @Override
    public Message<?> preSend(Message<?> message, MessageChannel channel) {
        StompHeaderAccessor accessor = MessageHeaderAccessor.getAccessor(message, StompHeaderAccessor.class);
        if (accessor == null) {
            return message;
        }

        if (StompCommand.CONNECT.equals(accessor.getCommand())) {
            String authHeader = accessor.getFirstNativeHeader("Authorization");

            // An anonymous CONNECT is allowed, and carries no principal.
            //
            // The public tracking page holds no token — that is the whole point of a shareable link —
            // so rejecting it here meant it never reached SUBSCRIBE, where "/topic/public." is
            // explicitly whitelisted a few lines below. That whitelist was unreachable code: the
            // customer's map only ever moved on the 60-second poll, while the socket retried every
            // five seconds and filled the log with rejections.
            //
            // Nothing is loosened by letting the frame through. Authorisation is decided at
            // SUBSCRIBE, where a session without a principal can reach "/topic/public.{deliveryId}"
            // and nothing else — and that destination carries only a pair of coordinates for a
            // delivery whose unguessable id the subscriber already holds. There are no
            // @MessageMapping handlers in this service, so an anonymous session has nothing to SEND
            // to either.
            if (authHeader == null) {
                log.debug("WS Connection accepted anonymously (public tracking)");
                return message;
            }
            if (!authHeader.startsWith("Bearer ")) {
                log.warn("WS Connection rejected: malformed Authorization header");
                throw new MessageDeliveryException("Missing or invalid Authorization header");
            }

            try {
                String token = authHeader.substring(7);
                Jwt jwt = jwtDecoder.decode(token);
                var auth = jwtAuthConverter.convert(jwt);
                accessor.setUser(auth);

                // NOTE: deliberately NO TenantContext.set() here. STOMP frames are processed on shared
                // clientInboundChannel pool threads, so a per-CONNECT ThreadLocal write would leak one
                // session's tenant onto a pooled thread serving other sessions (and the matching clear
                // on DISCONNECT would run on a different thread anyway). The per-session tenant lives
                // on the authenticated principal (UserPrincipal.companyId); any future message handler
                // must set/clear the TenantContext per-message from that principal, never per-connection.

                log.info("WS Connection authenticated for user: {}", auth.getName());
            } catch (Exception e) {
                log.warn("WS Connection authentication failed: {}", e.getMessage());
                throw new MessageDeliveryException("Unauthorized: " + e.getMessage());
            }
        } else if (StompCommand.SUBSCRIBE.equals(accessor.getCommand())) {
            String dest = accessor.getDestination();
            if (dest == null) {
                return message;
            }

            // Allow public tracking topics to be subscribed to without authentication
            if (dest.startsWith("/topic/public.")) {
                return message;
            }

            Principal principal = accessor.getUser();
            if (principal == null || !(principal instanceof UsernamePasswordAuthenticationToken auth)) {
                log.warn("WS Subscription rejected: Unauthenticated attempt to subscribe to {}", dest);
                throw new MessageDeliveryException("Access denied: Authentication required");
            }

            UserPrincipal user = (UserPrincipal) auth.getPrincipal();

            // Validate tenant-scoped topics: /topic/company.{companyId}.{subtopic} — DOT notation
            // (the RabbitMQ STOMP relay rejects '/' inside a routing key, so slash-form tenant
            // topics never worked with the relay enabled). The UUID contains no dots, so the first
            // '.' after the prefix separates companyId from the subtopic.
            if (dest.startsWith("/topic/company.")) {
                String remainder = dest.substring("/topic/company.".length());
                int dotIdx = remainder.indexOf('.');
                if (dotIdx < 0) {
                    log.warn("WS Subscription rejected: Invalid tenant topic format {}", dest);
                    throw new MessageDeliveryException("Access denied: Invalid topic format");
                }
                String topicCompanyId = remainder.substring(0, dotIdx);

                // Verify the companyId matches the user's org_id
                if (user.getCompanyId() == null || !user.getCompanyId().toString().equals(topicCompanyId)) {
                    log.warn("WS Subscription rejected: User {} company {} attempted subscribing to topic {}",
                            user.getUserId(), user.getCompanyId(), dest);
                    throw new MessageDeliveryException("Access denied: Topic company mismatch");
                }

                String subPath = remainder.substring(dotIdx + 1);
                if (subPath.startsWith("admin.")) {
                    if (!List.of("ADMIN", "DISPATCHER", "MANAGER").contains(user.getRole())) {
                        log.warn("WS Subscription rejected: User {} with role {} attempted subscribing to admin topic {}",
                                user.getUserId(), user.getRole(), dest);
                        throw new MessageDeliveryException("Access denied: Admin permissions required");
                    }
                } else if (subPath.startsWith("driver.")) {
                    String topicDriverId = subPath.substring("driver.".length());
                    if (!user.getUserId().equals(topicDriverId) && !List.of("ADMIN", "DISPATCHER", "MANAGER").contains(user.getRole())) {
                        log.warn("WS Subscription rejected: User {} with role {} attempted subscribing to driver topic {}",
                                user.getUserId(), user.getRole(), dest);
                        throw new MessageDeliveryException("Access denied: Subscription resource mismatch");
                    }
                }
            } else if (dest.startsWith("/topic/driver.")) {
                // Global (non-company-scoped) driver topic: SELF ONLY. The topic name carries no tenant,
                // so a role-based allowance would let an ADMIN/DISPATCHER of another tenant stream this
                // driver's assignments (client names, addresses) just by knowing the driver UUID.
                // Staff must use the tenant-validated /topic/company.{companyId}.driver.{id} form.
                String topicDriverId = dest.substring("/topic/driver.".length());
                if (!user.getUserId().equals(topicDriverId)) {
                    log.warn("WS Subscription rejected: User {} with role {} attempted subscribing to driver topic {}",
                            user.getUserId(), user.getRole(), dest);
                    throw new MessageDeliveryException("Access denied: Subscription resource mismatch");
                }
            } else {
                log.warn("WS Subscription rejected: Access to topic {} is forbidden", dest);
                throw new MessageDeliveryException("Access denied: Forbidden topic");
            }
            log.debug("WS Subscription approved: User {} subscribed to {}", user.getUserId(), dest);
        }

        return message;
    }
}
