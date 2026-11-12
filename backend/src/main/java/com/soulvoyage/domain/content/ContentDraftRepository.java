package com.soulvoyage.domain.content;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface ContentDraftRepository extends JpaRepository<ContentDraftEntity, Long> {

    Optional<ContentDraftEntity> findByContentTypeAndCode(String contentType, String code);

    List<ContentDraftEntity> findByContentTypeOrderByUpdatedAtDesc(String contentType);

    List<ContentDraftEntity> findAllByOrderByUpdatedAtDesc();

    void deleteByContentTypeAndCode(String contentType, String code);
}
