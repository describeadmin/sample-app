-- 业务表示例。语法基线同框架：MySQL 5.7 安全子集（CLAUDE.md 3.1）
--
-- 审计字段与 BaseEntity 一一对应，业务表都应包含这 6 个字段：
--   create_by / create_time / update_by / update_time / deleted / version
-- 它们由 AuditMetaObjectHandler 自动填充，业务代码不需要手工赋值。
CREATE TABLE IF NOT EXISTS biz_dept (
  id           BIGINT      NOT NULL AUTO_INCREMENT COMMENT '主键',
  dept_name    VARCHAR(64) NOT NULL                COMMENT '部门名称',
  parent_id    BIGINT      NOT NULL DEFAULT 0      COMMENT '父部门ID，0为根',
  sort         INT         NOT NULL DEFAULT 0      COMMENT '排序',
  create_by    BIGINT          NULL                COMMENT '创建人',
  create_time  DATETIME        NULL                COMMENT '创建时间',
  update_by    BIGINT          NULL                COMMENT '更新人',
  update_time  DATETIME        NULL                COMMENT '更新时间',
  deleted      TINYINT     NOT NULL DEFAULT 0      COMMENT '逻辑删除',
  version      INT         NOT NULL DEFAULT 0      COMMENT '乐观锁版本号',
  PRIMARY KEY (id),
  KEY idx_biz_dept_parent (parent_id)
) ENGINE=InnoDB
  DEFAULT CHARACTER SET utf8mb4
  COLLATE utf8mb4_general_ci
  COMMENT='部门';
