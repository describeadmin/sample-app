package io.github.describeadmin.sample.dept.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import io.github.describeadmin.mybatis.api.BaseEntity;

/**
 * 部门。
 *
 * <p>注意这个类有多"薄"——审计字段、逻辑删除、乐观锁、主键策略全部由 {@link BaseEntity} 承担，
 * 这里只有业务自己的字段。框架升级时改的是基类，本文件基本不需要跟着动。
 */
@TableName("biz_dept")
public class DeptEntity extends BaseEntity {

    private String deptName;
    private Long parentId;
    private Integer sort;

    public String getDeptName() { return deptName; }
    public void setDeptName(String deptName) { this.deptName = deptName; }
    public Long getParentId() { return parentId; }
    public void setParentId(Long parentId) { this.parentId = parentId; }
    public Integer getSort() { return sort; }
    public void setSort(Integer sort) { this.sort = sort; }
}
