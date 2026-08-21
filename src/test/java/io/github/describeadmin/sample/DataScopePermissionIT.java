package io.github.describeadmin.sample;

import io.github.describeadmin.common.api.DataScopeType;
import io.github.describeadmin.system.entity.SysDept;
import io.github.describeadmin.system.entity.SysRole;
import io.github.describeadmin.system.entity.SysUser;
import io.github.describeadmin.system.service.SysDeptService;
import io.github.describeadmin.system.service.SysMenuService;
import io.github.describeadmin.system.service.SysRoleService;
import io.github.describeadmin.system.service.SysUserService;
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
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 数据权限（按部门行级过滤）的 HTTP 端到端验证。
 *
 * <p>每个用例都断言<b>具体看到了哪些用户名</b>，不是只比对数量——数量相同但
 * 集合不同（看错了人）与真的看对完全不可区分，而这正是数据权限最危险的失效模式
 * （CLAUDE.md 3.6 的断言纪律同样适用于这里，不只是字符集问题）。
 *
 * <p>部门与用户名按测试方法各自起独立前缀，不复用彼此的夹具——本类与其余 IT 共享
 * 同一个 MySQL 容器且不做事务回滚，用 {@code dept_id}/{@code create_by} 这类
 * 天然带隔离性的键可以放心用精确匹配断言，不用担心被其他用例的数据污染。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@DisplayName("数据权限（HTTP 端到端）")
class DataScopePermissionIT extends AbstractMySqlIntegrationTest {

    @Autowired TestRestTemplate rest;
    @Autowired SysDeptService deptService;
    @Autowired SysRoleService roleService;
    @Autowired SysUserService userService;
    @Autowired SysMenuService menuService;

    private static final String PASSWORD = "pwd-12345";

    @Test
    @DisplayName("本部门：只看得到同部门的人，看不到别的部门")
    void deptScopeSeesOnlySameDept() {
        Long deptA = createDept("scope-dept-A", 0L);
        Long deptB = createDept("scope-dept-B", 0L);
        Long roleId = createRole("ROLE_SCOPE_DEPT", DataScopeType.DEPT, List.of(menuIdOf("system:user:list")));

        createUserInDept("scope-dept-viewer", deptA, roleId);
        createUserInDept("scope-dept-peer", deptA, null);
        createUserInDept("scope-dept-outsider", deptB, null);

        Set<String> visible = usernamesVisibleTo(login("scope-dept-viewer", PASSWORD));

        assertThat(visible).contains("scope-dept-viewer", "scope-dept-peer");
        assertThat(visible).doesNotContain("scope-dept-outsider");
    }

    @Test
    @DisplayName("本部门及以下：看得到子部门，看不到无关部门")
    void deptAndChildScopeSeesDescendants() {
        Long parent = createDept("scope-tree-parent", 0L);
        Long child = createDept("scope-tree-child", parent);
        Long unrelated = createDept("scope-tree-unrelated", 0L);
        Long roleId = createRole("ROLE_SCOPE_TREE", DataScopeType.DEPT_AND_CHILD,
                List.of(menuIdOf("system:user:list")));

        createUserInDept("scope-tree-viewer", parent, roleId);
        createUserInDept("scope-tree-peer", parent, null);
        createUserInDept("scope-tree-child-user", child, null);
        createUserInDept("scope-tree-outsider", unrelated, null);

        Set<String> visible = usernamesVisibleTo(login("scope-tree-viewer", PASSWORD));

        assertThat(visible).contains("scope-tree-viewer", "scope-tree-peer", "scope-tree-child-user");
        assertThat(visible).doesNotContain("scope-tree-outsider");
    }

    @Test
    @DisplayName("自定义部门：只认角色配置的部门列表，与查看者自己在哪个部门无关")
    void customScopeIgnoresViewerOwnDept() {
        Long deptX = createDept("scope-custom-X", 0L);
        Long deptY = createDept("scope-custom-Y", 0L);
        Long deptZ = createDept("scope-custom-Z", 0L);
        Long roleId = createRole("ROLE_SCOPE_CUSTOM", DataScopeType.CUSTOM,
                List.of(menuIdOf("system:user:list")));
        roleService.assignDataScopeDepts(roleId, List.of(deptX, deptY));

        // 查看者自己在 Z 部门——Z 不在自定义列表里，查看者应该连自己都看不见
        createUserInDept("scope-custom-viewer", deptZ, roleId);
        createUserInDept("scope-custom-in-x", deptX, null);
        createUserInDept("scope-custom-in-y", deptY, null);
        createUserInDept("scope-custom-in-z", deptZ, null);

        Set<String> visible = usernamesVisibleTo(login("scope-custom-viewer", PASSWORD));

        assertThat(visible).containsExactlyInAnyOrder("scope-custom-in-x", "scope-custom-in-y");
    }

