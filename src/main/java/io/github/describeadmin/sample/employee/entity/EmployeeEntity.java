package io.github.describeadmin.sample.employee.entity;

import com.baomidou.mybatisplus.annotation.FieldStrategy;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import io.github.describeadmin.crypto.api.AesEncryptedStringTypeHandler;
import io.github.describeadmin.mybatis.api.BaseEntity;

/**
 * 员工。
 *
 * <p>由 codegen 生成，之后按需手工补了敏感字段加密（framework-crypto-starter）。
 * 审计字段（创建人/创建时间、更新人/更新时间、逻辑删除、乐观锁版本）与主键均由
 * {@link BaseEntity} 承担，不要在此重复声明。
 *
 * <p><b>身份证号 / 手机号加密入库</b>：{@code idCard}、{@code mobile} 用
 * {@link AesEncryptedStringTypeHandler} 做 MyBatis-Plus 透明加解密（AES-256-GCM），
 * 库里存的是 {@code AES256-GCM:<Base64>} 密文，业务代码全程只碰明文 getter/setter。
 * 两处配置缺一不可：
 * <ul>
 *   <li>{@code @TableName(autoResultMap = true)} —— 没有它 SELECT 不会套用 typeHandler，
 *       查出来是密文原样，且不报错（见插件 README「两条必须知道的边界」第 1 条）</li>
 *   <li>{@code updateStrategy = FieldStrategy.NOT_NULL} —— partial update 未带该字段时
 *       不把密文列显式写成 NULL</li>
 * </ul>
 * 本模块没有「按身份证号精确查一行」的需求，因此不加 {@code @BlindIndex}/盲索引列。
 */
@TableName(value = "biz_employee", autoResultMap = true)
public class EmployeeEntity extends BaseEntity {

    /** 员工姓名 */
    private String employeeName;

    /** 性别 */
    private String gender;

    /** 身份证号（AES-256-GCM 加密入库） */
    @TableField(typeHandler = AesEncryptedStringTypeHandler.class, updateStrategy = FieldStrategy.NOT_NULL)
    private String idCard;

    /** 手机号码（AES-256-GCM 加密入库） */
    @TableField(typeHandler = AesEncryptedStringTypeHandler.class, updateStrategy = FieldStrategy.NOT_NULL)
    private String mobile;

    /** 家庭住址 */
    private String address;

    /** 备注 */
    private String remark;

    public String getEmployeeName() {
        return employeeName;
    }

    public void setEmployeeName(String employeeName) {
        this.employeeName = employeeName;
    }

    public String getGender() {
        return gender;
    }

    public void setGender(String gender) {
        this.gender = gender;
    }

    public String getIdCard() {
        return idCard;
    }

    public void setIdCard(String idCard) {
        this.idCard = idCard;
    }

    public String getMobile() {
        return mobile;
    }

    public void setMobile(String mobile) {
        this.mobile = mobile;
    }

    public String getAddress() {
        return address;
    }

    public void setAddress(String address) {
        this.address = address;
    }

    public String getRemark() {
        return remark;
    }

    public void setRemark(String remark) {
        this.remark = remark;
    }
}
