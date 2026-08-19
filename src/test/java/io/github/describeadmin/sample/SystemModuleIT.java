package io.github.describeadmin.sample;

import io.github.describeadmin.system.entity.SysDept;
import io.github.describeadmin.system.entity.SysMenu;
import io.github.describeadmin.system.entity.SysRole;
import io.github.describeadmin.system.entity.SysUser;
import io.github.describeadmin.system.service.SysDeptService;
import io.github.describeadmin.system.service.SysMenuService;
import io.github.describeadmin.system.service.SysRoleService;
import io.github.describeadmin.system.service.SysUserService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 验证系统管理能力由框架提供、业务方零实现。
 *
 * <p>本类注入的全部是 {@code io.github.describeadmin.system.*} 下的 Bean——
 * sample-app 没有写任何一行 RBAC 代码，这些能力全部来自
 * framework-system-starter 的自动配置。
 *
 * <p>这正是 develop_plan.md 目标 #5 的落点：框架修了 RBAC 的问题，
 * 业务方升个版本就拿到了；若让每个业务方自己实现，框架永远修不了他们的代码。
 */
@DisplayName("系统管理（框架提供）")
class SystemModuleIT extends AbstractMySqlIntegrationTest {

    @Autowired SysUserService userService;
    @Autowired SysRoleService roleService;
    @Autowired SysMenuService menuService;
    @Autowired SysDeptService deptService;
    @Autowired PasswordEncoder passwordEncoder;

    @Test
    @DisplayName("系统管理的 Service 全部由框架自动装配，业务方无需声明")
    void servicesComeFromFramework() {
        for (Object bean : List.of(userService, roleService, menuService, deptService)) {
            assertThat(bean.getClass().getName())
                    .as("应来自框架包，而非业务方包")
                    .startsWith("io.github.describeadmin.system.");
        }
    }

    @Test
    @DisplayName("种子数据完整：管理员、角色、菜单、部门")
    void seedData() {
        SysUser admin = userService.findByUsername("admin");
        assertThat(admin).isNotNull();
        // 值断言而非计数断言 —— 字符集问题只有比对具体值才查得出（CLAUDE.md 3.6）
        assertThat(admin.getNickname()).isEqualTo("超级管理员");
        assertThat(admin.getStatus()).isEqualTo(1);

        assertThat(roleService.list()).extracting(SysRole::getRoleCode).contains("ADMIN");
        assertThat(deptService.list()).extracting(SysDept::getDeptName).contains("总部");
    }

    @Test
    @DisplayName("创建用户时密码被哈希，不会明文落库")
    void createUserHashesPassword() {
        SysUser u = new SysUser();
        u.setUsername("zhangsan");
        u.setNickname("张三");

        SysUser created = userService.createUser(u, "P@ssw0rd-123", List.of());

        SysUser fromDb = userService.getById(created.getId());
        assertThat(fromDb.getPassword())
                .as("必须是哈希，不能是明文")
                .isNotEqualTo("P@ssw0rd-123")
                .startsWith("$2a$");
        assertThat(passwordEncoder.matches("P@ssw0rd-123", fromDb.getPassword())).isTrue();
    }

    @Test
    @DisplayName("用户名重复被拒绝（应用层校验，非唯一索引）")
    void duplicateUsernameRejected() {
        SysUser u = new SysUser();
        u.setUsername("admin");
        assertThatThrownBy(() -> userService.createUser(u, "whatever", List.of()))
                .hasMessageContaining("用户名已存在");
    }

    @Test
    @DisplayName("重置密码后旧密码失效、新密码生效")
    void resetPassword() {
        SysUser u = new SysUser();
        u.setUsername("lisi");
        SysUser created = userService.createUser(u, "old-password", List.of());

        userService.resetPassword(created.getId(), "new-password");

        String hash = userService.getById(created.getId()).getPassword();
        assertThat(passwordEncoder.matches("old-password", hash)).isFalse();
        assertThat(passwordEncoder.matches("new-password", hash)).isTrue();
    }

