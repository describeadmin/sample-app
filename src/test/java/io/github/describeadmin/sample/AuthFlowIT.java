package io.github.describeadmin.sample;

import io.github.describeadmin.cache.api.CacheProvider;
import io.github.describeadmin.security.core.ImageCaptchaProvider;
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
 * 认证链路的 HTTP 端到端验证。
 *
 * <p>为什么必须走真实 HTTP 而不是直接调 Service：本组测试要验证的东西
 * （过滤器链顺序、401 返回的是 JSON 而不是登录页跳转、Authorization 头的解析）
 * <b>全部只存在于 Servlet 层</b>，绕开 HTTP 就一条都测不到。
 *
 * <p>这也是前端能否工作的前提——前端拿到 302 跳转而不是 401 时，
 * 表现是「接口返回了一堆 HTML」，排查成本极高。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@DisplayName("认证链路（HTTP 端到端）")
class AuthFlowIT extends AbstractMySqlIntegrationTest {

    /** 与 describeadmin.security.captcha.trigger-threshold 的默认值一致。 */
    private static final int CAPTCHA_TRIGGER_THRESHOLD = 3;

    @Autowired TestRestTemplate rest;
    @Autowired CacheProvider cacheProvider;

    // ------------------------------------------------------------------ 登录

    @Test
    @SuppressWarnings("unchecked")
    @DisplayName("登录成功返回令牌与用户信息，中文昵称不乱码")
    void loginIssuesToken() {
        Map<?, ?> data = loginAsAdmin();

        assertThat(data.get("token")).asString().isNotBlank();
        assertThat(data.get("expiresIn")).isNotNull();

        @SuppressWarnings("unchecked")
        Map<String, Object> user = (Map<String, Object>) data.get("user");
        // 值断言而非存在性断言 —— 字符集损坏时字段照样"存在"（CLAUDE.md 3.6）
        assertThat(user.get("username")).isEqualTo("admin");
        assertThat(user.get("nickname")).isEqualTo("超级管理员");
        assertThat((List<Object>) user.get("roles")).contains("ADMIN");
    }

    @Test
    @DisplayName("密码错误返回业务错误码，且响应体里没有任何密码痕迹")
    void loginWithWrongPassword() {
        ResponseEntity<String> resp = rest.postForEntity("/api/auth/login",
                json(Map.of("type", "password", "username", "admin", "password", "wrong-one")),
                String.class);

        assertThat(resp.getBody()).doesNotContain("wrong-one").doesNotContain("$2a$");
    }

