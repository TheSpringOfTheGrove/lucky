INSERT IGNORE INTO `lucky5_odd`
  (`tenant_id`,`user_id`,`code`,`play`,`item`,`rate`,`secondary_rate`,`min_limit`,`max_limit`,`status`,`creator`,`updater`)
SELECT `tenant_id`,`user_id`,'regex5d2','五位二定',`item`,`rate`,`secondary_rate`,`min_limit`,`max_limit`,`status`,
       `creator`,`updater`
FROM `lucky5_odd`
WHERE `code`='regex2d' AND `deleted`=b'0';
