package io.github.describeadmin.sample;

import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.utility.DockerImageName;

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
                () -> "classpath:db/schema-rbac.sql,classpath:db/schema-biz_project.sql");
        registry.add("spring.sql.init.data-locations",
                () -> "classpath:db/seed-rbac.sql,classpath:db/menu-biz_project.sql");

        // ⚠️ 必须显式指定，否则 Spring 用【平台默认编码】读取 SQL 脚本文件。
        // 在中文 Windows 上默认是 GBK，会把 UTF-8 的脚本读坏，插进库里的中文全是乱码，
        // 而行数校验完全正常 —— 这个坑本项目已经在两个不同入口各踩过一次
        // （另一次是 docker-compose 的 seed-job，见 VERSION_BASELINE.md 发现 ④）。
        registry.add("spring.sql.init.encoding", () -> "UTF-8");
    }

    /** 供测试断言使用：当前跑的是哪个 MySQL 镜像。 */
    protected static String currentImage() {
        return IMAGE;
    }
}
