package com.asm.delivery.service;

import com.asm.delivery.dto.request.CreateRmaRequest;
import com.asm.delivery.dto.request.PublicReturnRequest;
import com.asm.delivery.dto.response.PublicReturnableItemsResponse;
import com.asm.delivery.dto.response.RmaResponse;
import com.asm.delivery.dto.response.TrackingResponse;
import com.asm.delivery.entity.*;
import com.asm.delivery.exception.AppException;
import com.asm.delivery.repository.DeliveryRepository;
import com.asm.delivery.repository.RmaPhotoRepository;
import com.asm.delivery.repository.RmaRepository;
import com.asm.delivery.security.UserPrincipal;
import com.asm.delivery.storage.RmaPhotoStorageService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.math.BigDecimal;
import java.util.*;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class PublicRmaServiceTest {

    @Mock DeliveryRepository deliveryRepository;
    @Mock RmaRepository rmaRepository;
    @Mock RmaPhotoRepository rmaPhotoRepository;
    @Mock RmaService rmaService;
    @Mock RmaPhotoStorageService photoService;
    @Mock PublicTrackingService publicTrackingService;
    // Added to the service when photo keys stopped being stored with their presigned signature; the
    // test kept passing locally only because the image build skips tests.
    @Mock com.asm.delivery.storage.MediaUrlResolver mediaUrlResolver;

    @InjectMocks PublicRmaService service;

    private UUID deliveryId;
    private Delivery delivery;

    @BeforeEach
    void setUp() {
        deliveryId = UUID.randomUUID();
        // Identity by default: these tests are about what gets stored, not about how a URL is signed.
        when(mediaUrlResolver.toKey(anyString())).thenAnswer(inv -> inv.getArgument(0));
        when(mediaUrlResolver.toPublicUrl(anyString())).thenAnswer(inv -> inv.getArgument(0));
        Order order = Order.builder()
                .id(UUID.randomUUID())
                .clientName("Client Test")
                .items(new ArrayList<>(List.of(
                        OrderItem.builder().sku("SKU-A").name("Chair").quantity(5).quantityDone(5).unitPrice(new BigDecimal("10")).build()
                )))
                .build();
        delivery = Delivery.builder().id(deliveryId).order(order).status(DeliveryStatus.DELIVERED).build();

        when(deliveryRepository.findByIdWithOrder(deliveryId)).thenReturn(Optional.of(delivery));
        when(publicTrackingService.getTracking(deliveryId)).thenReturn(TrackingResponse.builder().deliveryId(deliveryId.toString()).build());
    }

    @Test
    void getReturnableItems_computesReturnable() {
        when(rmaRepository.findByDeliveryIdOrderByCreatedAtDesc(deliveryId)).thenReturn(List.of());
        when(rmaService.returnableQuantitiesBySku(delivery)).thenReturn(Map.of("SKU-A", 3));

        PublicReturnableItemsResponse resp = service.getReturnableItems(deliveryId);

        assertThat(resp.isReturnable()).isTrue();
        assertThat(resp.isHasOpenReturn()).isFalse();
        assertThat(resp.getItems()).hasSize(1);
        PublicReturnableItemsResponse.ReturnableItem item = resp.getItems().get(0);
        assertThat(item.getSku()).isEqualTo("SKU-A");
        assertThat(item.getReturnableQty()).isEqualTo(3);
        assertThat(item.getDeliveredQty()).isEqualTo(5);
    }

    @Test
    void getReturnableItems_flagsOpenReturn() {
        Rma open = Rma.builder().id(UUID.randomUUID()).deliveryId(deliveryId).status(RmaStatus.REQUESTED).build();
        when(rmaRepository.findByDeliveryIdOrderByCreatedAtDesc(deliveryId)).thenReturn(List.of(open));
        when(rmaService.returnableQuantitiesBySku(delivery)).thenReturn(Map.of("SKU-A", 3));

        PublicReturnableItemsResponse resp = service.getReturnableItems(deliveryId);

        assertThat(resp.isHasOpenReturn()).isTrue();
        assertThat(resp.getOpenReturnId()).isEqualTo(open.getId());
        assertThat(resp.getOpenReturnStatus()).isEqualTo("REQUESTED");
    }

    @Test
    void getReturnableItems_hidesFullyReturnedRows() {
        when(rmaRepository.findByDeliveryIdOrderByCreatedAtDesc(deliveryId)).thenReturn(List.of());
        when(rmaService.returnableQuantitiesBySku(delivery)).thenReturn(Map.of("SKU-A", 0));

        assertThat(service.getReturnableItems(deliveryId).getItems()).isEmpty();
    }

    @Test
    void createReturn_delegatesWithClientActorAndPersistsPhotos() {
        UUID rmaId = UUID.randomUUID();
        when(rmaService.create(any(CreateRmaRequest.class), any())).thenReturn(RmaResponse.builder().id(rmaId).build());

        PublicReturnRequest req = new PublicReturnRequest();
        CreateRmaRequest.Item it = new CreateRmaRequest.Item();
        it.setSku("SKU-A");
        it.setQuantity(1);
        it.setCondition(RmaItemCondition.DAMAGED);
        req.setItems(List.of(it));
        req.setPhotoUrls(List.of("http://minio/rma/x.jpg"));

        service.createReturn(deliveryId, req);

        ArgumentCaptor<UserPrincipal> actor = ArgumentCaptor.forClass(UserPrincipal.class);
        ArgumentCaptor<CreateRmaRequest> created = ArgumentCaptor.forClass(CreateRmaRequest.class);
        verify(rmaService).create(created.capture(), actor.capture());
        assertThat(actor.getValue().getRole()).isEqualTo("CLIENT");
        assertThat(created.getValue().getDeliveryId()).isEqualTo(deliveryId);

        ArgumentCaptor<RmaPhoto> photo = ArgumentCaptor.forClass(RmaPhoto.class);
        verify(rmaPhotoRepository).save(photo.capture());
        assertThat(photo.getValue().getRmaId()).isEqualTo(rmaId);
        assertThat(photo.getValue().getUrl()).isEqualTo("http://minio/rma/x.jpg");

        verify(publicTrackingService).getTracking(deliveryId);
    }

    @Test
    void cancelReturn_transitionsRequestedToCancelledAsClient() {
        Rma open = Rma.builder().id(UUID.randomUUID()).deliveryId(deliveryId).status(RmaStatus.REQUESTED).build();
        when(rmaRepository.findByDeliveryIdOrderByCreatedAtDesc(deliveryId)).thenReturn(List.of(open));

        service.cancelReturn(deliveryId);

        ArgumentCaptor<String> note = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<UserPrincipal> actor = ArgumentCaptor.forClass(UserPrincipal.class);
        verify(rmaService).transition(eq(open.getId()), eq(RmaStatus.CANCELLED), note.capture(), actor.capture());
        assertThat(note.getValue()).isNotBlank();
        assertThat(actor.getValue().getRole()).isEqualTo("CLIENT");
    }

    @Test
    void cancelReturn_rejectsNonRequested() {
        Rma open = Rma.builder().id(UUID.randomUUID()).deliveryId(deliveryId).status(RmaStatus.APPROVED).build();
        when(rmaRepository.findByDeliveryIdOrderByCreatedAtDesc(deliveryId)).thenReturn(List.of(open));

        assertThatThrownBy(() -> service.cancelReturn(deliveryId)).isInstanceOf(AppException.class);
        verify(rmaService, never()).transition(any(), any(), any(), any());
    }

    @Test
    void cancelReturn_noOpenReturn_throwsNotFound() {
        when(rmaRepository.findByDeliveryIdOrderByCreatedAtDesc(deliveryId)).thenReturn(List.of());
        assertThatThrownBy(() -> service.cancelReturn(deliveryId)).isInstanceOf(AppException.class);
    }

    @Test
    void uploadPhotos_guardsUnknownDelivery() {
        UUID unknown = UUID.randomUUID();
        when(deliveryRepository.existsById(unknown)).thenReturn(false);
        assertThatThrownBy(() -> service.uploadPhotos(unknown, List.of())).isInstanceOf(AppException.class);
        verifyNoInteractions(photoService);
    }
}
