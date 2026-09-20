package com.soulvoyage.domain.user;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface UserRepository extends JpaRepository<UserEntity, Long> {
    Optional<UserEntity> findByUsernameAndDeletedAtIsNull(String username);
    Optional<UserEntity> findByIdAndDeletedAtIsNull(Long id);

    List<UserEntity> findByCrisisState(String crisisState);

    List<UserEntity> findByCrisisStateAndCrisisEndsAtBefore(String crisisState, Instant cutoff);

    /** S2 注销冷静期到期候选：status=3 且申请时间早于冷静期终点 */
    List<UserEntity> findByStatusAndDeletionRequestedAtBefore(Short status, Instant cutoff);

    /** A4 危机名单看板：非 NORMAL 全部（CRISIS/COOLING/REVIEW） */
    List<UserEntity> findByCrisisStateNotOrderByCrisisStartedAtAsc(String crisisState);

    /** A4 用户查询：status/关键词（用户名或昵称）过滤分页，只见元数据 */
    @Query("""
            select u from UserEntity u
            where (:st is null or u.status = :st)
              and (:kw is null or u.username like concat('%', :kw, '%')
                           or u.nickname like concat('%', :kw, '%'))
            order by u.id desc
            """)
    Page<UserEntity> findForAdmin(@Param("st") Short status, @Param("kw") String keyword, Pageable pageable);
}
