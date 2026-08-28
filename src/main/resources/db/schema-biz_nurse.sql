-- 护理员
--
-- 由 codegen 生成，之后手工补了 mobile_idx / id_card_idx 两列（盲索引，framework-crypto-starter）。
-- 语法基线：MySQL 5.7 安全子集（CLAUDE.md 3.1）
-- 审计字段与 BaseEntity 一一对应，请勿手工增删。
--
-- ⚠️ mobile / id_card 存的是 AES-256-GCM 密文（VARCHAR(255)，为 Base64 膨胀预留）；
--    明文精确搜索走 mobile_idx / id_card_idx（HmacSHA256 十六进制，固定 64 字符），
--    这两列由 BlindIndexInnerInterceptor 自动填充，各建一个普通 KEY 支撑等值查询。
CREATE TABLE IF NOT EXISTS biz_nurse (
  id             BIGINT      NOT NULL AUTO_INCREMENT COMMENT '主键',
  nurse_name     VARCHAR(64) NOT NULL COMMENT '护理员姓名',
  mobile         VARCHAR(255)     NULL COMMENT '手机号码（密文）',
  mobile_idx     VARCHAR(64)      NULL COMMENT '手机号码盲索引',
  id_card        VARCHAR(255)     NULL COMMENT '身份证号码（密文）',
  id_card_idx    VARCHAR(64)      NULL COMMENT '身份证号码盲索引',
  photo          VARCHAR(512)     NULL COMMENT '护理员照片',
  birth_date     DATE            NULL COMMENT '出生年月',
  employment_type VARCHAR(32)     NULL COMMENT '从业类型',
  create_by      BIGINT          NULL COMMENT '创建人',
  create_time    DATETIME        NULL COMMENT '创建时间',
  update_by      BIGINT          NULL COMMENT '更新人',
  update_time    DATETIME        NULL COMMENT '更新时间',
  deleted        TINYINT     NOT NULL DEFAULT 0 COMMENT '逻辑删除：0未删 1已删',
  version        INT         NOT NULL DEFAULT 0 COMMENT '乐观锁版本号',
  PRIMARY KEY (id),
  KEY idx_biz_nurse_nurse_name (nurse_name),
  KEY idx_biz_nurse_employment_type (employment_type),
  KEY idx_biz_nurse_mobile_idx (mobile_idx),
  KEY idx_biz_nurse_id_card_idx (id_card_idx)
) ENGINE=InnoDB
  DEFAULT CHARACTER SET utf8mb4
  COLLATE utf8mb4_general_ci
  COMMENT='护理员';
