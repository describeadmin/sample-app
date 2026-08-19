package io.github.describeadmin.sample;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * 业务模拟样板应用。
 *
 * <p>它同时承担两个职责（见 develop_plan.md 第七章）：
 * <ol>
 *   <li>以真实业务方的姿态消费框架——只 import {@code framework-bom}，不继承框架父 POM，
 *       任何"只有真实业务工程才会暴露"的问题都会在这里被发现</li>
 *   <li>作为发版前兼容性测试门禁的载体</li>
 * </ol>
 */
@SpringBootApplication
@MapperScan("io.github.describeadmin.sample.**.mapper")
public class SampleApplication {

    public static void main(String[] args) {
        SpringApplication.run(SampleApplication.class, args);
    }
}
