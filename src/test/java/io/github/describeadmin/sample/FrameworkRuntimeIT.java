package io.github.describeadmin.sample;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import io.github.describeadmin.common.api.PageQuery;
import io.github.describeadmin.common.api.PageResult;
import io.github.describeadmin.sample.dept.entity.DeptEntity;
import io.github.describeadmin.sample.dept.service.DeptService;
import io.github.describeadmin.security.api.AuthRequest;
import io.github.describeadmin.security.api.AuthUser;
import io.github.describeadmin.security.api.AuthUserLoader;
import io.github.describeadmin.security.api.LoginUser;
import io.github.describeadmin.security.core.AuthProviderRegistry;
import io.github.describeadmin.common.api.BizException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 框架运行时行为的集成验证。
 *
 * <p>此前只验证到编译期，本类覆盖的是「在真实 Spring 上下文 + 真实 MySQL 上是否真的按预期工作」，
 * 这是 Walking Skeleton 剩余的高风险面。
 */
@DisplayName("框架运行时行为")
class FrameworkRuntimeIT extends AbstractMySqlIntegrationTest {

    @Autowired DeptService deptService;
    @Autowired AuthProviderRegistry authRegistry;
    @Autowired AuthUserLoader userLoader;
    @Autowired JdbcTemplate jdbc;

    // ---------------------------------------------------------------- 环境

    @Test
    @DisplayName("跑在预期的 MySQL 版本上，且字符集为显式声明的值")
    void environment() {
        String version = jdbc.queryForObject("SELECT VERSION()", String.class);
        String collation = jdbc.queryForObject("SELECT @@collation_server", String.class);
        System.out.println("  测试镜像=" + currentImage() + "  server=" + version);

        assertThat(collation).isEqualTo("utf8mb4_general_ci");
        if (currentImage().contains("5.7")) {
            assertThat(version).startsWith("5.7");
        }
    }

    // ---------------------------------------------------------------- 审计字段

    @Test
    @DisplayName("审计字段由 AuditMetaObjectHandler 自动填充，业务代码不赋值")
    void auditFieldsAutoFilled() {
        DeptEntity d = new DeptEntity();
        d.setDeptName("研发部");
        // 刻意不设置任何审计字段
        deptService.save(d);

        DeptEntity saved = deptService.getById(d.getId());
        assertThat(saved.getCreateTime()).as("createTime 应被自动填充").isNotNull();
        assertThat(saved.getUpdateTime()).as("updateTime 应被自动填充").isNotNull();
        assertThat(saved.getDeleted()).as("deleted 默认 0").isZero();
        assertThat(saved.getVersion()).as("version 默认 0").isZero();
    }

    @Test
    @DisplayName("主键为数据库自增（IdType.AUTO），保存后回填")
    void autoIncrementIdBackfilled() {
        DeptEntity a = new DeptEntity();
        a.setDeptName("甲部门");
        deptService.save(a);
        DeptEntity b = new DeptEntity();
        b.setDeptName("乙部门");
        deptService.save(b);

        assertThat(a.getId()).isNotNull().isPositive();
        assertThat(b.getId()).isGreaterThan(a.getId());
    }

    // ---------------------------------------------------------------- 中文

    @Test
    @DisplayName("中文经 ORM 往返不损坏")
    void chineseRoundTrip() {
        String name = "测试部门-自动化";
        DeptEntity d = new DeptEntity();
        d.setDeptName(name);
        deptService.save(d);

        assertThat(deptService.getById(d.getId()).getDeptName()).isEqualTo(name);
    }

    @Test
    @DisplayName("种子数据中的中文完整（计数断言查不出的那类问题）")
    void seedChineseIntact() {
        String nickname = jdbc.queryForObject(
                "SELECT nickname FROM sys_user WHERE username = 'admin' AND deleted = 0",
                String.class);
        // 只断言 COUNT(*) 是不够的 —— 字符集配错时计数依然正确
        assertThat(nickname).isEqualTo("超级管理员");
    }

    // ---------------------------------------------------------------- 分页

    @Test
    @DisplayName("分页插件生效，且 count 查询正确")
    void paginationWorks() {
        for (int i = 1; i <= 25; i++) {
            DeptEntity d = new DeptEntity();
            d.setDeptName("分页部门" + i);
            d.setSort(i);
            deptService.save(d);
        }

        PageQuery q = new PageQuery();
        q.setCurrent(2);
        q.setSize(10);
        QueryWrapper<DeptEntity> w = new QueryWrapper<>();
        w.likeRight("dept_name", "分页部门").orderByAsc("sort");

        PageResult<DeptEntity> page = deptService.page(q, w);

        assertThat(page.getRecords()).hasSize(10);
        assertThat(page.getTotal()).isEqualTo(25);
        assertThat(page.getCurrent()).isEqualTo(2);
        assertThat(page.getPages()).isEqualTo(3);
        assertThat(page.getRecords().get(0).getSort()).isEqualTo(11);
    }

