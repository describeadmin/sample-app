package io.github.describeadmin.sample;

import io.github.describeadmin.sample.employee.entity.EmployeeEntity;
import io.github.describeadmin.sample.employee.service.EmployeeService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 员工模块：身份证号 / 手机号加密入库验证（framework-crypto-starter）。
 *
 * <p>核心手法与 CLAUDE.md 3.6、插件自己的 {@code CryptoEndToEndIntegrationTest} 一致——
 * <b>绕开 ORM 直接查裸列</b>确认库里存的是密文，再走 {@code Service} 确认读出来是明文。
 * 只断言具体值，不看数量。
 */
@DisplayName("员工：敏感字段加密入库")
class EmployeeCryptoIT extends AbstractMySqlIntegrationTest {

    private static final String ID_CARD = "110101199003072316";
    private static final String MOBILE = "13800001234";

    @Autowired EmployeeService employeeService;
    @Autowired JdbcTemplate jdbc;

    @Test
    @DisplayName("insert 后裸列是 AES256-GCM 密文，getById 读出来是明文；未加密字段维持明文")
    void encryptedAtRestButPlaintextThroughService() {
        EmployeeEntity e = new EmployeeEntity();
        e.setEmployeeName("张三");
        e.setGender("男");
        e.setIdCard(ID_CARD);
        e.setMobile(MOBILE);
        e.setAddress("北京市海淀区中关村大街1号");
        e.setRemark("加密入库验证");
        employeeService.save(e);
        Long id = e.getId();
        assertThat(id).isNotNull();

        // 1) 绕开 MyBatis 直接读裸列：必须是密文，且绝不能等于明文
        String rawIdCard = jdbc.queryForObject(
                "SELECT id_card FROM biz_employee WHERE id = ?", String.class, id);
        String rawMobile = jdbc.queryForObject(
                "SELECT mobile FROM biz_employee WHERE id = ?", String.class, id);
        assertThat(rawIdCard).startsWith("AES256-GCM:").isNotEqualTo(ID_CARD);
        assertThat(rawMobile).startsWith("AES256-GCM:").isNotEqualTo(MOBILE);

        // 2) 未加密字段作为对照：裸列就是明文
        String rawName = jdbc.queryForObject(
                "SELECT employee_name FROM biz_employee WHERE id = ?", String.class, id);
        String rawAddress = jdbc.queryForObject(
                "SELECT address FROM biz_employee WHERE id = ?", String.class, id);
        assertThat(rawName).isEqualTo("张三");
        assertThat(rawAddress).isEqualTo("北京市海淀区中关村大街1号");

        // 3) 走 Service 读：autoResultMap = true 让 TypeHandler 在 SELECT 生效，拿到明文
        EmployeeEntity loaded = employeeService.getById(id);
        assertThat(loaded.getIdCard()).isEqualTo(ID_CARD);
        assertThat(loaded.getMobile()).isEqualTo(MOBILE);
        assertThat(loaded.getEmployeeName()).isEqualTo("张三");
    }

    @Test
    @DisplayName("partial update 只改地址，不带 idCard/mobile 时不会把已有密文列覆盖成 NULL")
    void partialUpdateKeepsExistingCiphertext() {
        EmployeeEntity e = new EmployeeEntity();
        e.setEmployeeName("李四");
        e.setIdCard(ID_CARD);
        e.setMobile(MOBILE);
        e.setAddress("旧地址");
        employeeService.save(e);
        Long id = e.getId();

        EmployeeEntity patch = new EmployeeEntity();
        patch.setId(id);
        patch.setAddress("新地址");
        employeeService.updateById(patch);

        EmployeeEntity reloaded = employeeService.getById(id);
        assertThat(reloaded.getAddress()).isEqualTo("新地址");
        assertThat(reloaded.getIdCard())
                .as("updateStrategy = NOT_NULL：本次没带 idCard，密文列不应被写成 NULL")
                .isEqualTo(ID_CARD);
        assertThat(reloaded.getMobile()).isEqualTo(MOBILE);
    }
}
