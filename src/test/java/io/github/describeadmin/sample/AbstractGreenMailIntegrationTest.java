package io.github.describeadmin.sample;

import com.icegreen.greenmail.util.GreenMail;
import com.icegreen.greenmail.util.ServerSetupTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * 在 {@link AbstractMySqlIntegrationTest} 的基础上额外拉起一个真实（但纯 JVM 内、
 * 无需 Docker）的假 SMTP 服务器 GreenMail，供邮箱验证码登录插件的端到端测试使用。
 *
 * <p>与 MYSQL 容器同样的共享策略：整个测试类共用一个静态实例，避免每个测试方法
 * 都重新起停一次 SMTP 服务器。同时通过 {@code @DynamicPropertySource} 把
 * {@code spring.mail.host}/{@code port} 指向它——只有配置了 SMTP，
 * {@code framework-auth-email-starter} 的 {@code @ConditionalOnBean(JavaMailSender.class)}
 * 才会真正接管，邮箱登录能力才会出现在 {@code /api/auth/providers} 里。
 */
public abstract class AbstractGreenMailIntegrationTest extends AbstractMySqlIntegrationTest {

    private static final GreenMail GREEN_MAIL;

    static {
        GREEN_MAIL = new GreenMail(ServerSetupTest.SMTP);
        GREEN_MAIL.start();
    }

    @DynamicPropertySource
    static void mail(DynamicPropertyRegistry registry) {
        registry.add("spring.mail.host", () -> "localhost");
        registry.add("spring.mail.port", ServerSetupTest.SMTP::getPort);
        registry.add("describeadmin.auth.email.enabled", () -> "true");
        // 核心内置白名单不包含插件路径（分层硬边界），业务方必须自行追加
        registry.add("describeadmin.security.permit-all[0]", () -> "/api/auth/email/code");
    }

    protected static GreenMail greenMail() {
        return GREEN_MAIL;
    }
}
