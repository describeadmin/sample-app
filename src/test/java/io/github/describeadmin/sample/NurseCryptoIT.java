package io.github.describeadmin.sample;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import io.github.describeadmin.crypto.api.CryptoTemplate;
import io.github.describeadmin.sample.nurse.entity.NurseEntity;
import io.github.describeadmin.sample.nurse.service.NurseService;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 护理员模块：手机号 / 身份证号加密入库 + 盲索引精确搜索验证（framework-crypto-starter）。
 *
 * <p>手法与 {@code EmployeeCryptoIT}、插件自己的 {@code CryptoEndToEndIntegrationTest} 一致——
 * <b>绕开 ORM 直接查裸列</b>确认库里存的是密文，再走 {@code Service} 确认读出来是明文；
 * 盲索引部分额外验证「按完整明文能精确反查到行」。只断言具体值，不看数量。
 */
@DisplayName("护理员：敏感字段加密入库 + 盲索引搜索")
class NurseCryptoIT extends AbstractMySqlIntegrationTest {

    private static final String MOBILE = "13800001234";
    private static final String ID_CARD = "110101199003072316";

    @Autowired NurseService nurseService;
    @Autowired CryptoTemplate cryptoTemplate;
    @Autowired JdbcTemplate jdbc;

    @Test
    @DisplayName("insert 后 mobile/id_card 裸列是密文、*_idx 裸列是盲索引；getById 读出来是明文")
    void encryptedAtRestAndBlindIndexFilled() {
        NurseEntity n = new NurseEntity();
        n.setNurseName("南丁格尔");
        n.setMobile(MOBILE);
        n.setIdCard(ID_CARD);
        n.setEmploymentType("医疗护理员");
        nurseService.save(n);
        Long id = n.getId();
        assertThat(id).isNotNull();

        // 1) 密文列：带算法前缀，且绝不等于明文
        String rawMobile = jdbc.queryForObject(
                "SELECT mobile FROM biz_nurse WHERE id = ?", String.class, id);
        String rawIdCard = jdbc.queryForObject(
                "SELECT id_card FROM biz_nurse WHERE id = ?", String.class, id);
        assertThat(rawMobile).startsWith("AES256-GCM:").isNotEqualTo(MOBILE);
        assertThat(rawIdCard).startsWith("AES256-GCM:").isNotEqualTo(ID_CARD);

        // 2) 盲索引列：由 BlindIndexInnerInterceptor 自动填充，等于 CryptoTemplate 算出来的值
        String rawMobileIdx = jdbc.queryForObject(
                "SELECT mobile_idx FROM biz_nurse WHERE id = ?", String.class, id);
        String rawIdCardIdx = jdbc.queryForObject(
                "SELECT id_card_idx FROM biz_nurse WHERE id = ?", String.class, id);
        assertThat(rawMobileIdx).isEqualTo(cryptoTemplate.blindIndex(MOBILE)).hasSize(64);
        assertThat(rawIdCardIdx).isEqualTo(cryptoTemplate.blindIndex(ID_CARD)).hasSize(64);

        // 3) 未加密字段作为对照：裸列就是明文
        String rawName = jdbc.queryForObject(
                "SELECT nurse_name FROM biz_nurse WHERE id = ?", String.class, id);
        assertThat(rawName).isEqualTo("南丁格尔");

        // 4) 走 Service 读：autoResultMap = true 让 TypeHandler 在 SELECT 生效，拿到明文
        NurseEntity loaded = nurseService.getById(id);
        assertThat(loaded.getMobile()).isEqualTo(MOBILE);
        assertThat(loaded.getIdCard()).isEqualTo(ID_CARD);
        assertThat(loaded.getNurseName()).isEqualTo("南丁格尔");
    }

    @Test
    @DisplayName("按完整手机号/身份证号用 blindIndex 拼等值条件，能精确命中且不误伤其他行")
    void blindIndexExactSearchFindsRow() {
        // 用本方法专属的明文，避免与其他测试方法写入的行串味（共享容器，方法间不回滚）
        String searchMobile = "13712345678";
        String searchIdCard = "500101199912310000";

        NurseEntity target = new NurseEntity();
        target.setNurseName("目标护理员");
        target.setMobile(searchMobile);
        target.setIdCard(searchIdCard);
        nurseService.save(target);

        NurseEntity other = new NurseEntity();
        other.setNurseName("其他护理员");
        other.setMobile("13900005678");
        other.setIdCard("310101198512120000");
        nurseService.save(other);

        List<NurseEntity> byMobile = nurseService.list(
                new QueryWrapper<NurseEntity>().eq("mobile_idx", cryptoTemplate.blindIndex(searchMobile)));
        assertThat(byMobile)
                .isNotEmpty()
                .allSatisfy(r -> assertThat(r.getMobile()).isEqualTo(searchMobile))
                .extracting(NurseEntity::getId)
                .contains(target.getId())
                .doesNotContain(other.getId());

        List<NurseEntity> byIdCard = nurseService.list(
                new QueryWrapper<NurseEntity>().eq("id_card_idx", cryptoTemplate.blindIndex(searchIdCard)));
        assertThat(byIdCard)
                .isNotEmpty()
                .allSatisfy(r -> assertThat(r.getIdCard()).isEqualTo(searchIdCard))
                .extracting(NurseEntity::getId)
                .contains(target.getId())
                .doesNotContain(other.getId());
    }

    @Test
    @DisplayName("partial update 只改 photo，不带 mobile/idCard 时密文列与盲索引列都保持不变")
    void partialUpdateKeepsCiphertextAndBlindIndex() {
        NurseEntity n = new NurseEntity();
        n.setNurseName("待更新护理员");
        n.setMobile(MOBILE);
        n.setIdCard(ID_CARD);
        n.setPhoto("old.jpg");
        nurseService.save(n);
        Long id = n.getId();

        String mobileBefore = jdbc.queryForObject(
                "SELECT mobile FROM biz_nurse WHERE id = ?", String.class, id);
        String mobileIdxBefore = jdbc.queryForObject(
                "SELECT mobile_idx FROM biz_nurse WHERE id = ?", String.class, id);

        NurseEntity patch = new NurseEntity();
        patch.setId(id);
        patch.setPhoto("new.jpg");
        nurseService.updateById(patch);

        assertThat(jdbc.queryForObject("SELECT photo FROM biz_nurse WHERE id = ?", String.class, id))
                .isEqualTo("new.jpg");
        assertThat(jdbc.queryForObject("SELECT mobile FROM biz_nurse WHERE id = ?", String.class, id))
                .as("updateStrategy = NOT_NULL：本次没带 mobile，密文列不应被写成 NULL")
                .isEqualTo(mobileBefore);
        assertThat(jdbc.queryForObject("SELECT mobile_idx FROM biz_nurse WHERE id = ?", String.class, id))
                .as("盲索引列同样不应被 partial update 抹掉")
                .isEqualTo(mobileIdxBefore);

        NurseEntity reloaded = nurseService.getById(id);
        assertThat(reloaded.getMobile()).isEqualTo(MOBILE);
        assertThat(reloaded.getIdCard()).isEqualTo(ID_CARD);
    }
}
