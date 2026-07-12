package com.asm.delivery.controller;

import com.asm.delivery.dto.request.CreateRmaRequest;
import com.asm.delivery.dto.response.RmaResponse;
import com.asm.delivery.entity.RmaItemCondition;
import com.asm.delivery.entity.RmaStatus;
import com.asm.delivery.security.UserPrincipal;
import com.asm.delivery.service.RmaService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class AdminRmaControllerTest {

    @Mock RmaService rmaService;
    @InjectMocks AdminRmaController controller;

    private UserPrincipal principal;

    @BeforeEach
    void setUp() {
        principal = new UserPrincipal(UUID.randomUUID().toString(), "ADMIN", "Test", null);
    }

    @Test
    @DisplayName("list delegates to rmaService.list")
    void list_delegatesToService() {
        Page<RmaResponse> page = new PageImpl<>(List.of());
        when(rmaService.list(eq(RmaStatus.REQUESTED), eq("test"), any(), any(), any(Pageable.class))).thenReturn(page);

        var resp = controller.list(RmaStatus.REQUESTED, "test", null, null, Pageable.unpaged());

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        verify(rmaService).list(eq(RmaStatus.REQUESTED), eq("test"), any(), any(), eq(Pageable.unpaged()));
    }

    @Test
    @DisplayName("kpi delegates to rmaService.kpi")
    void kpi_delegatesToService() {
        Map<String, Object> kpi = Map.of("total", 5L, "open", 3);
        when(rmaService.kpi()).thenReturn(kpi);

        var resp = controller.kpi();

        assertThat(resp.getBody()).isEqualTo(kpi);
    }

    @Test
    @DisplayName("get delegates to rmaService.get")
    void get_delegatesToService() {
        UUID id = UUID.randomUUID();
        RmaResponse rma = RmaResponse.builder().id(id).status(RmaStatus.REQUESTED).build();
        when(rmaService.get(id)).thenReturn(rma);

        var resp = controller.get(id);

        assertThat(resp.getBody().getId()).isEqualTo(id);
    }

    @Test
    @DisplayName("create delegates to rmaService.create")
    void create_delegatesToService() {
        CreateRmaRequest req = new CreateRmaRequest();
        req.setDeliveryId(UUID.randomUUID());
        req.setItems(List.of());
        RmaResponse rma = RmaResponse.builder().id(UUID.randomUUID()).status(RmaStatus.REQUESTED).build();
        when(rmaService.create(eq(req), eq(principal))).thenReturn(rma);

        var resp = controller.create(principal, req);

        assertThat(resp.getBody().getStatus()).isEqualTo(RmaStatus.REQUESTED);
    }

    @Test
    @DisplayName("transition delegates to rmaService.transition")
    void transition_delegatesToService() {
        UUID id = UUID.randomUUID();
        RmaResponse rma = RmaResponse.builder().id(id).status(RmaStatus.APPROVED).build();
        when(rmaService.transition(id, RmaStatus.APPROVED, "ok", principal)).thenReturn(rma);

        var resp = controller.transition(principal, id, RmaStatus.APPROVED, "ok");

        assertThat(resp.getBody().getStatus()).isEqualTo(RmaStatus.APPROVED);
    }

    @Test
    @DisplayName("resync delegates to rmaService.resync")
    void resync_delegatesToService() {
        UUID id = UUID.randomUUID();
        RmaResponse rma = RmaResponse.builder().id(id).status(RmaStatus.RESTOCKED).build();
        when(rmaService.resync(id, principal)).thenReturn(rma);

        var resp = controller.resync(principal, id);

        assertThat(resp.getBody().getStatus()).isEqualTo(RmaStatus.RESTOCKED);
    }
}
