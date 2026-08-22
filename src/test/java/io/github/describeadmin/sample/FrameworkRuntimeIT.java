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
import io.github.describeadmin.security.api.TokenStore;
import io.github.describeadmin.security.core.AuthProviderRegistry;
import io.github.describeadmin.system.controller.SysUserController;
import io.github.describeadmin.system.entity.SysRole;
import io.github.describeadmin.system.entity.SysUser;
import io.github.describeadmin.system.service.SysRoleService;
import io.github.describeadmin.system.service.SysUserService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import java.math.BigDecimal;
import java.util.ArrayList;
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
    @Autowired SysUserController userController;
    @Autowired TokenStore tokenStore;

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
    @DisplayName("loadByUserId 与 loadByUsername 拼出同一个完整用户")
    void loadByUserIdMatchesLoadByUsername() {
        AuthUser byUsername = userLoader.loadByUsername("admin").orElseThrow();

        Optional<AuthUser> byId = userLoader.loadByUserId(byUsername.getUserId());

        assertThat(byId).isPresent();
        AuthUser found = byId.get();
        assertThat(found.getUserId()).isEqualTo(byUsername.getUserId());
        assertThat(found.getUsername()).isEqualTo(byUsername.getUsername());
        assertThat(found.getNickname()).isEqualTo(byUsername.getNickname());
        assertThat(found.isEnabled()).isEqualTo(byUsername.isEnabled());
        assertThat(found.getRoles()).isEqualTo(byUsername.getRoles());
        assertThat(found.getPermissions()).isEqualTo(byUsername.getPermissions());
        assertThat(found.getDeptId()).isEqualTo(byUsername.getDeptId());
        assertThat(found.getDataScope()).isEqualTo(byUsername.getDataScope());
        assertThat(found.getCustomDeptIds()).isEqualTo(byUsername.getCustomDeptIds());
        assertThat(found.getHomePath()).isEqualTo(byUsername.getHomePath());
    }

    @Test
    @DisplayName("loadByUserId 对不存在的 id 返回空")
    void loadByUserIdReturnsEmptyForUnknownId() {
        assertThat(userLoader.loadByUserId(-1L)).isEmpty();
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

    // ---------------------------------------------------------------- 改密码 / 禁用账号：令牌吊销

    @Test
    @DisplayName("管理员重置密码后，该用户已签发的令牌立即失效（docs/LOGIN_MODULE_AUDIT.md B 项）")
    void resetPasswordRevokesExistingTokens() {
        SysUser u = new SysUser();
        u.setUsername("revoke-on-reset-pwd");
        userService.createUser(u, "old-password-1", List.of());

        LoginUser loginUser = authRegistry.authenticate(new AuthRequest("password",
                Map.of("username", "revoke-on-reset-pwd", "password", "old-password-1")));
        String token = tokenStore.issue(loginUser);
        assertThat(tokenStore.resolve(token)).as("重置前令牌应有效").isPresent();

        userService.resetPassword(u.getId(), "new-password-1");

        assertThat(tokenStore.resolve(token)).as("重置密码后旧令牌应立即失效").isEmpty();
    }

    @Test
    @DisplayName("自助改密：旧密码错误时拒绝，且不吊销任何令牌")
    void changeOwnPasswordRejectsWrongOldPassword() {
        SysUser u = new SysUser();
        u.setUsername("change-pwd-wrong-old");
        userService.createUser(u, "correct-old-1", List.of());
        LoginUser loginUser = authRegistry.authenticate(new AuthRequest("password",
                Map.of("username", "change-pwd-wrong-old", "password", "correct-old-1")));
        String token = tokenStore.issue(loginUser);

        assertThatThrownBy(() -> userService.changeOwnPassword(u.getId(), "not-the-old-one", "new-pwd-1"))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("原密码不正确");

        assertThat(tokenStore.resolve(token)).as("校验失败不应吊销任何令牌").isPresent();
    }

    @Test
    @DisplayName("自助改密：旧密码正确则成功，且吊销全部旧令牌")
    void changeOwnPasswordSucceedsAndRevokesTokens() {
        SysUser u = new SysUser();
        u.setUsername("change-pwd-success");
        userService.createUser(u, "old-pwd-1", List.of());
        LoginUser loginUser = authRegistry.authenticate(new AuthRequest("password",
                Map.of("username", "change-pwd-success", "password", "old-pwd-1")));
        String token = tokenStore.issue(loginUser);

        userService.changeOwnPassword(u.getId(), "old-pwd-1", "brand-new-pwd-1");

        assertThat(tokenStore.resolve(token)).as("改密成功后旧令牌应立即失效").isEmpty();
        // 新密码确实生效：用它能登录
        assertThat(authRegistry.authenticate(new AuthRequest("password",
                Map.of("username", "change-pwd-success", "password", "brand-new-pwd-1"))))
                .isNotNull();
    }

    @Test
    @DisplayName("把账号 status 显式改为 0（禁用）后，已签发令牌立即失效")
    void disablingAccountRevokesExistingTokens() {
        SysUser u = new SysUser();
        u.setUsername("revoke-on-disable");
        userService.createUser(u, "pwd-12345", List.of());
        LoginUser loginUser = authRegistry.authenticate(new AuthRequest("password",
                Map.of("username", "revoke-on-disable", "password", "pwd-12345")));
        String token = tokenStore.issue(loginUser);

        // 先取出完整实体再改动一个字段——通用 update() 在本项目里按实际值生成 UPDATE，
        // 拿一个只设了 status 的裸对象去更新会把其余列（如 username）显式置空
        // （见 SysUser.password 字段注释里 SysDeptService.updateDept 的同类先例）
        SysUser patch = userService.getById(u.getId());
        patch.setStatus(0);
        // Controller 挂了 @OperLog，切面要从 SecurityContext 取当前操作人——直接调用
        // Controller 方法（绕开 HTTP 过滤器链）必须自己模拟 TokenAuthenticationFilter
        // 本该做的事，否则会因为"上下文里没有 Authentication"而报错
        asAdmin(() -> userController.update(u.getId(), patch));

        assertThat(tokenStore.resolve(token)).as("禁用账号后旧令牌应立即失效").isEmpty();
    }

    @Test
    @DisplayName("编辑账号但不改 status 时，不触发任何令牌吊销")
    void editingOtherFieldsDoesNotRevokeTokens() {
        SysUser u = new SysUser();
        u.setUsername("no-revoke-on-other-edit");
        u.setNickname("旧昵称");
        userService.createUser(u, "pwd-12345", List.of());
        LoginUser loginUser = authRegistry.authenticate(new AuthRequest("password",
                Map.of("username", "no-revoke-on-other-edit", "password", "pwd-12345")));
        String token = tokenStore.issue(loginUser);

        SysUser patch = userService.getById(u.getId());
        patch.setNickname("新昵称");
        asAdmin(() -> userController.update(u.getId(), patch));

        assertThat(tokenStore.resolve(token)).as("只改昵称不应吊销令牌").isPresent();
    }

    // ---------------------------------------------------------------- access/refresh 双令牌

    @Test
    @DisplayName("issueWithRefresh 签发的一对令牌都能独立解析，且 refresh 换发后旧的立即失效")
    void issueWithRefreshRotatesToken() {
        LoginUser loginUser = authRegistry.authenticate(new AuthRequest("password",
                Map.of("username", "admin", "password", "admin123")));

        var tokens = tokenStore.issueWithRefresh(loginUser);
        assertThat(tokenStore.resolve(tokens.getAccessToken())).isPresent();
        assertThat(tokens.getRefreshToken()).isNotBlank();

        var refreshed = tokenStore.refresh(tokens.getRefreshToken());
        assertThat(refreshed).isPresent();
        assertThat(refreshed.get().getRefreshToken()).isNotEqualTo(tokens.getRefreshToken());
        // 旧 refresh token 只能用一次
        assertThat(tokenStore.refresh(tokens.getRefreshToken())).isEmpty();
    }

    // ---------------------------------------------------------------- helpers

    /**
     * 以管理员身份临时填充 SecurityContext 执行一段动作，模拟
     * {@code TokenAuthenticationFilter} 在真实 HTTP 请求里做的事——直接调用带
     * {@code @OperLog}/依赖 {@code CurrentUserProvider} 的 Controller 方法时，
     * 绕开了过滤器链，必须自己把这一步补上，否则会抛
     * {@code AuthenticationCredentialsNotFoundException}。
     */
    private void asAdmin(Runnable action) {
        LoginUser admin = authRegistry.authenticate(new AuthRequest("password",
                Map.of("username", "admin", "password", "admin123")));
        List<GrantedAuthority> authorities = new ArrayList<>();
        for (String role : admin.getRoles()) {
            authorities.add(new SimpleGrantedAuthority("ROLE_" + role));
        }
        for (String permission : admin.getPermissions()) {
            authorities.add(new SimpleGrantedAuthority(permission));
        }
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(admin, null, authorities));
        try {
            action.run();
        } finally {
            SecurityContextHolder.clearContext();
        }
    }

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
