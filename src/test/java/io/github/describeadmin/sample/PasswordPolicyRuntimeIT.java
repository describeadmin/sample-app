package io.github.describeadmin.sample;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import io.github.describeadmin.security.api.AuthUserLoader;
import io.github.describeadmin.system.entity.SysConfig;
import io.github.describeadmin.system.entity.SysUser;
import io.github.describeadmin.system.service.SysConfigService;
import io.github.describeadmin.system.service.SysUserService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 密码「定期强制过期」与「历史不可重用」（Part 3）——两项都由 {@code sys_config} 参数驱动，
 * 默认 0＝关。本 IT 打开参数验证行为，并在每个用例后复位，避免污染共享容器里的其他测试。
 */
@SpringBootTest
@DisplayName("密码策略：定期过期 + 历史不可重用")
class PasswordPolicyRuntimeIT extends AbstractMySqlIntegrationTest {

    private static final String CFG_MAX_AGE = "sys.password.max-age-days";
    private static final String CFG_HISTORY = "sys.password.history-count";

    @Autowired
    SysUserService userService;
    @Autowired
    SysConfigService configService;
    @Autowired
    AuthUserLoader authUserLoader;
    @Autowired
    JdbcTemplate jdbc;

    @AfterEach
    void resetParams() {
        setParam(CFG_MAX_AGE, "0");
        setParam(CFG_HISTORY, "0");
    }

    @Test
    @DisplayName("history-count > 0 时，新密码不得命中最近 N 条历史；设回 0 后开关确实关掉")
    void historyReuseRejectedWhenEnabled() {
        setParam(CFG_HISTORY, "2");
        Long id = newUser("pwd-history-user", "Hist-Pwd-A1!");

        userService.changeOwnPassword(id, "Hist-Pwd-A1!", "Hist-Pwd-B2!");
        userService.changeOwnPassword(id, "Hist-Pwd-B2!", "Hist-Pwd-C3!");

        assertThatThrownBy(() -> userService.changeOwnPassword(id, "Hist-Pwd-C3!", "Hist-Pwd-B2!"))
                .as("B2 在最近 2 条历史里").hasMessageContaining("最近");
        assertThatNoException().as("没用过的口令放行")
                .isThrownBy(() -> userService.changeOwnPassword(id, "Hist-Pwd-C3!", "Hist-Pwd-D4!"));

        setParam(CFG_HISTORY, "0");
        assertThatNoException().as("关掉后不再查历史")
                .isThrownBy(() -> userService.changeOwnPassword(id, "Hist-Pwd-D4!", "Hist-Pwd-B2!"));
    }

    @Test
    @DisplayName("max-age-days > 0 且密码过龄 → 认证结果被标记强制改密；设回 0 后不再标记")
    void expiredPasswordForcesReset() {
        Long id = newUser("pwd-age-user", "Age-Pwd-2026!");
        // 隔离出「过龄」这一条因素：清掉建号自带的首次强制改密标记
        jdbc.update("UPDATE sys_user SET pwd_reset_required = 0 WHERE id = ?", id);

        assertThat(authFlag("pwd-age-user")).as("参数关闭：不过期").isFalse();

        setParam(CFG_MAX_AGE, "1");
        jdbc.update("UPDATE sys_user SET pwd_update_time = DATE_SUB(NOW(), INTERVAL 2 DAY) WHERE id = ?", id);
        assertThat(authFlag("pwd-age-user")).as("过龄：应被标记强制改密").isTrue();

        setParam(CFG_MAX_AGE, "0");
        assertThat(authFlag("pwd-age-user")).as("关掉有效期：不再标记").isFalse();
    }

    // ---------------------------------------------------------------- 工具

    private Long newUser(String username, String rawPassword) {
        SysUser u = new SysUser();
        u.setUsername(username);
        return userService.createUser(u, rawPassword, List.of()).getId();
    }

    /** 走真实认证加载链路（DbAuthUserLoader，会算上「过龄」），返回 pwdResetRequired。 */
    private boolean authFlag(String username) {
        return authUserLoader.loadByUsername(username).orElseThrow().isPwdResetRequired();
    }

    private void setParam(String key, String value) {
        SysConfig row = configService.getOne(new QueryWrapper<SysConfig>().eq("config_key", key), false);
        row.setConfigValue(value);
        configService.updateById(row);
    }
}
