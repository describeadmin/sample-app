package io.github.describeadmin.sample;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.utility.DockerImageName;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;

/**
 * 集成测试基类：拉起真实 MySQL 容器。
 *
 * <p><b>镜像版本可参数化</b>，这让 {@code mvn test} 本身就是 Tier 1 双版本门禁：
 *
 * <pre>
 *   mvn test                              # 默认 mysql:5.7
 *   mvn test -Dmysql.image=mysql:8.4      # 8.4-LTS
 * </pre>
 *
 * <p>默认值刻意取最严格的 5.7——如果默认给 8.4，"本地跑通、CI 才在 5.7 上挂"
 * 的情况会反复发生。
 *
 * <p>容器为整个测试类共享（{@code static} + 手动 start），避免每个测试方法都重启数据库。
 */
@SpringBootTest
public abstract class AbstractMySqlIntegrationTest {

    /** 与 docker-compose.test.yml 的 MYSQL_IMAGE 保持同一套取值。 */
    private static final String IMAGE = System.getProperty("mysql.image", "mysql:5.7");

    /**
     * dev-seed 写随机管理员口令的文件，相对 surefire 的工作目录（模块根）。
     * 放 {@code target/} 下：随 {@code mvn clean} 清理、不污染仓库、天然被 .gitignore 覆盖。
     */
    private static final String DEV_SEED_PASSWORD_FILE = "target/it-dev-seed.passwd";

    private static volatile String cachedAdminPassword;

    @SuppressWarnings("resource")
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>(
            DockerImageName.parse(IMAGE).asCompatibleSubstituteFor("mysql"))
            .withDatabaseName("describeadmin_test")
            .withUsername("app")
            .withPassword("app")
            // 与 schema 中显式声明的字符集保持一致，不依赖服务器默认值
            .withCommand("--character-set-server=utf8mb4",
                    "--collation-server=utf8mb4_general_ci")
            .withReuse(false);

    static {
        MYSQL.start();
    }

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> MYSQL.getJdbcUrl()
                + "?useSSL=false&allowPublicKeyRetrieval=true"
                + "&serverTimezone=Asia/Shanghai&characterEncoding=utf8");
        registry.add("spring.datasource.username", MYSQL::getUsername);
        registry.add("spring.datasource.password", MYSQL::getPassword);
        registry.add("spring.datasource.driver-class-name", MYSQL::getDriverClassName);

        // 由 Spring Boot 自动执行建表与种子脚本，保证每次测试拿到确定的初始状态
        registry.add("spring.sql.init.mode", () -> "always");
        registry.add("spring.sql.init.schema-locations",
                () -> "classpath:db/schema-rbac.sql,classpath:db/schema-biz_project.sql,classpath:db/schema-biz_employee.sql,classpath:db/schema-biz_nurse.sql");
        registry.add("spring.sql.init.data-locations",
                () -> "classpath:db/seed-rbac.sql,classpath:db/menu-biz_project.sql,classpath:db/menu-biz_employee.sql,classpath:db/menu-biz_nurse.sql");

        // ⚠️ 必须显式指定，否则 Spring 用【平台默认编码】读取 SQL 脚本文件。
        // 在中文 Windows 上默认是 GBK，会把 UTF-8 的脚本读坏，插进库里的中文全是乱码，
        // 而行数校验完全正常 —— 这个坑本项目已经在两个不同入口各踩过一次
        // （另一次是 docker-compose 的 seed-job，见 VERSION_BASELINE.md 发现 ④）。
        registry.add("spring.sql.init.encoding", () -> "UTF-8");

        // 种子不再写死 admin 口令：DevAdminSeeder 生成随机强口令，明文写到下面这个文件。
        // MYSQL 容器与 Spring 上下文全程共享 + seeder 幂等 → 文件只写一次，所有 IT 读同一份。
        registry.add("describeadmin.system.dev-seed.enabled", () -> "true");
        registry.add("describeadmin.system.dev-seed.password-file", () -> DEV_SEED_PASSWORD_FILE);
    }

    /** 供测试断言使用：当前跑的是哪个 MySQL 镜像。 */
    protected static String currentImage() {
        return IMAGE;
    }

    @Autowired(required = false)
    private JdbcTemplate jdbcTemplate;

    /**
     * 清掉某用户的强制改密标记。
     *
     * <p>管理员建号 / 重置密码后 {@code pwd_reset_required} 会被置 1，被标记用户除改密外
     * 一律 403。绝大多数既有用例是"建号 → 以该用户身份调受保护接口"，与强制改密无关，
     * 建号后调用本方法把这些用例恢复到原来的行为。强制改密本身由 {@code PasswordResetRequiredIT} 专门覆盖。
     */
    protected void clearPwdResetRequired(String username) {
        jdbcTemplate.update("UPDATE sys_user SET pwd_reset_required = 0 WHERE username = ?", username);
    }

    /**
     * dev-seed 生成的管理员随机口令。所有登录 helper 用它代替原先写死的 {@code admin123}。
     *
     * <p>{@code @SpringBootTest} 启动上下文时 {@code DevAdminSeeder}（{@code ApplicationRunner}）
     * 已经跑过并写好文件，因此测试方法里读到的一定是最新值。带轮询是为了容忍
     * 首个上下文尚未完全就绪的极小时间窗。
     */
    protected static String devSeedAdminPassword() {
        String cached = cachedAdminPassword;
        if (cached != null) {
            return cached;
        }
        Path file = Path.of(System.getProperty("user.dir")).resolve(DEV_SEED_PASSWORD_FILE);
        long deadline = System.currentTimeMillis() + Duration.ofSeconds(30).toMillis();
        while (System.currentTimeMillis() < deadline) {
            try {
                if (Files.exists(file)) {
                    String value = Files.readString(file, StandardCharsets.UTF_8).trim();
                    if (!value.isEmpty()) {
                        cachedAdminPassword = value;
                        return value;
                    }
                }
                Thread.sleep(200);
            } catch (IOException e) {
                throw new IllegalStateException("读取 dev-seed 口令文件失败: " + file, e);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("等待 dev-seed 口令文件被中断", e);
            }
        }
        throw new IllegalStateException("dev-seed 口令文件始终不存在: " + file
                + "（确认 DevAdminSeeder 已装配、且 describeadmin.system.dev-seed.enabled=true）");
    }
}
