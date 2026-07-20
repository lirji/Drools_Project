package com.lrj.drools.activity.persistence;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface ActivityManageRepository extends JpaRepository<ActivityManageEntity, Long> {

    /** 定位某活动的指定版本行（isDel 一般传 0）。 */
    Optional<ActivityManageEntity> findFirstByActivityIdAndVersionAndIsDel(String activityId, Integer version, Integer isDel);

    /** 当前生效版本（未删除里 version 最大的）。 */
    Optional<ActivityManageEntity> findFirstByActivityIdAndIsDelOrderByVersionDesc(String activityId, Integer isDel);

    /** 幂等：同 requestId 首次结果。 */
    Optional<ActivityManageEntity> findFirstByRequestIdAndIsDel(String requestId, Integer isDel);

    /** 列表（未删除，按最近修改倒序）。 */
    List<ActivityManageEntity> findByIsDelOrderByModifiedStimeDesc(Integer isDel);

    List<ActivityManageEntity> findByActivityStatusAndIsDel(Integer activityStatus, Integer isDel);

    /**
     * 逻辑删除旧版本行，返回受影响行数。用于版本化编辑的并发保护：
     * 影响行数为 0 说明旧版本已被别的请求删掉（并发双写），调用方返回 409。
     */
    @Modifying
    @Query("update ActivityManageEntity e set e.isDel = 1, e.modifiedStime = :now " +
            "where e.activityId = :activityId and e.version = :version and e.isDel = 0")
    int softDeleteVersion(@Param("activityId") String activityId,
                          @Param("version") Integer version,
                          @Param("now") Instant now);
}
