package com.leyon.soul.domain.user;

import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface UserRepository extends JpaRepository<UserEntity, Long> {
    Optional<UserEntity> findByUsernameAndDeletedAtIsNull(String username);
    Optional<UserEntity> findByIdAndDeletedAtIsNull(Long id);

    /**
     * 同用户串行化点（2026-11-20 并发审查修复）：任务并发上限与每日打卡都靠"先读后写"，
     * 而 count-then-insert / find-or-create 在多请求下不是原子的（实测 60 并发可穿透 2 个活跃任务上限、
     * 10 并发同日打卡有 7 个撞唯一键 500）。这里借用户行锁把同一用户的这类写操作排队，
     * 不引入新表、不依赖 Redis，H2 与 MySQL 都支持 SELECT ... FOR UPDATE。
     * 必须在事务内调用，否则锁随语句结束即释放。
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select u from UserEntity u where u.id = :id")
    Optional<UserEntity> lockById(@Param("id") Long id);

    /** M14 微信登录：openid → 已绑定的在册账号 */
    Optional<UserEntity> findByWxOpenidAndDeletedAtIsNull(String wxOpenid);

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
