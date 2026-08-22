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
 * 服务端权限点校验的 HTTP 端到端验证。
 *
 * <p><b>本组测试存在的理由</b>：在此之前，权限点只被下发给前端用于按钮显隐，
 * 服务端对所有非白名单接口一律只要求"已认证"。也就是说，任何一个能登录的账号
 * 直接构造 HTTP 请求就能调用任何接口——界面看起来是受控的，实际并不受控。
 * 这类缺陷从页面上永远看不出来，只能在 HTTP 层测。
 *
 * <p>覆盖两条独立的拒绝路径，它们的实现完全不同，必须分别验证：
 * <ul>
 *   <li>{@code BaseController} 通用端点 → {@code PermissionChecker.require}</li>
 *   <li>业务自定义端点 → Spring Security 的 {@code @PreAuthorize}</li>
 * </ul>
 * 两者都必须产出 403 + {@code code=40300}，而<b>不是 500</b>——
 * framework-web-starter 的 {@code GlobalExceptionHandler} 有一个
 * {@code @ExceptionHandler(Throwable.class)} 兜底，会把 {@code AccessDeniedException}
 * 吞成"服务器内部错误"。那种情况下权限其实拦住了，但前端看到的是 500，
 * 排查方向会完全跑偏。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@DisplayName("权限点校验（HTTP 端到端）")
class PermissionEnforcementIT extends AbstractMySqlIntegrationTest {

    @Autowired TestRestTemplate rest;

    // ------------------------------------------------------- 无权限账号被拒

    @Test
    @DisplayName("无角色账号访问通用列表端点被拒，且是 403 而不是 500")
    void noRoleUserIsDeniedOnBaseControllerEndpoint() {
        String token = tokenOfPowerlessUser("nobody-list");

        ResponseEntity<String> resp = rest.exchange("/api/system/user", HttpMethod.GET,
                new HttpEntity<>(bearer(token)), String.class);

        // 三项分开断言：任何一项单独成立都不足以说明行为正确
        assertThat(resp.getStatusCode())
                .as("必须是 403。若为 500，说明 AccessDeniedException 被 Throwable 兜底吃掉了")
                .isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(resp.getBody())
                .as("响应体的业务码应为 40300")
                .contains("\"code\":40300");
        assertThat(resp.getBody())
                .as("必须是 JSON，不能是 Spring Security 默认的 HTML 错误页")
                .doesNotContain("<html");
    }

