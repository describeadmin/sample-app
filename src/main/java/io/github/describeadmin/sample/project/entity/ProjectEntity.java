package io.github.describeadmin.sample.project.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import io.github.describeadmin.mybatis.api.BaseEntity;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * 项目 —— 一张明确属于【业务域】的表。
 *
 * <p>选它而不是"部门"是有意的：部门、用户、角色、菜单都属于系统管理，
 * 由 framework-system-starter 提供，业务方不应重复实现。
 * 本类演示的是业务方真正需要自己写的那部分代码。
 *
 * <p>注意这个类有多薄：审计字段、逻辑删除、乐观锁、主键策略全部由 BaseEntity 承担。
 */
@TableName("biz_project")
public class ProjectEntity extends BaseEntity {

    private String projectName;
    private String projectCode;
    private Long ownerDeptId;
    private BigDecimal budget;
    private LocalDate startDate;
    private Integer status;

    public String getProjectName() { return projectName; }
    public void setProjectName(String projectName) { this.projectName = projectName; }
    public String getProjectCode() { return projectCode; }
    public void setProjectCode(String projectCode) { this.projectCode = projectCode; }
    public Long getOwnerDeptId() { return ownerDeptId; }
    public void setOwnerDeptId(Long ownerDeptId) { this.ownerDeptId = ownerDeptId; }
    public BigDecimal getBudget() { return budget; }
    public void setBudget(BigDecimal budget) { this.budget = budget; }
    public LocalDate getStartDate() { return startDate; }
    public void setStartDate(LocalDate startDate) { this.startDate = startDate; }
    public Integer getStatus() { return status; }
    public void setStatus(Integer status) { this.status = status; }
}
