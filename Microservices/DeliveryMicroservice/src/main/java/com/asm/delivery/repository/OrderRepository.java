package com.asm.delivery.repository;

import com.asm.delivery.entity.Order;
import com.asm.delivery.entity.OrderStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface OrderRepository extends JpaRepository<Order, UUID> {

    List<Order> findByClientIdOrderByCreatedAtDesc(String clientId);

    List<Order> findByClientIdAndStatusNotInOrderByCreatedAtDesc(String clientId, List<OrderStatus> terminalStatuses);

    Optional<Order> findByErpOrderId(String erpOrderId);

    boolean existsByErpOrderId(String erpOrderId);
}
