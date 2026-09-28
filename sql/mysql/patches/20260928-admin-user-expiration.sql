-- 账号期限幂等升级，不覆盖已设置的期限、不改密码/状态/租户/权限。
SET NAMES utf8mb4;
SET @lucky5_user_expiration_ddl = IF(
  (SELECT COUNT(*) FROM information_schema.columns
   WHERE table_schema=DATABASE() AND table_name='system_users' AND column_name='expire_time')=0,
  'ALTER TABLE `system_users` ADD COLUMN `expire_time` datetime NOT NULL DEFAULT ''2099-12-31 23:59:59'' COMMENT ''后台账号到期时间'' AFTER `status`',
  'SELECT 1'
);
PREPARE lucky5_user_expiration_stmt FROM @lucky5_user_expiration_ddl;
EXECUTE lucky5_user_expiration_stmt;
DEALLOCATE PREPARE lucky5_user_expiration_stmt;
