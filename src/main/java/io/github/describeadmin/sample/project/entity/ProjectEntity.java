package io.github.describeadmin.sample.project.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import io.github.describeadmin.mybatis.api.BaseEntity;
import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * 项目。
 *
 * <p>由 codegen 生成。审计字段（创建人/创建时间、更新人/更新时间、逻辑删除、乐观锁版本）
 * 与主键均由 {@link BaseEntity} 承担，不要在此重复声明。
 */
@TableName("biz_project")
public class ProjectEntity extends BaseEntity {

    /** 项目名称 */
    private String projectName;

    /** 项目编号 */
    private String projectCode;

    /** 归口部门ID */
    private Long ownerDeptId;

    /** 预算金额 */
    private BigDecimal budget;

    /** 开始日期 */
    private LocalDate startDate;

    /** 状态 */
    private Integer status;

    /** 备注 */
    private String remark;

    public String getProjectName() {
        return projectName;
    }

    public void setProjectName(String projectName) {
        this.projectName = projectName;
    }

    public String getProjectCode() {
        return projectCode;
    }

    public void setProjectCode(String projectCode) {
        this.projectCode = projectCode;
    }

    public Long getOwnerDeptId() {
        return ownerDeptId;
    }

    public void setOwnerDeptId(Long ownerDeptId) {
        this.ownerDeptId = ownerDeptId;
    }

    public BigDecimal getBudget() {
        return budget;
    }

    public void setBudget(BigDecimal budget) {
        this.budget = budget;
    }

    public LocalDate getStartDate() {
        return startDate;
    }

    public void setStartDate(LocalDate startDate) {
        this.startDate = startDate;
    }

    public Integer getStatus() {
        return status;
    }

    public void setStatus(Integer status) {
        this.status = status;
    }

    public String getRemark() {
        return remark;
    }

    public void setRemark(String remark) {
        this.remark = remark;
    }
}
