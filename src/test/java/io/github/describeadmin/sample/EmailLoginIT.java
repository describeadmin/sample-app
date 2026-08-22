package io.github.describeadmin.sample;

import jakarta.mail.Address;
import jakarta.mail.internet.MimeMessage;
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
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 邮箱验证码登录插件（framework-auth-email-starter）落地到 sample-app 的端到端验证。
 *
 * <p>只有本类（以及未来同样继承 {@link AbstractGreenMailIntegrationTest} 的测试）配置了
 * {@code spring.mail.host}，插件才会真正接管——{@link AuthFlowIT} 用的是不带 SMTP 配置的
 * 普通上下文，因此它那边 {@code /api/auth/providers} 仍然只有 {@code password} 一项，
 * 两组测试互不影响。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@DisplayName("邮箱验证码登录（HTTP 端到端）")
class EmailLoginIT extends AbstractGreenMailIntegrationTest {

    @Autowired TestRestTemplate rest;

    @Test
    @SuppressWarnings("unchecked")
    @DisplayName("配置了 SMTP 后，/api/auth/providers 同时列出 password 与 email")
    void providersIncludeEmail() {
        ResponseEntity<Map> resp = rest.getForEntity("/api/auth/providers", Map.class);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        // password 的 order() 是 -100，恒排最前；email 用默认 order()=0，排在其后
        assertThat((List<Object>) resp.getBody().get("data")).containsExactly("password", "email");
    }

    @Test
    @DisplayName("发码接口免认证即可调用——不带 Authorization 头也应成功")
    void sendCodeEndpointIsPermitAll() {
        String email = registerUserWithEmail("email-login-basic@example.com");

        ResponseEntity<String> resp = rest.postForEntity("/api/auth/email/code",
                json(Map.of("email", email)), String.class);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    @SuppressWarnings("unchecked")
    @DisplayName("完整走一遍：注册邮箱 → 发码 → GreenMail 真实收到验证码 → 用验证码登录成功")
    void fullEmailLoginFlow() throws Exception {
        String email = registerUserWithEmail("email-login-flow@example.com");

        rest.postForEntity("/api/auth/email/code", json(Map.of("email", email)), String.class);
        String code = extractCodeFromLatestMail(email);

        ResponseEntity<Map> loginResp = rest.postForEntity("/api/auth/login",
                json(Map.of("type", "email", "email", email, "code", code)), Map.class);

        assertThat(loginResp.getStatusCode()).isEqualTo(HttpStatus.OK);
        Map<String, Object> data = (Map<String, Object>) loginResp.getBody().get("data");
        assertThat(data.get("token")).asString().isNotBlank();
        Map<String, Object> user = (Map<String, Object>) data.get("user");
        assertThat(user.get("authType")).isEqualTo("email");
        assertThat(user.get("nickname")).isEqualTo("邮箱登录测试账号");
    }

    @Test
    @DisplayName("验证码错误：登录失败，返回业务错误而不是 500")
    void loginFailsWithWrongCode() {
        String email = registerUserWithEmail("email-login-wrong-code@example.com");
        rest.postForEntity("/api/auth/email/code", json(Map.of("email", email)), String.class);

        ResponseEntity<String> resp = rest.postForEntity("/api/auth/login",
                json(Map.of("type", "email", "email", email, "code", "000000")), String.class);

        assertThat(resp.getBody()).contains("验证码错误");
    }

    @Test
    @DisplayName("未注册邮箱发码：接口仍返回成功，但 GreenMail 收不到邮件——防账号枚举")
    void sendCodeForUnregisteredEmailIsSilentlySuccessful() {
        int before = greenMail().getReceivedMessages().length;

        ResponseEntity<String> resp = rest.postForEntity("/api/auth/email/code",
                json(Map.of("email", "nobody-registered-in-sample-app@example.com")), String.class);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(greenMail().getReceivedMessages()).hasSize(before);
    }

    // ---------------------------------------------------------------- 工具

    /** 用管理员建一个带邮箱的账号，返回该邮箱。 */
    private String registerUserWithEmail(String email) {
        String username = "email-user-" + Math.abs(email.hashCode());
        ResponseEntity<Map> created = rest.exchange("/api/system/user/with-password", HttpMethod.POST,
                new HttpEntity<>(Map.of(
                        "username", username,
                        "password", "irrelevant-password-1",
                        "nickname", "邮箱登录测试账号",
                        "email", email,
                        "status", 1), bearer(tokenOfAdmin())),
                Map.class);
        assertThat(created.getStatusCode()).as("前置条件：建号应成功").isEqualTo(HttpStatus.OK);
        return email;
    }

    /** 从 GreenMail 收到的、发给 {@code toEmail} 的最新一封邮件里抠出验证码。 */
    private String extractCodeFromLatestMail(String toEmail) throws Exception {
        MimeMessage[] messages = greenMail().getReceivedMessages();
        for (int i = messages.length - 1; i >= 0; i--) {
            MimeMessage message = messages[i];
            for (Address recipient : message.getAllRecipients()) {
                if (recipient.toString().contains(toEmail)) {
                    String body = String.valueOf(message.getContent());
                    Matcher matcher = Pattern.compile("验证码是：(\\d+)").matcher(body);
                    if (matcher.find()) {
                        return matcher.group(1);
                    }
                }
            }
        }
        throw new AssertionError("未在 GreenMail 收到的邮件里找到发给 " + toEmail + " 的验证码");
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
