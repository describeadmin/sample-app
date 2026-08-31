package io.github.describeadmin.sample.employee.controller;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import io.github.describeadmin.common.api.BizException;
import io.github.describeadmin.common.api.ResultCode;
import io.github.describeadmin.mybatis.api.BaseController;
import io.github.describeadmin.sample.employee.entity.EmployeeEntity;
import io.github.describeadmin.sample.employee.mapper.EmployeeMapper;
import io.github.describeadmin.sample.employee.service.EmployeeService;
import java.util.Map;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 员工。
 *
 * <p>由 codegen 生成。继承 {@code BaseController} 即获得
 * list / get / create / update / delete 五个标准端点，
 * 业务特有接口在此追加。
 *
 * <p>列表查询支持的条件：
 * <ul>
 *   <li>{@code employeeName} —— 员工姓名（like）</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/employee")
public class EmployeeController extends BaseController<EmployeeService, EmployeeMapper, EmployeeEntity> {

    private final EmployeeService employeeService;

    public EmployeeController(EmployeeService employeeService) {
        this.employeeService = employeeService;
    }

    @Override
    protected EmployeeService getService() {
        return employeeService;
    }

    /**
     * 权限点前缀。
     *
     * <p>显式覆写，不依赖 {@code BaseController} 从 {@code @RequestMapping} 的推导：
     * apiPrefix 默认会把模块名里的下划线换成连字符（{@code my_module} →
     * {@code /api/my-module}），而 {@code menu-*.sql} 登记的权限点用的是模块名原样
     * （{@code my_module:list}）。靠推导会得到 {@code my-module:list}，
     * 与授权数据对不上，表现为<b>连 ADMIN 都被 403</b>——
     * 而错误信息里没有任何东西指向"权限点前缀拼错了"。
     */
    @Override
    public String permPrefix() {
        return "employee";
    }

    /**
     * 列表查询的筛选条件。
     *
     * <p>空值不参与筛选，否则「不填任何条件」会退化成 {@code WHERE col = ''}，一条都查不出。
     * LIKE 一律右模糊，可走索引；不生成左模糊以免全表扫描。
     */
    @Override
    protected Wrapper<EmployeeEntity> buildListWrapper(Map<String, String> params) {
        QueryWrapper<EmployeeEntity> wrapper = new QueryWrapper<>();
        wrapper.likeRight(text(params, "employeeName") != null, "employee_name", text(params, "employeeName"));
        return wrapper;
    }

    /** 取参数，空串按未填处理 —— 前端清空输入框后通常传的是空串而不是不传。 */
    private static String text(Map<String, String> params, String key) {
        String value = params.get(key);
        return value == null || value.isBlank() ? null : value.trim();
    }
}
