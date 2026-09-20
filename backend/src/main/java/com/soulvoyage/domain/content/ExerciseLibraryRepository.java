package com.soulvoyage.domain.content;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface ExerciseLibraryRepository extends JpaRepository<ExerciseLibraryEntity, Long> {
    List<ExerciseLibraryEntity> findByStatusOrderByIdAsc(Short status);

    Optional<ExerciseLibraryEntity> findByCode(String code);
}
