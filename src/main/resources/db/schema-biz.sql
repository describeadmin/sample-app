-- 业务表。语法基线同框架：MySQL 5.7 安全子集（CLAUDE.md 3.1）
CREATE TABLE IF NOT EXISTS biz_project (
  id            BIGINT        NOT NULL AUTO_INCREMENT COMMENT '主键',
  project_name  VARCHAR(128)  NOT NULL                COMMENT '项目名称',
  project_code  VARCHAR(64)       NULL                COMMENT '项目编号',
  owner_dept_id BIGINT            NULL                COMMENT '归口部门ID（关联 sys_dept）',
  budget        DECIMAL(18,2)     NULL                COMMENT '预算金额',
  start_date    DATE              NULL                COMMENT '开始日期',
  status        TINYINT       NOT NULL DEFAULT 1      COMMENT '状态',
  create_by     BIGINT            NULL                COMMENT '创建人',
  create_time   DATETIME          NULL                COMMENT '创建时间',
  update_by     BIGINT            NULL                COMMENT '更新人',
  update_time   DATETIME          NULL                COMMENT '更新时间',
  deleted       TINYINT       NOT NULL DEFAULT 0      COMMENT '逻辑删除',
  version       INT           NOT NULL DEFAULT 0      COMMENT '乐观锁版本号',
  PRIMARY KEY (id),
  KEY idx_biz_project_dept (owner_dept_id)
) ENGINE=InnoDB
  DEFAULT CHARACTER SET utf8mb4
  COLLATE utf8mb4_general_ci
  COMMENT='项目';