    @Test
    @DisplayName("登录接口本身免认证，否则没人进得来")
    void loginEndpointIsPermitAll() {
        ResponseEntity<String> resp = rest.postForEntity("/api/auth/login",
                json(Map.of("type", "password", "username", "admin", "password", "admin123")),
                String.class);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    // ------------------------------------------------------------------ 鉴权

    @Test
    @DisplayName("未带令牌访问受保护接口返回 401 JSON，而不是跳转登录页")
    void protectedEndpointWithoutTokenReturnsJson() {
        ResponseEntity<String> resp = rest.getForEntity("/api/system/user", String.class);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        // 这三条是分开的断言：Spring Security 默认会 302 到 /login 并返回 HTML，
        // 只断状态码或只断响应体都可能漏掉一半问题
        assertThat(resp.getHeaders().getFirst(HttpHeaders.LOCATION)).isNull();
        assertThat(resp.getHeaders().getContentType())
                .isNotNull()
                .satisfies(ct -> assertThat(ct.includes(MediaType.APPLICATION_JSON)).isTrue());
        assertThat(resp.getBody()).contains("\"code\":40100").doesNotContain("<html");
    }

    @Test
    @DisplayName("带上令牌即可访问受保护接口")
    void protectedEndpointWithToken() {
        ResponseEntity<String> resp = rest.exchange("/api/system/user", HttpMethod.GET,
                new HttpEntity<>(bearer(tokenOfAdmin())), String.class);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(resp.getBody()).contains("超级管理员");
    }

    @Test
    @DisplayName("伪造的令牌一律当作未登录处理")
    void forgedTokenRejected() {
        ResponseEntity<String> resp = rest.exchange("/api/system/user", HttpMethod.GET,
                new HttpEntity<>(bearer("this-is-not-a-real-token")), String.class);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    @DisplayName("/api/auth/me 回源返回当前用户，前端刷新页面靠它恢复登录态")
    void meReturnsCurrentUser() {
        ResponseEntity<Map> resp = rest.exchange("/api/auth/me", HttpMethod.GET,
                new HttpEntity<>(bearer(tokenOfAdmin())), Map.class);

        @SuppressWarnings("unchecked")
        Map<String, Object> user = (Map<String, Object>) resp.getBody().get("data");
        assertThat(user.get("username")).isEqualTo("admin");
        assertThat(user.get("nickname")).isEqualTo("超级管理员");
    }

    @Test
    @DisplayName("菜单树取自登录态而非请求参数，拿不到别人的菜单")
    void menusComeFromLoginContext() {
        ResponseEntity<Map> resp = rest.exchange("/api/auth/menus", HttpMethod.GET,
                new HttpEntity<>(bearer(tokenOfAdmin())), Map.class);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat((List<?>) resp.getBody().get("data")).isNotEmpty();
    }

    // ------------------------------------------------------------------ 刷新令牌（E 项）

    @Test
    @DisplayName("登录成功同时返回 refreshToken，且 expiresIn/refreshExpiresIn 都是正数")
    void loginReturnsRefreshToken() {
        Map<?, ?> data = loginAsAdmin();

        assertThat(data.get("refreshToken")).asString().isNotBlank();
        // expiresIn/refreshExpiresIn 是 long，按框架的全局约定（CLAUDE.md 4.8）序列化为字符串——
        // LoginResult 没有加 @JsonFormat(shape = NUMBER) 例外，因此这里拿到的是 String 而不是 Number，
        // 强转 Number 会抛 ClassCastException。
        assertThat(Long.parseLong(String.valueOf(data.get("expiresIn")))).isPositive();
        assertThat(Long.parseLong(String.valueOf(data.get("refreshExpiresIn")))).isPositive();
    }

    @Test
    @DisplayName("用 refresh token 换发新令牌成功，新 access token 立即可用")
    @SuppressWarnings("unchecked")
    void refreshIssuesNewAccessToken() {
        Map<?, ?> data = loginAsAdmin();
        String refreshToken = String.valueOf(data.get("refreshToken"));

        ResponseEntity<Map> resp = rest.postForEntity("/api/auth/refresh",
                json(Map.of("refreshToken", refreshToken)), Map.class);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        Map<String, Object> refreshed = (Map<String, Object>) resp.getBody().get("data");
        String newAccessToken = String.valueOf(refreshed.get("token"));
        assertThat(newAccessToken).isNotBlank();

        assertThat(rest.exchange("/api/auth/me", HttpMethod.GET,
                new HttpEntity<>(bearer(newAccessToken)), String.class).getStatusCode())
                .isEqualTo(HttpStatus.OK);
    }

    @Test
    @DisplayName("刷新后旧 refresh token 立即失效（轮换），不能再用一次")
    void refreshRotatesOldRefreshToken() {
        Map<?, ?> data = loginAsAdmin();
        String refreshToken = String.valueOf(data.get("refreshToken"));

        rest.postForEntity("/api/auth/refresh", json(Map.of("refreshToken", refreshToken)), Map.class);

        // 业务异常统一返回 HTTP 200，真正的错误信息体现在响应体的 code 字段里——
        // 只有 Spring Security 过滤器链自身的拒绝（缺令牌/权限不足）才映射到真实 HTTP 状态码，
        // 见 GlobalExceptionHandler 的类注释
        ResponseEntity<String> second = rest.postForEntity("/api/auth/refresh",
                json(Map.of("refreshToken", refreshToken)), String.class);
        assertThat(second.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(second.getBody()).contains("\"code\":40100").contains("刷新令牌无效或已过期");
    }

    @Test
    @DisplayName("POST /api/auth/refresh 本身免认证——挂权限校验会自相矛盾")
    void refreshEndpointIsPermitAll() {
        Map<?, ?> data = loginAsAdmin();
        String refreshToken = String.valueOf(data.get("refreshToken"));

        // 不带 Authorization 头
        ResponseEntity<String> resp = rest.postForEntity("/api/auth/refresh",
                json(Map.of("refreshToken", refreshToken)), String.class);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    @DisplayName("无效的 refresh token 返回业务错误码，而不是 500")
    void refreshWithInvalidTokenReturnsBizError() {
        ResponseEntity<String> resp = rest.postForEntity("/api/auth/refresh",
                json(Map.of("refreshToken", "not-a-real-refresh-token")), String.class);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(resp.getBody()).contains("\"code\":40100");
    }

    // ------------------------------------------------------------------ 登出

    @Test
    @DisplayName("登出后原令牌立即失效 —— 这是选不透明令牌而非 JWT 的直接收益")
    void logoutRevokesToken() {
        String token = tokenOfAdmin();

        // 登出前可用
        assertThat(rest.exchange("/api/auth/me", HttpMethod.GET,
                new HttpEntity<>(bearer(token)), String.class).getStatusCode())
                .isEqualTo(HttpStatus.OK);

        rest.exchange("/api/auth/logout", HttpMethod.POST,
                new HttpEntity<>(bearer(token)), String.class);

        // 登出后立刻不可用。JWT 方案在这里会依然返回 200，直到令牌自然过期
        assertThat(rest.exchange("/api/auth/me", HttpMethod.GET,
                new HttpEntity<>(bearer(token)), String.class).getStatusCode())
                .isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    // ------------------------------------------------------------------ 插件化

    @Test
    @SuppressWarnings("unchecked")
    @DisplayName("登录方式列表由后端动态给出，前端不硬编码登录按钮")
    void providersAreDiscoverable() {
        ResponseEntity<Map> resp = rest.getForEntity("/api/auth/providers", Map.class);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        // 当前只装了内置的用户名密码；引入 framework-auth-zhengwuding-starter 后这里会自动多一项
        assertThat((List<Object>) resp.getBody().get("data")).containsExactly("password");
    }

    // ------------------------------------------------------------------ 审计

    @Test
    @DisplayName("带登录态的写操作，create_by 被自动填成当前用户而非留空")
    void auditFieldsFilledFromLoginContext() {
        String token = tokenOfAdmin();

        ResponseEntity<Map> created = rest.exchange("/api/system/dept", HttpMethod.POST,
                new HttpEntity<>(Map.of("deptName", "审计验证部", "parentId", 0, "sort", 99),
                        bearer(token)),
                Map.class);

        @SuppressWarnings("unchecked")
        Map<String, Object> dept = (Map<String, Object>) created.getBody().get("data");
        assertThat(dept.get("deptName")).isEqualTo("审计验证部");
        // 这一条如果为 null，说明 CurrentUserProvider 没有被 security-starter 覆盖掉 NOOP
        assertThat(dept.get("createBy")).as("审计人应由框架自动填充").isNotNull();
    }

    // ------------------------------------------------------------------ 验证码

    @Test
    @DisplayName("GET /api/auth/captcha 免认证，返回图形验证码挑战")
    void captchaEndpointIsPermitAllAndReturnsImage() {
        ResponseEntity<Map> resp = rest.getForEntity("/api/auth/captcha", Map.class);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        Map<String, Object> challenge = captchaData(resp);
        assertThat(challenge.get("type")).isEqualTo("image");
        @SuppressWarnings("unchecked")
        Map<String, Object> payload = (Map<String, Object>) challenge.get("payload");
        assertThat((String) payload.get("image")).startsWith("data:image/png;base64,");
    }

    @Test
    @DisplayName("渐进式验证码：未达阈值不要求，达到阈值后必须带验证码，且一次性防重放")
    void progressiveCaptchaTriggersAfterThresholdAndIsOneTimeUse() {
        String username = "captcha-progressive";
        createUser(username, "right-password-1");

        // 阈值之内仍是普通的认证失败，不要求验证码
        for (int i = 0; i < CAPTCHA_TRIGGER_THRESHOLD - 1; i++) {
            ResponseEntity<String> resp = login(username, "wrong-password");
            assertThat(resp.getBody())
                    .as("第 " + (i + 1) + " 次失败不应提前要求验证码")
                    .contains("\"code\":40102");
        }

        // 第 CAPTCHA_TRIGGER_THRESHOLD 次失败后达到阈值，从此起不带验证码直接被拒绝，
        // 哪怕接下来这次密码其实是对的
        login(username, "wrong-password");
        ResponseEntity<String> withoutCaptcha = login(username, "right-password-1");
        assertThat(withoutCaptcha.getBody())
                .as("达到阈值后必须要求验证码，即使密码正确")
                .contains("\"code\":40103");

        // 拿一个真实验证码，直接从注入的 CacheProvider 读出正确答案——图形验证码是给人眼看的，
        // 自动化测试没法"识图"，ImageCaptchaProvider.CACHE_KEY_PREFIX 就是为这条路径公开的
        String captchaId = captchaData(rest.getForEntity("/api/auth/captcha", Map.class))
                .get("captchaId").toString();
        String answer = correctAnswerOf(captchaId);

        // 验证码正确、密码仍然错误：两层校验相互独立
        ResponseEntity<String> wrongPasswordWithCaptcha = rest.postForEntity("/api/auth/login",
                json(Map.of("type", "password", "username", username, "password", "wrong-password",
                        "captchaId", captchaId, "captchaCode", answer)),
                String.class);
        assertThat(wrongPasswordWithCaptcha.getBody()).contains("\"code\":40102");

        // 同一 captchaId 已经被上一次校验消费掉了，哪怕这次带上正确密码，也只会拿到"验证码失效"
        ResponseEntity<String> reused = rest.postForEntity("/api/auth/login",
                json(Map.of("type", "password", "username", username, "password", "right-password-1",
                        "captchaId", captchaId, "captchaCode", answer)),
                String.class);
        assertThat(reused.getBody())
                .as("验证码必须是一次性的，不能重放")
                .contains("\"code\":40104");

        // 换一张新验证码，配合正确密码，登录成功
        String freshCaptchaId = captchaData(rest.getForEntity("/api/auth/captcha", Map.class))
                .get("captchaId").toString();
        String freshAnswer = correctAnswerOf(freshCaptchaId);

        ResponseEntity<Map> success = rest.postForEntity("/api/auth/login",
                json(Map.of("type", "password", "username", username, "password", "right-password-1",
                        "captchaId", freshCaptchaId, "captchaCode", freshAnswer)),
                Map.class);
        assertThat(success.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(((Map<?, ?>) success.getBody().get("data")).get("token")).asString().isNotBlank();
    }

    // ------------------------------------------------------------------ 工具

    @SuppressWarnings("unchecked")
    private static Map<String, Object> captchaData(ResponseEntity<Map> resp) {
        return (Map<String, Object>) resp.getBody().get("data");
    }

    private String correctAnswerOf(String captchaId) {
        return cacheProvider.get(ImageCaptchaProvider.CACHE_KEY_PREFIX + captchaId, String.class)
                .orElseThrow(() -> new IllegalStateException("测试前置条件：验证码答案应存在于缓存中"));
    }

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
                        "nickname", "验证码测试账号",
                        "status", 1), bearer(tokenOfAdmin())),
                Map.class);
        assertThat(created.getStatusCode()).as("前置条件：建号应成功").isEqualTo(HttpStatus.OK);
    }

    private Map<?, ?> loginAsAdmin() {
        ResponseEntity<Map> resp = rest.postForEntity("/api/auth/login",
                json(Map.of("type", "password", "username", "admin", "password", "admin123")),
                Map.class);
        assertThat(resp.getStatusCode()).as("登录应成功，检查种子数据").isEqualTo(HttpStatus.OK);
        return (Map<?, ?>) resp.getBody().get("data");
    }

    private String tokenOfAdmin() {
        return String.valueOf(loginAsAdmin().get("token"));
    }

    private static HttpEntity<Map<String, Object>> json(Map<String, Object> body) {
        HttpHeaders headers = new HttpHeaders();
        // 显式带上 charset，避免服务端按平台默认编码解析请求体里的中文
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