    @Test
    @DisplayName("授权是重建语义：整体覆盖而非增量累加")
    void assignRolesIsRebuildNotAppend() {
        SysRole r1 = newRole("ROLE_A", "角色甲");
        SysRole r2 = newRole("ROLE_B", "角色乙");
        SysRole r3 = newRole("ROLE_C", "角色丙");

        SysUser u = new SysUser();
        u.setUsername("wangwu");
        SysUser created = userService.createUser(u, "pwd-123456",
                List.of(r1.getId(), r2.getId()));
        assertThat(userService.roleIdsOf(created.getId()))
                .containsExactlyInAnyOrder(r1.getId(), r2.getId());

        // 重新授权为单个角色：旧的两个应被清除，而不是累加成三个
        userService.assignRoles(created.getId(), List.of(r3.getId()));
        assertThat(userService.roleIdsOf(created.getId()))
                .containsExactly(r3.getId());

        // 清空授权
        userService.assignRoles(created.getId(), List.of());
        assertThat(userService.roleIdsOf(created.getId())).isEmpty();
    }

    @Test
    @DisplayName("角色授予菜单同样是重建语义")
    void assignMenusIsRebuild() {
        SysRole role = newRole("ROLE_MENU", "菜单测试角色");
        List<Long> allMenuIds = menuService.list().stream().map(SysMenu::getId).toList();
        assertThat(allMenuIds).isNotEmpty();

        roleService.assignMenus(role.getId(), allMenuIds);
        assertThat(roleService.menuIdsOf(role.getId())).hasSameSizeAs(allMenuIds);

        roleService.assignMenus(role.getId(), List.of(allMenuIds.get(0)));
        assertThat(roleService.menuIdsOf(role.getId())).containsExactly(allMenuIds.get(0));
    }

    @Test
    @DisplayName("菜单树在内存中组装，不依赖递归 CTE")
    void menuTree() {
        List<SysMenu> tree = menuService.tree();

        assertThat(tree).as("应有根节点").isNotEmpty();
        SysMenu root = tree.stream()
                .filter(m -> "系统管理".equals(m.getMenuName()))
                .findFirst().orElseThrow();
        assertThat(root.getParentId()).isZero();
        assertThat(root.getChildren())
                .as("子节点应被挂载到父节点下")
                .extracting(SysMenu::getMenuName)
                .contains("部门管理");
    }

    @Test
    @DisplayName("按用户过滤的菜单树只含其被授权的部分，且不含按钮")
    void menuTreeOfUser() {
        SysUser admin = userService.findByUsername("admin");
        List<SysMenu> tree = menuService.treeOf(admin.getId());
        assertThat(tree).isNotEmpty();
        assertNoButton(tree);

        // 未授权任何菜单的用户，拿到空树而不是全量
        SysUser u = new SysUser();
        u.setUsername("nobody");
        SysUser created = userService.createUser(u, "pwd-123456", List.of());
        assertThat(menuService.treeOf(created.getId())).isEmpty();
    }

    @Test
    @DisplayName("部门树可用")
    void deptTree() {
        SysDept child = new SysDept();
        child.setDeptName("信息中心");
        child.setParentId(deptService.list().get(0).getId());
        deptService.save(child);

        List<SysDept> tree = deptService.tree();
        assertThat(tree).extracting(SysDept::getDeptName).contains("总部");
        assertThat(tree.stream().flatMap(d -> d.getChildren().stream()))
                .extracting(SysDept::getDeptName)
                .contains("信息中心");
    }

    // ---------------------------------------------------------------- helpers

    private SysRole newRole(String code, String name) {
        SysRole r = new SysRole();
        r.setRoleCode(code);
        r.setRoleName(name);
        r.setSort(0);
        roleService.save(r);
        return r;
    }

    private static void assertNoButton(List<SysMenu> nodes) {
        for (SysMenu m : nodes) {
            assertThat(m.getMenuType())
                    .as("路由菜单树不应包含按钮类型")
                    .isNotEqualTo(SysMenu.TYPE_BUTTON);
            assertNoButton(m.getChildren());
        }
    }
}
