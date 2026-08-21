package io.github.describeadmin.sample;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import io.github.describeadmin.common.api.BizException;
import io.github.describeadmin.common.api.PageQuery;
import io.github.describeadmin.common.api.PageResult;
import io.github.describeadmin.sample.project.entity.ProjectEntity;
import io.github.describeadmin.sample.project.service.ProjectService;
import io.github.describeadmin.security.api.AuthRequest;
import io.github.describeadmin.security.api.AuthUser;
import io.github.describeadmin.security.api.AuthUserLoader;
import io.github.describeadmin.security.api.LoginUser;
import io.github.describeadmin.security.core.AuthProviderRegistry;
import io.github.describeadmin.system.entity.SysRole;
import io.github.describeadmin.system.entity.SysUser;
import io.github.describeadmin.system.service.SysRoleService;
import io.github.describeadmin.system.service.SysUserService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 框架运行时行为的集成验证（业务方视角）。
 *
 * <p>本类只使用业务方能拿到的东西：业务实体 {@link ProjectEntity} 与框架公开的 API。
 * 系统管理（用户/角色/菜单/部门）由 framework-system-starter 提供，
 * 业务方一行都不用写——这一点由 {@link SystemModuleIT} 验证。
 */
@DisplayName("框架运行时行为")
class FrameworkRuntimeIT extends AbstractMySqlIntegrationTest {

    @Autowired ProjectService projectService;
    @Autowired AuthProviderRegistry authRegistry;
    @Autowired AuthUserLoader userLoader;
    @Autowired JdbcTemplate jdbc;
    @Autowired SysRoleService roleService;
    @Autowired SysUserService userService;

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

    // ---------------------------------------------------------------- 基类能力

    @Test
    @DisplayName("审计字段由框架自动填充，业务代码不赋值")
    void auditFieldsAutoFilled() {
        ProjectEntity p = newProject("智慧城市一期");
        projectService.save(p);

        ProjectEntity saved = projectService.getById(p.getId());
        assertThat(saved.getCreateTime()).as("createTime 应被自动填充").isNotNull();
        assertThat(saved.getUpdateTime()).as("updateTime 应被自动填充").isNotNull();
        assertThat(saved.getDeleted()).isZero();
        assertThat(saved.getVersion()).isZero();
    }

    @Test
    @DisplayName("主键为数据库自增，保存后回填")
    void autoIncrementIdBackfilled() {
        ProjectEntity a = newProject("甲项目");
        projectService.save(a);
        ProjectEntity b = newProject("乙项目");
        projectService.save(b);

        assertThat(a.getId()).isNotNull().isPositive();
        assertThat(b.getId()).isGreaterThan(a.getId());
    }

    @Test
    @DisplayName("中文与 DECIMAL 经 ORM 往返不损坏")
    void roundTrip() {
        ProjectEntity p = newProject("测试项目-自动化");
        p.setBudget(new BigDecimal("1234567.89"));
        projectService.save(p);

        ProjectEntity got = projectService.getById(p.getId());
        assertThat(got.getProjectName()).isEqualTo("测试项目-自动化");
        assertThat(got.getBudget()).isEqualByComparingTo("1234567.89");
    }

