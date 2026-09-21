package com.soulvoyage.domain.board;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface SupportGroupMemberRepository extends JpaRepository<SupportGroupMemberEntity, Long> {

    @Query("select m.userId from SupportGroupMemberEntity m where m.groupId = :gid order by m.userId asc")
    List<Long> findUserIdsByGroupId(@Param("gid") Long groupId);

    boolean existsByGroupIdAndUserId(Long groupId, Long userId);

    long countByGroupId(Long groupId);

    void deleteByGroupIdAndUserId(Long groupId, Long userId);
}
