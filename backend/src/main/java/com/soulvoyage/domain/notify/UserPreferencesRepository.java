package com.soulvoyage.domain.notify;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface UserPreferencesRepository extends JpaRepository<UserPreferencesEntity, Long> {

    Optional<UserPreferencesEntity> findByUserId(Long userId);

    /** G5 定时提醒扫描（提醒默认关，开启者才被扫到） */
    List<UserPreferencesEntity> findByCheckinReminderOnOrderByUserId(Short on);

    List<UserPreferencesEntity> findByPlanReminderOnOrderByUserId(Short on);

    /** G6 成长来信受众（默认开） */
    List<UserPreferencesEntity> findByLetterOnOrderByUserId(Short on);

    /** M12 群体授权集：候选成员 ∩ counselor_board_on=1（无偏好行=从未开过=不授权） */
    @Query("select p.userId from UserPreferencesEntity p where p.userId in :ids and p.counselorBoardOn = 1")
    List<Long> findBoardConsentedUserIds(@Param("ids") List<Long> ids);
}