    @Test
    @DisplayName("自定义部门但一个部门都没配：看不到任何人，而不是退化成看得见一切")
    void customScopeWithoutAnyDeptSeesNobody() {
        Long dept = createDept("scope-custom-empty", 0L);
        Long roleId = createRole("ROLE_SCOPE_CUSTOM_EMPTY", DataScopeType.CUSTOM,
                List.of(menuIdOf("system:user:list")));
        // 故意不调用 assignDataScopeDepts

        createUserInDept("scope-custom-empty-viewer", dept, roleId);
        createUserInDept("scope-custom-empty-peer", dept, null);

        Set<String> visible = usernamesVisibleTo(login("scope-custom-empty-viewer", PASSWORD));

        assertThat(visible).isEmpty();
    }

    @Test
    @DisplayName("仅本人：只看得到自己创建的数据，看不到自己这条账号本身（那是管理员建的）")
    void selfScopeSeesOnlySelfCreated() {
        Long roleId = createRole("ROLE_SCOPE_SELF", DataScopeType.SELF,
                List.of(menuIdOf("system:user:list"), menuIdOf("system:user:add")));
        createUserInDept("scope-self-viewer", null, roleId);
        String viewerToken = login("scope-self-viewer", PASSWORD);

        // 用查看者自己的身份创建，create_by 才会是查看者自己的 userId
        createViaHttp(viewerToken, "scope-self-child-1");
        createViaHttp(viewerToken, "scope-self-child-2");

        Set<String> visible = usernamesVisibleTo(viewerToken);

        assertThat(visible).containsExactlyInAnyOrder("scope-self-child-1", "scope-self-child-2");
        assertThat(visible)
                .as("查看者自己的账号是管理员建的，create_by 不是查看者自己，仅本人档下不应可见")
                .doesNotContain("scope-self-viewer");
    }

    @Test
    @DisplayName("全部：不受部门限制——且这条能力来自角色的 data_scope 配置，不是代码里特判 ADMIN")
    void allScopeSeesEverythingRegardlessOfConfiguredRole() {
        Long deptP = createDept("scope-all-P", 0L);
        Long deptQ = createDept("scope-all-Q", 0L);
        // 刻意起一个与 ADMIN 无关的角色标识，证明 ALL 档是数据驱动的，不是硬编码 role_code
        Long roleId = createRole("ROLE_SCOPE_ALL_NOT_ADMIN", DataScopeType.ALL,
                List.of(menuIdOf("system:user:list")));

        createUserInDept("scope-all-viewer", deptP, roleId);
        createUserInDept("scope-all-in-p", deptP, null);
        createUserInDept("scope-all-in-q", deptQ, null);

        Set<String> visible = usernamesVisibleTo(login("scope-all-viewer", PASSWORD));

        assertThat(visible).contains("scope-all-viewer", "scope-all-in-p", "scope-all-in-q");
    }

    // ---------------------------------------------------------------- helpers

    private Long createDept(String name, Long parentId) {
        SysDept dept = new SysDept();
        dept.setDeptName(name);
        dept.setParentId(parentId);
        dept.setSort(0);
        dept.setStatus(1);
        return deptService.createDept(dept).getId();
    }

    private Long createRole(String code, DataScopeType scope, List<Long> menuIds) {
        SysRole role = new SysRole();
        role.setRoleCode(code);
        role.setRoleName(code);
        role.setSort(0);
        role.setDataScope(scope.getCode());
        roleService.save(role);
        roleService.assignMenus(role.getId(), menuIds);
        return role.getId();
    }

    private void createUserInDept(String username, Long deptId, Long roleId) {
        SysUser u = new SysUser();
        u.setUsername(username);
        u.setNickname(username);
        u.setDeptId(deptId);
        userService.createUser(u, PASSWORD, roleId == null ? List.of() : List.of(roleId));
    }

    private Long menuIdOf(String permCode) {
        return menuService.list().stream()
                .filter(m -> permCode.equals(m.getPermCode()))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("种子数据缺少权限点: " + permCode))
                .getId();
    }

    private String login(String username, String password) {
        ResponseEntity<Map> resp = rest.postForEntity("/api/auth/login",
                json(Map.of("type", "password", "username", username, "password", password)), Map.class);
        assertThat(resp.getStatusCode()).as("登录应成功: " + username).isEqualTo(HttpStatus.OK);
        return String.valueOf(((Map<?, ?>) resp.getBody().get("data")).get("token"));
    }

    private void createViaHttp(String token, String username) {
        ResponseEntity<Map> resp = rest.exchange("/api/system/user/with-password", HttpMethod.POST,
                new HttpEntity<>(Map.of(
                        "username", username,
                        "password", PASSWORD,
                        "nickname", username), bearer(token)),
                Map.class);
        assertThat(resp.getStatusCode()).as("前置条件：建号应成功: " + username).isEqualTo(HttpStatus.OK);
    }

    @SuppressWarnings("unchecked")
    private Set<String> usernamesVisibleTo(String token) {
        // size 取 PageQuery.MAX_SIZE，避免分页把断言要看的行截没了
        ResponseEntity<Map> resp = rest.exchange("/api/system/user?current=1&size=500", HttpMethod.GET,
                new HttpEntity<>(bearer(token)), Map.class);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        Map<String, Object> data = (Map<String, Object>) resp.getBody().get("data");
        List<Map<String, Object>> records = (List<Map<String, Object>>) data.get("records");
        return records.stream()
                .map(r -> String.valueOf(r.get("username")))
                .collect(Collectors.toSet());
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
