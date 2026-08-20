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
 * 在线用户与强制下线的 HTTP 端到端验证。
 *
 * <p>这组能力的价值全在"立即生效"上——如果踢下线之后原令牌还能用，
 * 那它就只是一个让管理员误以为处理过了的按钮，比没有更糟。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@DisplayName("在线用户（HTTP 端到端）")
class OnlineSessionIT extends AbstractMySqlIntegrationTest {

    @Autowired TestRestTemplate rest;

    @Test
    @DisplayName("当前登录会话出现在在线列表里，中文昵称不乱码")
    void currentSessionIsListed() {
        String token = tokenOfAdmin();

        ResponseEntity<String> resp = rest.exchange("/api/system/online", HttpMethod.GET,
                new HttpEntity<>(bearer(token)), String.class);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(resp.getBody()).contains("\"username\":\"admin\"");
        // 值断言，顺带守住字符集（CLAUDE.md 3.6）
        assertThat(resp.getBody()).contains("超级管理员");
    }

    @Test
    @DisplayName("在线列表里不含令牌本身")
    void tokenIsNeverExposed() {
        String token = tokenOfAdmin();

        ResponseEntity<String> resp = rest.exchange("/api/system/online", HttpMethod.GET,
                new HttpEntity<>(bearer(token)), String.class);

        // 令牌一旦出现在这个响应里，任何能打开在线用户页的人都可以拿它冒充当事人
        assertThat(resp.getBody())
                .as("在线用户列表绝不能带出令牌")
                .doesNotContain(token);
        assertThat(resp.getBody()).doesNotContain("\"token\"");
    }

    @Test
    @DisplayName("强制下线后，被踢用户的令牌立即失效")
    void forceLogoutInvalidatesTokenImmediately() {
        // 造一个独立账号，避免把执行测试的 admin 自己踢掉影响其他用例
        String victimToken = tokenOfNewUser("online-victim");
        Long victimId = userIdOf(victimToken);

        // 踢之前是能用的——否则下面的断言可能只是因为它本来就不能用
        assertThat(meStatus(victimToken)).isEqualTo(HttpStatus.OK);

        ResponseEntity<Map> kicked = rest.exchange("/api/system/online/" + victimId,
                HttpMethod.DELETE, new HttpEntity<>(bearer(tokenOfAdmin())), Map.class);
        assertThat(kicked.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(kicked.getBody().get("data")).as("应报告实际吊销的令牌数").isEqualTo(1);

        assertThat(meStatus(victimToken))
                .as("踢下线必须立即生效，而不是等令牌自然过期")
                .isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    @DisplayName("踢一个不在线的用户返回 0，而不是报错")
    void forceLogoutOfOfflineUserIsNoop() {
        ResponseEntity<Map> resp = rest.exchange("/api/system/online/999999",
                HttpMethod.DELETE, new HttpEntity<>(bearer(tokenOfAdmin())), Map.class);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(resp.getBody().get("data")).isEqualTo(0);
    }

    @Test
    @DisplayName("无权限账号既看不到在线列表，也踢不了人")
    void requiresPermission() {
        String powerless = tokenOfNewUser("online-nobody");

        assertThat(rest.exchange("/api/system/online", HttpMethod.GET,
                new HttpEntity<>(bearer(powerless)), String.class).getStatusCode())
                .isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(rest.exchange("/api/system/online/1", HttpMethod.DELETE,
                new HttpEntity<>(bearer(powerless)), String.class).getStatusCode())
                .as("读被拦住而写没被拦住是这类页面最常见的疏漏")
                .isEqualTo(HttpStatus.FORBIDDEN);
    }

    // ------------------------------------------------------------------ 工具

    private HttpStatus meStatus(String token) {
        return (HttpStatus) rest.exchange("/api/auth/me", HttpMethod.GET,
                new HttpEntity<>(bearer(token)), String.class).getStatusCode();
    }

    @SuppressWarnings("unchecked")
    private Long userIdOf(String token) {
        ResponseEntity<Map> me = rest.exchange("/api/auth/me", HttpMethod.GET,
                new HttpEntity<>(bearer(token)), Map.class);
        Map<String, Object> data = (Map<String, Object>) me.getBody().get("data");
        return Long.valueOf(String.valueOf(data.get("userId")));
    }

    /** 建一个无任何角色的账号并登录，返回其令牌。 */
    private String tokenOfNewUser(String username) {
        ResponseEntity<Map> created = rest.exchange("/api/system/user/with-password",
                HttpMethod.POST,
                new HttpEntity<>(Map.of(
                        "username", username,
                        "password", "pwd-12345",
                        "nickname", "在线测试账号",
                        "status", 1), bearer(tokenOfAdmin())),
                Map.class);
        assertThat(created.getStatusCode()).as("前置条件：建号应成功").isEqualTo(HttpStatus.OK);

        ResponseEntity<Map> login = rest.postForEntity("/api/auth/login",
                json(Map.of("type", "password", "username", username, "password", "pwd-12345")),
                Map.class);
        assertThat(login.getStatusCode()).as("前置条件：新账号应能登录").isEqualTo(HttpStatus.OK);
        return String.valueOf(((Map<?, ?>) login.getBody().get("data")).get("token"));
    }

    private String tokenOfAdmin() {
        ResponseEntity<Map> resp = rest.postForEntity("/api/auth/login",
                json(Map.of("type", "password", "username", "admin", "password", "admin123")),
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