    @Test
    @DisplayName("分页插件生效，且 count 查询正确")
    void paginationWorks() {
        for (int i = 1; i <= 25; i++) {
            ProjectEntity p = newProject("分页项目" + i);
            p.setStatus(i);
            projectService.save(p);
        }

        PageQuery q = new PageQuery();
        q.setCurrent(2);
        q.setSize(10);
        QueryWrapper<ProjectEntity> w = new QueryWrapper<ProjectEntity>()
                .likeRight("project_name", "分页项目").orderByAsc("status");

        PageResult<ProjectEntity> page = projectService.page(q, w);

        assertThat(page.getRecords()).hasSize(10);
        assertThat(page.getTotal()).isEqualTo(25);
        assertThat(page.getPages()).isEqualTo(3);
        assertThat(page.getRecords().get(0).getStatus()).isEqualTo(11);
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

    @Test
    @DisplayName("removeById 执行逻辑删除，物理行保留")
    void logicalDelete() {
        ProjectEntity p = newProject("待删除项目");
        projectService.save(p);
        Long id = p.getId();

        assertThat(projectService.removeById(id)).isTrue();
        assertThat(projectService.getById(id)).as("查询应过滤已逻辑删除的记录").isNull();

        Integer physical = jdbc.queryForObject(
                "SELECT COUNT(*) FROM biz_project WHERE id = ?", Integer.class, id);
        assertThat(physical).as("物理行应仍然存在").isEqualTo(1);
    }

    @Test
    @DisplayName("乐观锁：版本号自增，过期版本更新失败")
    void optimisticLock() {
        ProjectEntity p = newProject("并发项目");
        projectService.save(p);

        ProjectEntity first = projectService.getById(p.getId());
        ProjectEntity stale = projectService.getById(p.getId());

        first.setProjectName("先改的");
        assertThat(projectService.updateById(first)).isTrue();
        assertThat(projectService.getById(p.getId()).getVersion()).isEqualTo(1);

        stale.setProjectName("后改的");
        assertThat(projectService.updateById(stale))
                .as("持有过期 version 的更新应失败").isFalse();
        assertThat(projectService.getById(p.getId()).getProjectName()).isEqualTo("先改的");
    }

    // ---------------------------------------------------------------- 认证 SPI

    @Test
    @DisplayName("AuthProvider 被自动收集，内置 password 方式可用")
    void authProviderCollected() {
        assertThat(authRegistry.availableTypes()).contains("password");
    }

    @Test
    @DisplayName("默认 AuthUserLoader 由框架提供，业务方无需实现")
    void defaultAuthUserLoaderProvidedByFramework() {
        // 业务方没有写任何 AuthUserLoader 实现，这个 Bean 来自 framework-system-starter
        assertThat(userLoader.getClass().getName())
                .startsWith("io.github.describeadmin.system.");

        Optional<AuthUser> found = userLoader.loadByUsername("admin");
        assertThat(found).isPresent();
        assertThat(found.get().isEnabled()).isTrue();
        assertThat(found.get().getPasswordHash()).startsWith("$2a$");
        assertThat(userLoader.loadByUsername("ghost")).isEmpty();
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
        assertThat(catchMessage("admin", "wrong-password"))
                .isEqualTo(catchMessage("no-such-user-at-all", "whatever"));
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
    @DisplayName("角色未设置首页时，登录用户的 homePath 为 null，交由前端落回全局默认值")
    void loginWithoutRoleHomePathReturnsNull() {
        LoginUser user = authRegistry.authenticate(new AuthRequest("password",
                Map.of("username", "admin", "password", "admin123")));

        assertThat(user.getHomePath()).isNull();
    }

    @Test
    @DisplayName("角色设置了首页后，登录用户的 homePath 携带该角色配置的路径")
    void loginCarriesRoleHomePath() {
        SysRole role = new SysRole();
        role.setRoleCode("HOME_PATH_TEST");
        role.setRoleName("首页测试角色");
        role.setSort(0);
        role.setHomePath("/system/dict");
        roleService.save(role);

        SysUser u = new SysUser();
        u.setUsername("homepath-user");
        userService.createUser(u, "pwd-123456", List.of(role.getId()));

        LoginUser user = authRegistry.authenticate(new AuthRequest("password",
                Map.of("username", "homepath-user", "password", "pwd-123456")));

        assertThat(user.getHomePath()).isEqualTo("/system/dict");
    }

    // ---------------------------------------------------------------- helpers

    private static ProjectEntity newProject(String name) {
        ProjectEntity p = new ProjectEntity();
        p.setProjectName(name);
        p.setStatus(1);
        return p;
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
}