    @Test
    @DisplayName("无角色账号访问 @PreAuthorize 端点被拒，与通用端点返回同一份响应")
    void noRoleUserIsDeniedOnPreAuthorizeEndpoint() {
        String token = tokenOfPowerlessUser("nobody-tree");

        ResponseEntity<String> viaPreAuthorize = rest.exchange("/api/system/menu/tree",
                HttpMethod.GET, new HttpEntity<>(bearer(token)), String.class);
        ResponseEntity<String> viaChecker = rest.exchange("/api/system/menu",
                HttpMethod.GET, new HttpEntity<>(bearer(token)), String.class);

        assertThat(viaPreAuthorize.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(viaChecker.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        // 两条路径的实现不同，但对客户端必须不可区分——否则前端要写两套错误处理。
        // timestamp 每次请求都不同，比对前剔除。
        assertThat(withoutTimestamp(viaPreAuthorize.getBody()))
                .isEqualTo(withoutTimestamp(viaChecker.getBody()));
        // 值断言：错误消息里的中文不能是乱码（CLAUDE.md 3.6）
        assertThat(viaPreAuthorize.getBody()).contains("无权访问");
    }

    @Test
    @DisplayName("无权限的写操作被拒，且数据库里确实没有落数据")
    void deniedWriteLeavesNoTrace() {
        String token = tokenOfPowerlessUser("nobody-write");

        ResponseEntity<String> resp = rest.exchange("/api/system/dept", HttpMethod.POST,
                new HttpEntity<>(Map.of("deptName", "越权创建的部门", "parentId", 0, "sort", 1),
                        bearer(token)),
                String.class);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        // 只断言状态码不够：要确认拦截发生在写库之前，而不是写完才报错
        ResponseEntity<Map> asAdmin = rest.exchange("/api/system/dept/tree", HttpMethod.GET,
                new HttpEntity<>(bearer(tokenOfAdmin())), Map.class);
        assertThat(String.valueOf(asAdmin.getBody().get("data")))
                .as("被拒的创建不应在库里留下任何痕迹")
                .doesNotContain("越权创建的部门");
    }

    // --------------------------------------------------------- 有权限则放行

    @Test
    @DisplayName("ADMIN 拥有全部权限点，通用端点与 @PreAuthorize 端点都放行")
    void adminPassesBothPaths() {
        String token = tokenOfAdmin();

        assertThat(rest.exchange("/api/system/user", HttpMethod.GET,
                new HttpEntity<>(bearer(token)), String.class).getStatusCode())
                .isEqualTo(HttpStatus.OK);
        assertThat(rest.exchange("/api/system/menu/tree", HttpMethod.GET,
                new HttpEntity<>(bearer(token)), String.class).getStatusCode())
                .isEqualTo(HttpStatus.OK);
    }

    @Test
    @DisplayName("权限点前缀由 @RequestMapping 推导，与种子数据里登记的权限点对得上")
    void permPrefixMatchesSeededPermissionCodes() {
        String token = tokenOfAdmin();

        // 这四个端点走的是 BaseController 通用 list，权限点分别推导为
        // system:user / system:role / system:menu / system:dept 加 :list。
        // 只要推导规则与 seed-rbac.sql 不一致，ADMIN 也会被拒——
        // 这条用例正是用来抓"推导出的前缀没人授权"这种静默错配的。
        for (String path : List.of("/api/system/user", "/api/system/role",
                "/api/system/menu", "/api/system/dept")) {
            assertThat(rest.exchange(path, HttpMethod.GET,
                    new HttpEntity<>(bearer(token)), String.class).getStatusCode())
                    .as("推导出的权限点前缀与种子数据不匹配: " + path)
                    .isEqualTo(HttpStatus.OK);
        }
    }

    @Test
    @DisplayName("业务模块的权限点前缀同样推导正确（/api/project → project:list）")
    void permPrefixWorksForGeneratedBusinessModule() {
        assertThat(rest.exchange("/api/project", HttpMethod.GET,
                new HttpEntity<>(bearer(tokenOfAdmin())), String.class).getStatusCode())
                .as("codegen 产出的模块，其 menu-*.sql 已把 project:list 授给 ADMIN")
                .isEqualTo(HttpStatus.OK);
    }

    // ------------------------------------------------------------------ 工具

    /**
     * 造一个能登录但没有任何角色的账号。
     *
     * <p>不复用 admin 再去摘权限，是因为 ADMIN 的授权关系是种子数据的一部分，
     * 改动它会污染同一个容器里的其他测试。
     */
    private String tokenOfPowerlessUser(String username) {
        ResponseEntity<Map> created = rest.exchange("/api/system/user/with-password",
                HttpMethod.POST,
                new HttpEntity<>(Map.of(
                        "username", username,
                        "password", "pwd-12345",
                        "nickname", "无权限账号",
                        "status", 1), bearer(tokenOfAdmin())),
                Map.class);
        assertThat(created.getStatusCode()).as("前置条件：建号应成功").isEqualTo(HttpStatus.OK);

        ResponseEntity<Map> login = rest.postForEntity("/api/auth/login",
                json(Map.of("type", "password", "username", username, "password", "pwd-12345")),
                Map.class);
        assertThat(login.getStatusCode()).as("前置条件：新账号应能登录").isEqualTo(HttpStatus.OK);

        Map<?, ?> data = (Map<?, ?>) login.getBody().get("data");
        @SuppressWarnings("unchecked")
        Map<String, Object> user = (Map<String, Object>) data.get("user");
        assertThat((List<?>) user.get("permissions"))
                .as("前置条件：该账号不应有任何权限点，否则本组测试失去意义")
                .isEmpty();
        return String.valueOf(data.get("token"));
    }

    private String tokenOfAdmin() {
        ResponseEntity<Map> resp = rest.postForEntity("/api/auth/login",
                json(Map.of("type", "password", "username", "admin", "password", "admin123")),
                Map.class);
        assertThat(resp.getStatusCode()).as("登录应成功，检查种子数据").isEqualTo(HttpStatus.OK);
        return String.valueOf(((Map<?, ?>) resp.getBody().get("data")).get("token"));
    }

    /** 剔除响应体里每次都不同的 timestamp 字段，便于比对两条拒绝路径的产出。 */
    private static String withoutTimestamp(String body) {
        return body == null ? null : body.replaceAll("\"timestamp\":\\d+,?", "");
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
