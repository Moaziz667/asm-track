package com.asm.delivery.repository;

import com.asm.delivery.entity.RmaPhoto;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface RmaPhotoRepository extends JpaRepository<RmaPhoto, UUID> {

    List<RmaPhoto> findByRmaIdOrderByCreatedAtAsc(UUID rmaId);

    List<RmaPhoto> findByRmaIdIn(List<UUID> rmaIds);
}
