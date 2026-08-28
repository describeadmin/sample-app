package io.github.describeadmin.sample.nurse.entity;

import com.baomidou.mybatisplus.annotation.FieldStrategy;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.fasterxml.jackson.annotation.JsonIgnore;
import io.github.describeadmin.crypto.api.AesEncryptedStringTypeHandler;
import io.github.describeadmin.crypto.api.BlindIndex;
import io.github.describeadmin.mybatis.api.BaseEntity;
import java.time.LocalDate;

/**
 * 护理员。
 *
 * <p>由 codegen 生成，之后按需手工补了敏感字段加密 + 盲索引（framework-crypto-starter）。
 * 审计字段（创建人/创建时间、更新人/更新时间、逻辑删除、乐观锁版本）与主键均由
 * {@link BaseEntity} 承担，不要在此重复声明。
 *
 * <p><b>手机号码 / 身份证号码：加密入库 + 支持按明文精确搜索</b>
 * <ul>
 *   <li>{@code mobile} / {@code idCard} 用 {@link AesEncryptedStringTypeHandler} 做
 *       MyBatis-Plus 透明加解密（AES-256-GCM），库里存的是 {@code AES256-GCM:<Base64>} 密文，
 *       业务代码全程只碰明文 getter/setter。</li>
 *   <li>{@code mobileIdx} / {@code idCardIdx} 是 {@code @BlindIndex} 盲索引列：插件的
 *       {@code BlindIndexInnerInterceptor} 在 insert/update 前自动从源字段算出 HMAC 写入，
 *       业务方不写同步逻辑。列表查询按明文精确匹配时，Controller 用
 *       {@code CryptoTemplate.blindIndex(明文)} 拼等值条件命中该列（密文列本身无法被查询）。</li>
 * </ul>
 * 三处配置缺一不可：
 * <ul>
 *   <li>{@code @TableName(autoResultMap = true)} —— 没有它 SELECT 不套用 typeHandler，
 *       查出来是密文原样且不报错（见插件 README「两条必须知道的边界」第 1 条）</li>
 *   <li>加密字段的 {@code updateStrategy = FieldStrategy.NOT_NULL} —— partial update 未带
 *       该字段时不把密文列显式写成 NULL</li>
 *   <li>盲索引字段的 {@code updateStrategy = FieldStrategy.NOT_NULL} —— 同理，源字段这次没带上时
 *       拦截器会跳过不写，必须靠这条策略把它排除出 UPDATE 的 SET 子句（见 {@link BlindIndex} javadoc）</li>
 * </ul>
 */
@TableName(value = "biz_nurse", autoResultMap = true)
public class NurseEntity extends BaseEntity {

    /** 护理员姓名 */
    private String nurseName;

    /** 手机号码（AES-256-GCM 加密入库） */
    @TableField(typeHandler = AesEncryptedStringTypeHandler.class, updateStrategy = FieldStrategy.NOT_NULL)
    private String mobile;

    /** 手机号码盲索引（HMAC，供按明文精确搜索；由拦截器自动填充，业务代码不要赋值） */
    @BlindIndex(source = "mobile")
    @TableField(value = "mobile_idx", updateStrategy = FieldStrategy.NOT_NULL)
    private String mobileIdx;

    /** 身份证号码（AES-256-GCM 加密入库） */
    @TableField(typeHandler = AesEncryptedStringTypeHandler.class, updateStrategy = FieldStrategy.NOT_NULL)
    private String idCard;

    /** 身份证号码盲索引（HMAC，供按明文精确搜索；由拦截器自动填充，业务代码不要赋值） */
    @BlindIndex(source = "idCard")
    @TableField(value = "id_card_idx", updateStrategy = FieldStrategy.NOT_NULL)
    private String idCardIdx;

    /** 护理员照片 */
    private String photo;

    /** 出生年月 */
    private LocalDate birthDate;

    /** 从业类型 */
    private String employmentType;

    public String getNurseName() {
        return nurseName;
    }

    public void setNurseName(String nurseName) {
        this.nurseName = nurseName;
    }

    public String getMobile() {
        return mobile;
    }

    public void setMobile(String mobile) {
        this.mobile = mobile;
    }

    /** 盲索引是内部机制，不下发给前端（虽是不可逆的 keyed hash，也没有暴露的必要）。 */
    @JsonIgnore
    public String getMobileIdx() {
        return mobileIdx;
    }

    public String getIdCard() {
        return idCard;
    }

    public void setIdCard(String idCard) {
        this.idCard = idCard;
    }

    @JsonIgnore
    public String getIdCardIdx() {
        return idCardIdx;
    }

    public String getPhoto() {
        return photo;
    }

    public void setPhoto(String photo) {
        this.photo = photo;
    }

    public LocalDate getBirthDate() {
        return birthDate;
    }

    public void setBirthDate(LocalDate birthDate) {
        this.birthDate = birthDate;
    }

    public String getEmploymentType() {
        return employmentType;
    }

    public void setEmploymentType(String employmentType) {
        this.employmentType = employmentType;
    }
}
