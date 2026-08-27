package io.github.describeadmin.sample;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

import java.nio.charset.StandardCharsets;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 强制首次改密（Part 2）的 HTTP 端到端验证。
 *
 * <p>管理员建号 / 重置密码后，对方 {@code sys_user.pwd_reset_required = 1}；登录能成功，
 * 但除「改密 / me / 登出」外的任何请求都被 {@code PasswordResetRequiredFilter} 挡成
 * {@code 403 + code 40105}。改密成功后标记清零、旧令牌失效。
 *
 * <p>本 IT 刻意<b>不</b>调用 {@code clearPwdResetRequired(...)}——它测的正是那个标记。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@DisplayName("强制首次改密（HTTP 端到端）")
class PasswordResetRequiredIT extends AbstractMySqlIntegrationTest {

    private static final String INITIAL_PWD = "Init-Pwd-2026!";
    private static final String NEW_PWD = "Fresh-Pwd-2026!";

    @Autowired
    TestRestTemplate rest;

    @Test
    @DisplayName("建号 → 登录被标记 → 业务接口 40105 → 改密 → 旧令牌失效 → 新口令登录后放行")
    void forcedChangeFullCycle() {
        String adminToken = tokenOf("admin", devSeedAdminPassword());
        String username = "pwd-reset-cycle";
        createUser(adminToken, username, INITIAL_PWD);

        // 登录成功，但响应里带 pwdResetRequired = true
        ResponseEntity<Map> login = rest.postForEntity("/api/auth/login",
                json(Map.of("type", "password", "username", username, "password", INITIAL_PWD)), Map.class);
        assertThat(login.getStatusCode()).isEqualTo(HttpStatus.OK);
        Map<?, ?> data = (Map<?, ?>) login.getBody().get("data");
        Map<?, ?> user = (Map<?, ?>) data.get("user");
        assertThat(user.get("pwdResetRequired")).as("登录响应应告诉前端要强制改密").isEqualTo(true);
        String token = String.valueOf(data.get("token"));

        // 业务接口被门禁挡下：403 + 40105
        ResponseEntity<String> blocked = rest.exchange("/api/system/user", HttpMethod.GET,
                new HttpEntity<>(bearer(token)), String.class);
        assertThat(blocked.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(blocked.getBody()).contains("40105");

        // /api/auth/me 在白名单里，放行
        assertThat(rest.exchange("/api/auth/me", HttpMethod.GET,
                new HttpEntity<>(bearer(token)), String.class).getStatusCode())
                .isEqualTo(HttpStatus.OK);

        // 改密：旧口令 + 合规新口令
        ResponseEntity<String> changed = rest.exchange("/api/auth/password", HttpMethod.PUT,
                new HttpEntity<>(Map.of("oldPassword", INITIAL_PWD, "newPassword", NEW_PWD), bearer(token)),
                String.class);
        assertThat(changed.getStatusCode()).isEqualTo(HttpStatus.OK);

        // 改密会吊销全部令牌，旧 token 失效
        assertThat(rest.exchange("/api/auth/me", HttpMethod.GET,
                new HttpEntity<>(bearer(token)), String.class).getStatusCode())
                .isEqualTo(HttpStatus.UNAUTHORIZED);

        // 用新口令登录：不再被标记
        ResponseEntity<Map> relogin = rest.postForEntity("/api/auth/login",
                json(Map.of("type", "password", "username", username, "password", NEW_PWD)), Map.class);
        assertThat(relogin.getStatusCode()).isEqualTo(HttpStatus.OK);
        Map<?, ?> reData = (Map<?, ?>) relogin.getBody().get("data");
        assertThat(((Map<?, ?>) reData.get("user")).get("pwdResetRequired")).isEqualTo(false);

        // 门禁不再拦：无 system:user:list 权限点，得到的是普通的 40300 而不是 40105
        ResponseEntity<String> afterChange = rest.exchange("/api/system/user", HttpMethod.GET,
                new HttpEntity<>(bearer(String.valueOf(reData.get("token")))), String.class);
        assertThat(afterChange.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(afterChange.getBody()).contains("40300").doesNotContain("40105");
    }

    @Test
    @DisplayName("管理员重置密码后，对方下次登录再次被要求强制改密")
    void adminResetReArmsTheFlag() {
        String adminToken = tokenOf("admin", devSeedAdminPassword());
        String username = "pwd-reset-readmin";
        createUser(adminToken, username, INITIAL_PWD);

        // 先自助改密解除一次标记
        String token = tokenOf(username, INITIAL_PWD);
        rest.exchange("/api/auth/password", HttpMethod.PUT,
                new HttpEntity<>(Map.of("oldPassword", INITIAL_PWD, "newPassword", NEW_PWD), bearer(token)),
                String.class);
        Map<?, ?> clean = loginData(username, NEW_PWD);
        assertThat(((Map<?, ?>) clean.get("user")).get("pwdResetRequired")).isEqualTo(false);

        // 管理员重置密码
        Long userId = Long.valueOf(String.valueOf(((Map<?, ?>) clean.get("user")).get("userId")));
        ResponseEntity<String> reset = rest.exchange("/api/system/user/" + userId + "/password",
                HttpMethod.PUT,
                new HttpEntity<>(Map.of("password", "Reset-Pwd-2026!"), bearer(adminToken)), String.class);
        assertThat(reset.getStatusCode()).isEqualTo(HttpStatus.OK);

        // 对方再次被标记
        Map<?, ?> afterReset = loginData(username, "Reset-Pwd-2026!");
        assertThat(((Map<?, ?>) afterReset.get("user")).get("pwdResetRequired"))
                .as("管理员重置后应再次强制改密").isEqualTo(true);
    }

    // ---------------------------------------------------------------- 工具

    private void createUser(String adminToken, String username, String password) {
        ResponseEntity<Map> created = rest.exchange("/api/system/user/with-password", HttpMethod.POST,
                new HttpEntity<>(Map.of(
                        "username", username,
                        "password", password,
                        "nickname", "强制改密测试账号",
                        "status", 1), bearer(adminToken)),
                Map.class);
        assertThat(created.getStatusCode()).as("前置条件：建号应成功").isEqualTo(HttpStatus.OK);
    }

    private Map<?, ?> loginData(String username, String password) {
        ResponseEntity<Map> resp = rest.postForEntity("/api/auth/login",
                json(Map.of("type", "password", "username", username, "password", password)), Map.class);
        assertThat(resp.getStatusCode()).as("登录应成功: " + username).isEqualTo(HttpStatus.OK);
        return (Map<?, ?>) resp.getBody().get("data");
    }

    private String tokenOf(String username, String password) {
        return String.valueOf(loginData(username, password).get("token"));
    }

    private static HttpEntity<Map<String, Object>> json(Map<String, Object> body) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(new MediaType(MediaType.APPLICATION_JSON, StandardCharsets.UTF_8));
        return new HttpEntity<>(body, headers);
    }

    private static HttpHeaders bearer(String token) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(new MediaType(MediaType.APPLICATION_JSON, StandardCharsets.UTF_8));
        headers.setBearerAuth(token);
        return headers;
    }
}
