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
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 登录失败次数限制的 HTTP 端到端验证。
 *
 * <p><b>每个用例都用独立的用户名</b>：锁定状态存活在共享的应用上下文里，
 * 复用同一个账号会让用例之间互相影响，而这种失败往往表现为"单独跑能过、一起跑就挂"，
 * 是最难查的一类测试问题。同样的理由，这里绝不去锁 {@code admin}——
 * 它是其余所有测试的前置条件。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@DisplayName("登录失败锁定（HTTP 端到端）")
class LoginLockoutIT extends AbstractMySqlIntegrationTest {

    /** 与 describeadmin.security.lockout.max-failures 的默认值一致。 */
    private static final int MAX_FAILURES = 5;

    @Autowired TestRestTemplate rest;

    @Test
    @DisplayName("连续失败达到阈值后，即使密码正确也被拒")
    void correctPasswordRejectedWhileLocked() {
        String username = "lockout-victim";
        createUser(username, "right-password-1");

        // 阈值之内仍是普通的认证失败
        for (int i = 0; i < MAX_FAILURES; i++) {
            ResponseEntity<String> resp = login(username, "wrong-password");
            assertThat(resp.getBody())
                    .as("第 " + (i + 1) + " 次失败不应提前触发锁定")
                    .contains("用户名或密码错误");
        }

        ResponseEntity<String> locked = login(username, "right-password-1");

        // 这一条才是本组测试的意义：密码是对的，仍然进不去
        assertThat(locked.getBody())
                .as("达到阈值后必须拒绝，哪怕密码正确")
                .contains("锁定")
                .doesNotContain("用户名或密码错误");
    }

    @Test
    @DisplayName("锁定只针对当事账号，不波及其他账号")
    void lockoutIsScopedToOneAccount() {
        String victim = "lockout-scoped";
        String bystander = "lockout-bystander";
        createUser(victim, "pwd-12345");
        createUser(bystander, "pwd-12345");

        for (int i = 0; i < MAX_FAILURES; i++) {
            login(victim, "wrong-password");
        }

        assertThat(login(victim, "pwd-12345").getBody()).contains("锁定");
        assertThat(login(bystander, "pwd-12345").getStatusCode())
                .as("旁观账号不应受影响")
                .isEqualTo(HttpStatus.OK);
    }

    @Test
    @DisplayName("不存在的用户名同样会被锁定")
    void unknownUsernameIsLockedToo() {
        String username = "lockout-ghost-account";

        for (int i = 0; i < MAX_FAILURES; i++) {
            login(username, "whatever");
        }

        // 若只对存在的账号锁定，"这个用户名会不会被锁"本身就是一条账号枚举信道，
        // 登录链路里用 DUMMY_HASH 抹平响应耗时的努力也就白费了
        assertThat(login(username, "whatever").getBody())
                .as("锁定行为不能泄露账号是否存在")
                .contains("锁定");
    }

    @Test
    @DisplayName("中途登录成功会清零计数")
    void successfulLoginResetsCounter() {
        String username = "lockout-reset";
        createUser(username, "pwd-12345");

        for (int i = 0; i < MAX_FAILURES - 1; i++) {
            login(username, "wrong-password");
        }
        assertThat(login(username, "pwd-12345").getStatusCode())
                .as("尚未达阈值，应能登录").isEqualTo(HttpStatus.OK);

        // 计数已清零，于是又可以承受完整的一轮失败而不被锁
        for (int i = 0; i < MAX_FAILURES - 1; i++) {
            login(username, "wrong-password");
        }
        assertThat(login(username, "pwd-12345").getStatusCode())
                .as("成功登录应清零失败计数")
                .isEqualTo(HttpStatus.OK);
    }

    // ------------------------------------------------------------------ 管理侧可观测性（D 项）

    @Test
    @DisplayName("锁定后出现在 locked-accounts 列表里，手动解锁后立即可以重新登录")
    void adminCanListAndUnlockLockedAccount() {
        String username = "lockout-admin-unlock";
        createUser(username, "right-password-1");
        for (int i = 0; i < MAX_FAILURES; i++) {
            login(username, "wrong-password");
        }
        // 达到阈值后密码正确也进不去，见 correctPasswordRejectedWhileLocked
        assertThat(login(username, "right-password-1").getBody()).contains("锁定");

        ResponseEntity<Map> listed = rest.exchange("/api/system/security/locked-accounts",
                HttpMethod.GET, new HttpEntity<>(bearer(tokenOfAdmin())), Map.class);
        assertThat(listed.getStatusCode()).isEqualTo(HttpStatus.OK);
        @SuppressWarnings("unchecked")
        List<String> locked = (List<String>) listed.getBody().get("data");
        assertThat(locked).contains(username);

        ResponseEntity<Map> unlocked = rest.exchange("/api/system/security/locked-accounts/" + username,
                HttpMethod.DELETE, new HttpEntity<>(bearer(tokenOfAdmin())), Map.class);
        assertThat(unlocked.getStatusCode()).isEqualTo(HttpStatus.OK);

        // 手动解锁后立即可以用正确密码登录，不用等 15 分钟窗口
        assertThat(login(username, "right-password-1").getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    @DisplayName("没有 system:security:list 权限点的普通账号被拒绝（403），而不是意外放行")
    void lockedAccountsEndpointRequiresPermission() {
        String username = "lockout-no-perm";
        createUser(username, "pwd-perm-1"); // createUser 不分配任何角色，因此没有任何权限点

        ResponseEntity<String> resp = rest.exchange("/api/system/security/locked-accounts",
                HttpMethod.GET, new HttpEntity<>(bearer(tokenOf(username, "pwd-perm-1"))), String.class);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    // ------------------------------------------------------------------ 工具

    private ResponseEntity<String> login(String username, String password) {
        return rest.postForEntity("/api/auth/login",
                json(Map.of("type", "password", "username", username, "password", password)),
                String.class);
    }

    private void createUser(String username, String password) {
        ResponseEntity<Map> created = rest.exchange("/api/system/user/with-password",
                HttpMethod.POST,
                new HttpEntity<>(Map.of(
                        "username", username,
                        "password", password,
                        "nickname", "锁定测试账号",
                        "status", 1), bearer(tokenOfAdmin())),
                Map.class);
        assertThat(created.getStatusCode()).as("前置条件：建号应成功").isEqualTo(HttpStatus.OK);
    }

    private String tokenOfAdmin() {
        return tokenOf("admin", "admin123");
    }

    private String tokenOf(String username, String password) {
        ResponseEntity<Map> resp = rest.postForEntity("/api/auth/login",
                json(Map.of("type", "password", "username", username, "password", password)),
                Map.class);
        assertThat(resp.getStatusCode()).as("登录应成功，检查种子数据").isEqualTo(HttpStatus.OK);
        return String.valueOf(((Map<?, ?>) resp.getBody().get("data")).get("token"));
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
