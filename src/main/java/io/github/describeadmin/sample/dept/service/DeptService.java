package io.github.describeadmin.sample.dept.service;

import io.github.describeadmin.mybatis.api.BaseService;
import io.github.describeadmin.sample.dept.entity.DeptEntity;
import io.github.describeadmin.sample.dept.mapper.DeptMapper;
import org.springframework.stereotype.Service;

/** 部门 Service。CRUD 与分页由 BaseService 提供，这里只写业务特有方法。 */
@Service
public class DeptService extends BaseService<DeptMapper, DeptEntity> {
}
