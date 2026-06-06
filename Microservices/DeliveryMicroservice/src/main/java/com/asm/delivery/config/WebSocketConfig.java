package com.asm.delivery.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.messaging.simp.config.MessageBrokerRegistry;
import org.springframework.web.socket.config.annotation.EnableWebSocketMessageBroker;
import org.springframework.web.socket.config.annotation.StompEndpointRegistry;
import org.springframework.web.socket.config.annotation.WebSocketMessageBrokerConfigurer;

@Configuration
@EnableWebSocketMessageBroker
public class WebSocketConfig implements WebSocketMessageBrokerConfigurer {

    @Value("${websocket.broker.relay.enabled:false}")
    private boolean relayEnabled;

    @Value("${spring.rabbitmq.host:rabbitmq}")
    private String rabbitHost;

    @Value("${spring.rabbitmq.stomp.port:61613}")
    private int rabbitStompPort;

    @Value("${spring.rabbitmq.username:guest}")
    private String rabbitUser;

    @Value("${spring.rabbitmq.password:guest}")
    private String rabbitPass;

    @Override
    public void configureMessageBroker(MessageBrokerRegistry registry) {
        if (relayEnabled) {
            registry.enableStompBrokerRelay("/topic")
                    .setRelayHost(rabbitHost)
                    .setRelayPort(rabbitStompPort)
                    .setClientLogin(rabbitUser)
                    .setClientPasscode(rabbitPass)
                    .setSystemLogin(rabbitUser)
                    .setSystemPasscode(rabbitPass);
        } else {
            registry.enableSimpleBroker("/topic");
        }
        registry.setApplicationDestinationPrefixes("/app");
    }

    @org.springframework.beans.factory.annotation.Autowired
    private org.springframework.security.oauth2.jwt.JwtDecoder jwtDecoder;

    @Override
    public void configureClientInboundChannel(org.springframework.messaging.simp.config.ChannelRegistration registration) {
        registration.interceptors(new com.asm.delivery.security.WebSocketSecurityInterceptor(
                jwtDecoder, new com.asm.delivery.security.JwtAuthConverter()));
    }

    @Override
    public void registerStompEndpoints(StompEndpointRegistry registry) {
        registry.addEndpoint("/ws")
                .setAllowedOriginPatterns("*")
                .withSockJS();
    }
}