    @Test
    @DisplayName("PageQuery 的 size 上限在入口处被夹紧")
    void pageSizeClamped() {
        PageQuery q = new PageQuery();
        q.setSize(999_999);
        q.setCurrent(0);
        assertThat(q.getSize()).isEqualTo(PageQuery.MAX_SIZE);
        assertThat(q.getCurrent()).isEqualTo(1);
    }

    // ---------------------------------------------------------------- 逻辑删除 / 乐观锁

    @Test
    @DisplayName("removeById 执行逻辑删除，记录仍在物理表中")
    void logicalDelete() {
        DeptEntity d = new DeptEntity();
        d.setDeptName("待删除部门");
        deptService.save(d);
        Long id = d.getId();

        assertThat(deptService.removeById(id)).isTrue();
        assertThat(deptService.getById(id)).as("查询应过滤掉已逻辑删除的记录").isNull();

        Integer physical = jdbc.queryForObject(
                "SELECT COUNT(*) FROM biz_dept WHERE id = ?", Integer.class, id);
        assertThat(physical).as("物理行应仍然存在").isEqualTo(1);
    }

    @Test
    @DisplayName("乐观锁：版本号自增，过期版本更新失败")
    void optimisticLock() {
        DeptEntity d = new DeptEntity();
        d.setDeptName("并发部门");
        deptService.save(d);

        DeptEntity first = deptService.getById(d.getId());
        DeptEntity stale = deptService.getById(d.getId());   // 同一版本的另一份副本

        first.setDeptName("先改的");
        assertThat(deptService.updateById(first)).isTrue();
        assertThat(deptService.getById(d.getId()).getVersion()).isEqualTo(1);

        stale.setDeptName("后改的");
        assertThat(deptService.updateById(stale))
                .as("持有过期 version 的更新应失败").isFalse();
        assertThat(deptService.getById(d.getId()).getDeptName()).isEqualTo("先改的");
    }

    // ---------------------------------------------------------------- 认证 SPI

    @Test
    @DisplayName("AuthProvider 被自动收集，内置 password 方式可用")
    void authProviderCollected() {
        assertThat(authRegistry.availableTypes()).contains("password");
    }

    @Test
    @DisplayName("用户名密码登录成功，并带出角色与权限")
    void loginSucceeds() {
        LoginUser user = authRegistry.authenticate(new AuthRequest("password",
                Map.of("username", "admin", "password", "admin123")));

        assertThat(user.getUsername()).isEqualTo("admin");
        assertThat(user.getNickname()).isEqualTo("超级管理员");
        assertThat(user.getAuthType()).isEqualTo("password");
        assertThat(user.getRoles()).contains("ADMIN");
        assertThat(user.getPermissions()).contains("system:dept:list");
    }

    @Test
    @DisplayName("密码错误与用户不存在返回同一条信息，不泄露账号是否存在")
    void loginFailureDoesNotLeakAccountExistence() {
        String msgWrongPassword = catchMessage("admin", "wrong-password");
        String msgNoSuchUser = catchMessage("no-such-user-at-all", "whatever");
        assertThat(msgWrongPassword).isEqualTo(msgNoSuchUser);
    }

    private String catchMessage(String username, String password) {
        try {
            authRegistry.authenticate(new AuthRequest("password",
                    Map.of("username", username, "password", password)));
            throw new AssertionError("认证本应失败");
        } catch (BizException e) {
            return e.getMessage();
        }
    }

    @Test
    @DisplayName("未知登录方式被拒绝")
    void unknownAuthTypeRejected() {
        assertThatThrownBy(() -> authRegistry.authenticate(
                new AuthRequest("no-such-provider", Map.of())))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("不支持的登录方式");
    }

    @Test
    @DisplayName("AuthUserLoader 的 RBAC 查询在 5.7 安全子集下可用")
    void authUserLoaderQueries() {
        Optional<AuthUser> found = userLoader.loadByUsername("admin");
        assertThat(found).isPresent();
        assertThat(found.get().isEnabled()).isTrue();
        assertThat(found.get().getPasswordHash()).startsWith("$2a$");
        assertThat(userLoader.loadByUsername("ghost")).isEmpty();
    }
}
