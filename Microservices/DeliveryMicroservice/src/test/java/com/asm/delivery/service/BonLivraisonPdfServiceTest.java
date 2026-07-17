package com.asm.delivery.service;

import com.asm.delivery.entity.Company;
import com.asm.delivery.entity.Delivery;
import com.asm.delivery.entity.Order;
import com.asm.delivery.entity.OrderItem;
import com.asm.delivery.exception.AppException;
import com.asm.delivery.repository.CompanyRepository;
import com.asm.delivery.repository.DeliveryRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.awt.Color;
import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class BonLivraisonPdfServiceTest {

    @Mock DeliveryRepository deliveryRepository;
    @Mock CompanyRepository companyRepository;
    @Mock CompanyBrandingResolver brandingResolver;

    private BonLivraisonPdfService service;
    private UUID deliveryId;

    @BeforeEach
    void setUp() {
        service = new BonLivraisonPdfService(deliveryRepository, companyRepository, brandingResolver);
        deliveryId = UUID.randomUUID();
        // The branding header is provided by the resolver; stub it so the PDF renders without MinIO.
        lenient().when(brandingResolver.resolve(anyString(), anyString()))
                .thenReturn(new BasePdfService.ReportPageEvent(
                        "BON DE LIVRAISON", "N° X", "ASM Track", null, new Color(30, 80, 160)));
    }

    private static Order orderWithItems() {
        return Order.builder()
                .clientName("Client Test")
                .clientPhone("+216 20 000 000")
                .dropoffAddress("12 Rue de Tunis")
                .dropoffCity("Sfax")
                .blNumber("WH/OUT/00042")
                .erpExternalRef("S00110")
                .totalAmount(new BigDecimal("150.000"))
                .currency("TND")
                .items(List.of(
                        OrderItem.builder().sku("SKU-1").name("Article A").quantity(2)
                                .unitWeightKg(new BigDecimal("1.5")).build(),
                        OrderItem.builder().sku("SKU-2").name("Article B").quantity(1)
                                .unitWeightKg(new BigDecimal("0.5")).build()))
                .build();
    }

    private static boolean isPdf(byte[] bytes) {
        return bytes != null && bytes.length > 4
                && bytes[0] == '%' && bytes[1] == 'P' && bytes[2] == 'D' && bytes[3] == 'F';
    }

    @Test
    void generatesValidPdfWithCompanyAndItems() {
        Delivery delivery = Delivery.builder().id(deliveryId).blNumber("WH/OUT/00042").order(orderWithItems()).build();
        Company company = Company.builder().name("ASM SARL").address("Zone Industrielle").city("Tunis")
                .taxId("1234567A/M/000").registrationNumber("B123456789").phone("+216 71 000 000").active(true).build();

        when(deliveryRepository.findByIdWithOrder(deliveryId)).thenReturn(Optional.of(delivery));
        when(companyRepository.findAllByActiveTrue()).thenReturn(List.of(company));

        byte[] pdf = service.generate(deliveryId);

        assertThat(isPdf(pdf)).isTrue();
        assertThat(pdf.length).isGreaterThan(800);
    }

    @Test
    void generatesWithoutCompany_fallsBackToNeutralBranding() {
        Delivery delivery = Delivery.builder().id(deliveryId).order(orderWithItems()).build();
        when(deliveryRepository.findByIdWithOrder(deliveryId)).thenReturn(Optional.of(delivery));
        when(companyRepository.findAllByActiveTrue()).thenReturn(List.of());

        byte[] pdf = service.generate(deliveryId);

        assertThat(isPdf(pdf)).isTrue();
    }

    @Test
    void generatesWithNoItems() {
        Order order = Order.builder().clientName("Client").dropoffAddress("Adresse")
                .blNumber("WH/OUT/1").items(List.of()).build();
        Delivery delivery = Delivery.builder().id(deliveryId).order(order).build();
        when(deliveryRepository.findByIdWithOrder(deliveryId)).thenReturn(Optional.of(delivery));
        when(companyRepository.findAllByActiveTrue()).thenReturn(List.of());

        byte[] pdf = service.generate(deliveryId);

        assertThat(isPdf(pdf)).isTrue();
    }

    @Test
    void throwsWhenDeliveryHasNoOrder() {
        Delivery delivery = Delivery.builder().id(deliveryId).order(null).build();
        when(deliveryRepository.findByIdWithOrder(deliveryId)).thenReturn(Optional.of(delivery));

        assertThatThrownBy(() -> service.generate(deliveryId)).isInstanceOf(AppException.class);
    }

    @Test
    void throwsWhenDeliveryNotFound() {
        when(deliveryRepository.findByIdWithOrder(deliveryId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.generate(deliveryId)).isInstanceOf(AppException.class);
    }
}
