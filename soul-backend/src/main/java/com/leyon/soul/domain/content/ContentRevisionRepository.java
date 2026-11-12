package com.leyon.soul.domain.content;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface ContentRevisionRepository extends JpaRepository<ContentRevisionEntity, Long> {

    List<ContentRevisionEntity> findByContentTypeAndCodeOrderByRevisionNoDesc(String contentType, String code);

    Optional<ContentRevisionEntity> findTopByContentTypeAndCodeOrderByRevisionNoDesc(String contentType, String code);

    Optional<ContentRevisionEntity> findByIdAndContentTypeAndCode(Long id, String contentType, String code);
}
