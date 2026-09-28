-- Format-only, idempotent correction of legacy accepted BET receipts.
-- Keep order state, ownership, funds, audit status and original message timestamps unchanged.
SET NAMES utf8mb4;
DROP TEMPORARY TABLE IF EXISTS lucky5_receipt_format;
CREATE TEMPORARY TABLE lucky5_receipt_format AS
SELECT id, update_time,
       TRIM(TRAILING CHAR(10) FROM REPLACE(
         REPLACE(reply, CONCAT(CHAR(13),CHAR(10)), CHAR(10)),
         CONCAT(CHAR(10),'已受理'), '')) AS body
FROM lucky5_message
WHERE command_type='BET' AND reply LIKE '%【户型审核成功】%'
  AND (reply LIKE CONCAT('%',CHAR(10),'已受理')
    OR reply LIKE CONCAT('%',CHAR(10),'已受理',CHAR(10),'%')
    OR reply LIKE CONCAT('%',CHAR(10),'已受理',CHAR(13),CHAR(10),'%'));
START TRANSACTION;
UPDATE lucky5_message m JOIN lucky5_receipt_format f ON m.id=f.id
SET m.reply=CONCAT(f.body, IF(f.body LIKE '%点击退码%' OR f.body LIKE '%已退码%',
                            '', CONCAT(CHAR(10),CHAR(10),'点击退码'))),
    m.update_time=f.update_time;
COMMIT;
DROP TEMPORARY TABLE IF EXISTS lucky5_receipt_format;
