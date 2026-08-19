package io.github.describeadmin.sample.project.service;

import io.github.describeadmin.mybatis.api.BaseService;
import io.github.describeadmin.sample.project.entity.ProjectEntity;
import io.github.describeadmin.sample.project.mapper.ProjectMapper;
import org.springframework.stereotype.Service;

@Service
public class ProjectService extends BaseService<ProjectMapper, ProjectEntity> {
}
