package io.github.describeadmin.sample.project.controller;

import io.github.describeadmin.mybatis.api.BaseController;
import io.github.describeadmin.sample.project.entity.ProjectEntity;
import io.github.describeadmin.sample.project.mapper.ProjectMapper;
import io.github.describeadmin.sample.project.service.ProjectService;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/project")
public class ProjectController
        extends BaseController<ProjectService, ProjectMapper, ProjectEntity> {

    private final ProjectService projectService;

    public ProjectController(ProjectService projectService) {
        this.projectService = projectService;
    }

    @Override
    protected ProjectService getService() {
        return projectService;
    }
}
