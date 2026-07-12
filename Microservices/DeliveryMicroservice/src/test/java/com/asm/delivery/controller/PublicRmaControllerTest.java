package com.asm.delivery.controller;

import com.asm.delivery.dto.request.PublicReturnRequest;
import com.asm.delivery.dto.response.PublicReturnableItemsResponse;
import com.asm.delivery.dto.response.TrackingResponse;
import com.asm.delivery.exception.AppException;
import com.asm.delivery.service.PublicReturnRateLimiter;
import com.asm.delivery.service.PublicRmaService;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.http.HttpStatus;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class PublicRmaControllerTest {

    @Mock PublicRmaService service;
    @Mock PublicReturnRateLimiter rateLimiter;
    @Mock HttpServletRequest httpRequest;

    @InjectMocks PublicRmaController controller;

    private final UUID deliveryId = UUID.randomUUID();

    @Test
    void getReturnableItems_delegates() {
        var expected = PublicReturnableItemsResponse.builder().deliveryStatus("DELIVERED").build();
        when(service.getReturnableItems(deliveryId)).thenReturn(expected);
        assertThat(controller.getReturnableItems(deliveryId).getBody()).isSameAs(expected);
    }

    @Test
    void createReturn_allowed_delegates() {
        when(rateLimiter.tryAcquire(any(), eq(deliveryId))).thenReturn(true);
        var tracking = TrackingResponse.builder().deliveryId(deliveryId.toString()).build();
        when(service.createReturn(eq(deliveryId), any())).thenReturn(tracking);

        var body = controller.createReturn(deliveryId, new PublicReturnRequest(), httpRequest).getBody();

        assertThat(body).isSameAs(tracking);
        verify(service).createReturn(eq(deliveryId), any());
    }

    @Test
    void createReturn_rateLimited_throws429_withoutCallingService() {
        when(rateLimiter.tryAcquire(any(), eq(deliveryId))).thenReturn(false);

        assertThatThrownBy(() -> controller.createReturn(deliveryId, new PublicReturnRequest(), httpRequest))
                .isInstanceOfSatisfying(AppException.class,
                        e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS));
        verify(service, never()).createReturn(any(), any());
    }

    @Test
    void cancelReturn_allowed_delegates() {
        when(rateLimiter.tryAcquire(any(), eq(deliveryId))).thenReturn(true);
        var tracking = TrackingResponse.builder().deliveryId(deliveryId.toString()).build();
        when(service.cancelReturn(deliveryId)).thenReturn(tracking);
        assertThat(controller.cancelReturn(deliveryId, httpRequest).getBody()).isSameAs(tracking);
    }

    @Test
    void uploadPhotos_allowed_delegates() {
        when(rateLimiter.tryAcquire(any(), eq(deliveryId))).thenReturn(true);
        when(service.uploadPhotos(eq(deliveryId), any())).thenReturn(List.of("http://minio/x.jpg"));
        assertThat(controller.uploadPhotos(deliveryId, List.of(), httpRequest).getBody())
                .containsExactly("http://minio/x.jpg");
    }
}
