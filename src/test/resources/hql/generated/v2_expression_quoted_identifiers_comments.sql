-- Semicolons in comments and strings must not split this statement; SELECT 'ignore;me'.
/* a comment containing ; and nested-query-looking text (SELECT id FROM missing) */
INSERT INTO `dwd`.`result` (`id`, `name`)
SELECT u.`id`, concat(u.`name`, ';中文')
FROM `ods`.`users` u;
