-- MySQL 8；先暂停所有写入并备份，使用 mysql 客户端执行，禁止使用 --force。
-- 先校验再做 DDL：冲突仅报告，不改名。已执行过的迁移不应重复执行。
SET NAMES utf8mb4;
DROP PROCEDURE IF EXISTS p11_preflight;
DELIMITER $$
CREATE PROCEDURE p11_preflight()
BEGIN
  -- 旧版官方社区身份必须是惰性创建的占位资料，不能把真实 QQ 用户绑定到官方账号。
  IF EXISTS (
    SELECT 1 FROM app_user u JOIN admin_user a ON a.username=u.qq_number AND a.user_type=1
    WHERE u.email <> CONCAT(a.username,'@official.local') OR u.password IS NOT NULL
  ) THEN
    SELECT u.id AS app_user_id, a.id AS admin_user_id, u.qq_number, u.email
    FROM app_user u JOIN admin_user a ON a.username=u.qq_number AND a.user_type=1
    WHERE u.email <> CONCAT(a.username,'@official.local') OR u.password IS NOT NULL;
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='Official identity conflict: review listed rows before migration';
  END IF;
  IF EXISTS (
    SELECT 1 FROM app_user u LEFT JOIN admin_user a ON a.username=u.qq_number AND a.user_type=1
    WHERE a.id IS NULL AND u.qq_number NOT REGEXP '^[0-9]{5,11}$'
  ) THEN
    SELECT u.id, u.qq_number FROM app_user u LEFT JOIN admin_user a ON a.username=u.qq_number AND a.user_type=1
    WHERE a.id IS NULL AND u.qq_number NOT REGEXP '^[0-9]{5,11}$';
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='Unbound non-QQ community identity: review listed rows';
  END IF;
  IF EXISTS (
    SELECT account FROM (
      SELECT LOWER(TRIM(username)) COLLATE utf8mb4_unicode_ci AS account FROM admin_user
      UNION ALL
      SELECT LOWER(TRIM(u.qq_number)) COLLATE utf8mb4_unicode_ci FROM app_user u
      LEFT JOIN admin_user a ON a.username=u.qq_number AND a.user_type=1 WHERE a.id IS NULL
    ) names GROUP BY account HAVING COUNT(*)>1 OR account=''
  ) THEN
    SELECT account, GROUP_CONCAT(owner ORDER BY owner) AS conflicting_owners FROM (
      SELECT LOWER(TRIM(username)) COLLATE utf8mb4_unicode_ci AS account, CONCAT('admin:',id) AS owner FROM admin_user
      UNION ALL
      SELECT LOWER(TRIM(u.qq_number)) COLLATE utf8mb4_unicode_ci, CONCAT('app:',u.id) FROM app_user u
      LEFT JOIN admin_user a ON a.username=u.qq_number AND a.user_type=1 WHERE a.id IS NULL
    ) names GROUP BY account HAVING COUNT(*)>1 OR account='';
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='Global login account conflict: review listed rows before migration';
  END IF;
END$$
DELIMITER ;
CALL p11_preflight();
DROP PROCEDURE p11_preflight;

ALTER TABLE app_user
  ADD COLUMN username VARCHAR(20) NULL COMMENT '自定义账号，NULL时用QQ号',
  ADD COLUMN official_admin_id BIGINT NULL COMMENT '官方社区身份固定关联',
  ADD UNIQUE KEY uk_official_admin (official_admin_id),
  MODIFY COLUMN qq_number VARCHAR(11) NULL COMMENT '普通用户QQ号；官方社区身份为NULL';

CREATE TABLE login_account (
  id BIGINT NOT NULL AUTO_INCREMENT,
  account VARCHAR(50) COLLATE utf8mb4_unicode_ci NOT NULL COMMENT 'trim后的小写登录名',
  owner_type TINYINT NOT NULL COMMENT '0 app_user，1 admin_user',
  owner_id BIGINT NOT NULL,
  PRIMARY KEY(id),
  UNIQUE KEY uk_login_account(account),
  UNIQUE KEY uk_login_owner(owner_type,owner_id),
  CONSTRAINT chk_login_owner CHECK(owner_type IN (0,1))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

START TRANSACTION;
UPDATE app_user u JOIN admin_user a ON a.username=u.qq_number AND a.user_type=1
SET u.official_admin_id=a.id, u.qq_number=NULL;
INSERT INTO login_account(account,owner_type,owner_id)
SELECT LOWER(TRIM(username)),1,id FROM admin_user;
INSERT INTO login_account(account,owner_type,owner_id)
SELECT LOWER(TRIM(qq_number)),0,id FROM app_user WHERE official_admin_id IS NULL;
COMMIT;
