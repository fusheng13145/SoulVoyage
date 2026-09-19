package com.soulvoyage.domain.notify;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface UserPreferencesRepository extends JpaRepository<UserPreferencesEntity, Long> {

    Optional<UserPreferencesEntity> findByUserId(Long userId);

    /** G5 定时提醒扫描（提醒默认关，开启者才被扫到） */
    List<UserPreferencesEntity> findByCheckinReminderOnOrderByUserId(Short on);

    List<UserPreferencesEntity> findByPlanReminderOnOrderByUserId(Short on);

    /** G6 成长来信受众（默认开） */
    List<UserPreferencesEntity> findByLetterOnOrderByUserId(Short on);
}
