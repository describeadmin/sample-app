package io.github.describeadmin.sample.project.controller;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import io.github.describeadmin.common.api.BizException;
import io.github.describeadmin.common.api.ResultCode;
import io.github.describeadmin.mybatis.api.BaseController;
import io.github.describeadmin.sample.project.entity.ProjectEntity;
import io.github.describeadmin.sample.project.mapper.ProjectMapper;
import io.github.describeadmin.sample.project.service.ProjectService;
import java.time.LocalDate;
import java.util.Map;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 项目。
 *
 * <p>由 codegen 生成。继承 {@code BaseController} 即获得
 * list / get / create / update / delete 五个标准端点，
 * 业务特有接口在此追加。
 *
 * <p>列表查询支持的条件：
 * <ul>
 *   <li>{@code projectName} —— 项目名称（like）</li>
 *   <li>{@code projectCode} —— 项目编号（eq）</li>
 *   <li>{@code ownerDeptId} —— 归口部门ID（eq）</li>
 *   <li>{@code startDate} —— 开始日期（range）</li>
 *   <li>{@code status} —— 状态（eq）</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/project")
public class ProjectController extends BaseController<ProjectService, ProjectMapper, ProjectEntity> {

    private final ProjectService projectService;

    public ProjectController(ProjectService projectService) {
        this.projectService = projectService;
    }

    @Override
    protected ProjectService getService() {
        return projectService;
    }

    /**
     * 列表查询的筛选条件。
     *
     * <p>空值不参与筛选，否则「不填任何条件」会退化成 {@code WHERE col = ''}，一条都查不出。
     * LIKE 一律右模糊，可走索引；不生成左模糊以免全表扫描。
     */
    @Override
    protected Wrapper<ProjectEntity> buildListWrapper(Map<String, String> params) {
        QueryWrapper<ProjectEntity> wrapper = new QueryWrapper<>();
        wrapper.likeRight(text(params, "projectName") != null, "project_name", text(params, "projectName"));
        wrapper.eq(text(params, "projectCode") != null, "project_code", text(params, "projectCode"));
        wrapper.eq(asLong(params, "ownerDeptId") != null, "owner_dept_id", asLong(params, "ownerDeptId"));
        wrapper.ge(asDate(params, "startDateStart") != null, "start_date", asDate(params, "startDateStart"));
        wrapper.le(asDate(params, "startDateEnd") != null, "start_date", asDate(params, "startDateEnd"));
        wrapper.eq(asInt(params, "status") != null, "status", asInt(params, "status"));
        return wrapper;
    }

    /** 取参数，空串按未填处理 —— 前端清空输入框后通常传的是空串而不是不传。 */
    private static String text(Map<String, String> params, String key) {
        String value = params.get(key);
        return value == null || value.isBlank() ? null : value.trim();
    }

    private static Integer asInt(Map<String, String> params, String key) {
        String value = text(params, key);
        if (value == null) {
            return null;
        }
        try {
            return Integer.valueOf(value);
        } catch (RuntimeException e) {
            throw new BizException(ResultCode.BAD_REQUEST,
                    "参数格式不正确: " + key + "=" + value);
        }
    }

    private static LocalDate asDate(Map<String, String> params, String key) {
        String value = text(params, key);
        if (value == null) {
            return null;
        }
        try {
            return LocalDate.parse(value);
        } catch (RuntimeException e) {
            throw new BizException(ResultCode.BAD_REQUEST,
                    "参数格式不正确: " + key + "=" + value);
        }
    }

    private static Long asLong(Map<String, String> params, String key) {
        String value = text(params, key);
        if (value == null) {
            return null;
        }
        try {
            return Long.valueOf(value);
        } catch (RuntimeException e) {
            throw new BizException(ResultCode.BAD_REQUEST,
                    "参数格式不正确: " + key + "=" + value);
        }
    }
}
