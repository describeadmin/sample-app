package io.github.describeadmin.sample.dept.controller;

import io.github.describeadmin.mybatis.api.BaseController;
import io.github.describeadmin.sample.dept.entity.DeptEntity;
import io.github.describeadmin.sample.dept.mapper.DeptMapper;
import io.github.describeadmin.sample.dept.service.DeptService;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 部门 Controller。
 *
 * <p>继承 {@link BaseController} 即获得 list / get / create / update / delete 五个端点，
 * 本文件只需提供 Service 实例。
 */
@RestController
@RequestMapping("/api/dept")
public class DeptController extends BaseController<DeptService, DeptMapper, DeptEntity> {

    private final DeptService deptService;

    public DeptController(DeptService deptService) {
        this.deptService = deptService;
    }

    @Override
    protected DeptService getService() {
        return deptService;
    }
}
