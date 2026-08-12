package com.lrj.drools.activity.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;

/**
 * 活动基础层。收敛自来源 {@code ActivityAdminPlatformManage}（去掉合伙人/审核/权益系统 id 等 demo 无关列）。
 *
 * 版本化：同一 {@code activityId} 可有多行，靠 {@code version} 区分；编辑时旧行 {@code isDel=1}，新行 version+1。
 * 主键用自增代理键（因为一个 activityId 对应多版本行），(activityId, version, isDel) 是逻辑键。
 */
@Entity
@Table(name = "activity_manage",
        // 生产硬化(P1-15)：热点索引一律以 tenant_id 打头(判别式多租户下每条查询都带 tenant 谓词)。
        indexes = {
                @Index(name = "idx_am_aid_ver_del", columnList = "tenant_id,activity_id,version,is_del"),
                @Index(name = "idx_am_status_time", columnList = "tenant_id,activity_status,activity_start_time,activity_end_time"),
                @Index(name = "idx_am_request", columnList = "tenant_id,request_id")
        },
        // 幂等硬化(P1-o)：同租户同 requestId 唯一(request_id 可空→多空值放行)，并发重复由 DB 兜底而非仅 check-then-insert。
        uniqueConstraints = {
                @jakarta.persistence.UniqueConstraint(
                        name = ActivityManageEntity.UK_TENANT_REQUEST,
                        columnNames = {"tenant_id", "request_id"})
        })
public class ActivityManageEntity extends SoftDeletableTenantEntity {

    /**
     * 幂等唯一约束名。<b>写平面要按它分辨「并发重复请求(409)」与其它完整性错误</b>
     * （见 {@link ConstraintViolations}），所以它是被代码读取的标识，不只是 DDL 里的一个名字——
     * 常量化后改名时约束声明与判定处一起走，不会只改一边。
     */
    public static final String UK_TENANT_REQUEST = "uk_am_tenant_request";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "activity_id", length = 64, nullable = false)
    private String activityId;

    @Column(name = "activity_name", length = 128, nullable = false)
    private String activityName;

    @Column(name = "biz_line", length = 64)
    private String bizLine;

    /** 1-红包 2-优惠券 3-CPS 4-权益券 5-买赠（见 ActivityType）。 */
    @Column(name = "activity_type", nullable = false)
    private Integer activityType;

    /** 发放规则文字外显（运营给用户看的说明）。 */
    @JdbcTypeCode(SqlTypes.LONGVARCHAR)
    @Column(name = "activity_rule")
    private String activityRule;

    @Column(name = "activity_start_time", nullable = false)
    private Instant activityStartTime;

    @Column(name = "activity_end_time", nullable = false)
    private Instant activityEndTime;

    /** 0-待上线 1-已上线 2-已下线 3-待生效（见 ActivityStatus）。 */
    @Column(name = "activity_status", nullable = false)
    private Integer activityStatus;

    /** 1-全国 2-指定地域。 */
    @Column(name = "activity_area_type")
    private Integer activityAreaType;

    /** 省市区 id，逗号分隔。 */
    @Column(name = "district_ids", length = 1024)
    private String districtIds;

    /** 多活动碰撞优先级，越小越优先。 */
    @Column(name = "priority")
    private Integer priority;

    @Column(name = "inventory")
    private Integer inventory;

    @Column(name = "user_inventory")
    private Integer userInventory;

    @Column(name = "version", nullable = false)
    private Integer version;

    /** 幂等键：同 requestId 重复提交返回首次结果。 */
    @Column(name = "request_id", length = 64)
    private String requestId;

    /** P1-8 四眼：本版本的提交人（创建/编辑者身份，来自 ActorContext）。发布时校验审批人≠提交人。 */
    @Column(name = "submitted_by", length = 128)
    private String submittedBy;

    public ActivityManageEntity() {}

    public Long getId() { return id; }

    public String getActivityId() { return activityId; }
    public void setActivityId(String activityId) { this.activityId = activityId; }

    public String getActivityName() { return activityName; }
    public void setActivityName(String activityName) { this.activityName = activityName; }

    public String getBizLine() { return bizLine; }
    public void setBizLine(String bizLine) { this.bizLine = bizLine; }

    public Integer getActivityType() { return activityType; }
    public void setActivityType(Integer activityType) { this.activityType = activityType; }

    public String getActivityRule() { return activityRule; }
    public void setActivityRule(String activityRule) { this.activityRule = activityRule; }

    public Instant getActivityStartTime() { return activityStartTime; }
    public void setActivityStartTime(Instant activityStartTime) { this.activityStartTime = activityStartTime; }

    public Instant getActivityEndTime() { return activityEndTime; }
    public void setActivityEndTime(Instant activityEndTime) { this.activityEndTime = activityEndTime; }

    public Integer getActivityStatus() { return activityStatus; }
    public void setActivityStatus(Integer activityStatus) { this.activityStatus = activityStatus; }

    public Integer getActivityAreaType() { return activityAreaType; }
    public void setActivityAreaType(Integer activityAreaType) { this.activityAreaType = activityAreaType; }

    public String getDistrictIds() { return districtIds; }
    public void setDistrictIds(String districtIds) { this.districtIds = districtIds; }

    public Integer getPriority() { return priority; }
    public void setPriority(Integer priority) { this.priority = priority; }

    public Integer getInventory() { return inventory; }
    public void setInventory(Integer inventory) { this.inventory = inventory; }

    public Integer getUserInventory() { return userInventory; }
    public void setUserInventory(Integer userInventory) { this.userInventory = userInventory; }

    public Integer getVersion() { return version; }
    public void setVersion(Integer version) { this.version = version; }

    public String getRequestId() { return requestId; }
    public void setRequestId(String requestId) { this.requestId = requestId; }

    public String getSubmittedBy() { return submittedBy; }
    public void setSubmittedBy(String submittedBy) { this.submittedBy = submittedBy; }
}
