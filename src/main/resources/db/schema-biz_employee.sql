-- 员工
--
-- 由 codegen 生成。语法基线：MySQL 5.7 安全子集（CLAUDE.md 3.1）
-- 审计字段与 BaseEntity 一一对应，请勿手工增删。
CREATE TABLE IF NOT EXISTS biz_employee (
  id             BIGINT      NOT NULL AUTO_INCREMENT COMMENT '主键',
  employee_name  VARCHAR(64) NOT NULL COMMENT '员工姓名',
  gender         VARCHAR(8)      NULL COMMENT '性别',
  id_card        VARCHAR(255)     NULL COMMENT '身份证号',
  mobile         VARCHAR(255)     NULL COMMENT '手机号码',
  address        VARCHAR(255)     NULL COMMENT '家庭住址',
  remark         TEXT            NULL COMMENT '备注',
  create_by      BIGINT          NULL COMMENT '创建人',
  create_time    DATETIME        NULL COMMENT '创建时间',
  update_by      BIGINT          NULL COMMENT '更新人',
  update_time    DATETIME        NULL COMMENT '更新时间',
  deleted        TINYINT     NOT NULL DEFAULT 0 COMMENT '逻辑删除：0未删 1已删',
  version        INT         NOT NULL DEFAULT 0 COMMENT '乐观锁版本号',
  PRIMARY KEY (id),
  KEY idx_biz_employee_employee_name (employee_name)
) ENGINE=InnoDB
  DEFAULT CHARACTER SET utf8mb4
  COLLATE utf8mb4_general_ci
  COMMENT='员工';
