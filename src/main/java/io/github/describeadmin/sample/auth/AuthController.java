package io.github.describeadmin.sample.auth;

import io.github.describeadmin.common.api.Result;
import io.github.describeadmin.security.api.AuthRequest;
import io.github.describeadmin.security.api.LoginUser;
import io.github.describeadmin.security.core.AuthProviderRegistry;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * 认证接口。
 */
@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private final AuthProviderRegistry registry;

    public AuthController(AuthProviderRegistry registry) {
        this.registry = registry;
    }

    /**
     * 当前项目启用了哪些登录方式。
     *
     * <p>前端登录页调用本接口动态渲染，<b>不要把登录方式的按钮硬编码在页面里</b>——
     * 这是插件化在前端侧成立的关键（见 develop_plan.md 3.2）。
     * 引入了浙政钉插件，这里就会多出一项，前后端都不需要改代码。
     */
    @GetMapping("/providers")
    public Result<List<String>> providers() {
        return Result.ok(registry.availableTypes());
    }

    /**
     * 登录。
     *
     * <p>请求体形如 {@code {"type":"password","username":"admin","password":"..."}}，
     * 除 {@code type} 外的字段透传给对应的 {@code AuthProvider}，
     * 因此新增登录方式无需改动本接口的签名。
     */
    @PostMapping("/login")
    public Result<LoginUser> login(@RequestBody Map<String, Object> body) {
        String type = String.valueOf(body.getOrDefault("type", "password"));
        return Result.ok(registry.authenticate(new AuthRequest(type, body)));
    }
}
