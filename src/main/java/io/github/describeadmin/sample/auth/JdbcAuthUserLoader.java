package io.github.describeadmin.sample.auth;

import io.github.describeadmin.security.api.AuthUser;
import io.github.describeadmin.security.api.AuthUserLoader;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * 基于框架参考 RBAC 表结构的 {@link AuthUserLoader} 实现。
 *
 * <p>业务方实现这个 SPI 即可接入框架的认证流程，框架不关心用户存在哪里。
 *
 * <p><b>SQL 写法遵循 CLAUDE.md 3.1 的红线</b>：不用 CTE、不用窗口函数，
 * 只用 JOIN + IN 子查询——这些在 MySQL 5.7 与国产化库上都可用。
 */
@Component
public class JdbcAuthUserLoader implements AuthUserLoader {

    private final JdbcTemplate jdbc;

    public JdbcAuthUserLoader(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Optional<AuthUser> loadByUsername(String username) {
        // 逻辑删除过滤由实现方负责——框架无法假设业务方的删除语义
        List<Object[]> rows = jdbc.query(
                "SELECT id, username, password, nickname, status "
                        + "FROM sys_user WHERE username = ? AND deleted = 0",
                (rs, i) -> new Object[]{
                        rs.getLong("id"), rs.getString("username"), rs.getString("password"),
                        rs.getString("nickname"), rs.getInt("status")
                }, username);

        if (rows.isEmpty()) {
            return Optional.empty();
        }
        Object[] r = rows.get(0);
        Long userId = (Long) r[0];

        return Optional.of(new AuthUser(
                userId, (String) r[1], (String) r[2], (String) r[3],
                ((Integer) r[4]) == 1,
                loadRoles(userId),
                loadPermissions(userId)));
    }

    private Set<String> loadRoles(Long userId) {
        return new LinkedHashSet<>(jdbc.queryForList(
                "SELECT r.role_code FROM sys_role r "
                        + "JOIN sys_user_role ur ON ur.role_id = r.id "
                        + "WHERE ur.user_id = ? AND r.deleted = 0 "
                        + "ORDER BY r.sort",
                String.class, userId));
    }

    private Set<String> loadPermissions(Long userId) {
        // 三表 JOIN + IN 子查询，5.7 安全子集内的写法
        return new LinkedHashSet<>(jdbc.queryForList(
                "SELECT DISTINCT m.perm_code FROM sys_menu m "
                        + "JOIN sys_role_menu rm ON rm.menu_id = m.id "
                        + "WHERE m.perm_code IS NOT NULL AND m.deleted = 0 "
                        + "AND rm.role_id IN ("
                        + "  SELECT ur.role_id FROM sys_user_role ur WHERE ur.user_id = ?"
                        + ")",
                String.class, userId));
    }
}
