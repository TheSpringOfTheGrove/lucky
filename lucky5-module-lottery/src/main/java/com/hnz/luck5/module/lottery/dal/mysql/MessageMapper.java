package com.hnz.luck5.module.lottery.dal.mysql;

import com.hnz.luck5.framework.mybatis.core.mapper.BaseMapperX;
import com.hnz.luck5.module.lottery.dal.dataobject.MessageDO;
import lombok.Data;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Result;
import org.apache.ibatis.annotations.Results;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface MessageMapper extends BaseMapperX<MessageDO> {

    /** Page the visible robot/member rows, not just the underlying message records. */
    @Select("""
            <script>
            SELECT m.id AS message_id, row_kind.kind AS row_kind
            FROM lucky5_message m
            JOIN (SELECT 0 AS kind UNION ALL SELECT 1 AS kind) row_kind
            WHERE m.tenant_id = #{tenantId} AND m.user_id = #{userId} AND m.deleted = 0
              <if test="period != null">AND m.period LIKE CONCAT('%', #{period}, '%')</if>
              AND (
                (row_kind.kind = 0 AND (TRIM(m.reply) &lt;&gt; '' OR m.command_type IN ('DRAW', 'DRAW_RESULT', 'LOTTERY_RESULT'))
                  <if test="content != null">AND LOCATE(LOWER(#{content}), LOWER(m.reply)) &gt; 0</if>
                  <if test="nickname != null and !nicknameCanMatchRobot">AND LOCATE(LOWER(#{nickname}), LOWER(m.member)) &gt; 0</if>)
                OR
                (row_kind.kind = 1 AND TRIM(m.content) &lt;&gt; '' AND m.command_type NOT IN ('DRAW', 'DRAW_RESULT', 'LOTTERY_RESULT')
                  <if test="content != null">AND LOCATE(LOWER(#{content}), LOWER(m.content)) &gt; 0</if>
                  <if test="nickname != null">AND LOCATE(LOWER(#{nickname}), LOWER(m.member)) &gt; 0</if>)
              )
            ORDER BY m.create_time DESC, m.id DESC, row_kind.kind ASC
            LIMIT #{pageSize} OFFSET #{offset}
            </script>
            """)
    @Results({@Result(property = "messageId", column = "message_id"),
            @Result(property = "kind", column = "row_kind")})
    List<MessagePageEntry> selectDisplayPage(@Param("tenantId") Long tenantId, @Param("userId") Long userId,
                                             @Param("period") String period, @Param("content") String content,
                                             @Param("nickname") String nickname,
                                             @Param("nicknameCanMatchRobot") boolean nicknameCanMatchRobot,
                                             @Param("offset") long offset, @Param("pageSize") int pageSize);

    @Select("""
            <script>
            SELECT COUNT(*)
            FROM lucky5_message m
            JOIN (SELECT 0 AS kind UNION ALL SELECT 1 AS kind) row_kind
            WHERE m.tenant_id = #{tenantId} AND m.user_id = #{userId} AND m.deleted = 0
              <if test="period != null">AND m.period LIKE CONCAT('%', #{period}, '%')</if>
              AND (
                (row_kind.kind = 0 AND (TRIM(m.reply) &lt;&gt; '' OR m.command_type IN ('DRAW', 'DRAW_RESULT', 'LOTTERY_RESULT'))
                  <if test="content != null">AND LOCATE(LOWER(#{content}), LOWER(m.reply)) &gt; 0</if>
                  <if test="nickname != null and !nicknameCanMatchRobot">AND LOCATE(LOWER(#{nickname}), LOWER(m.member)) &gt; 0</if>)
                OR
                (row_kind.kind = 1 AND TRIM(m.content) &lt;&gt; '' AND m.command_type NOT IN ('DRAW', 'DRAW_RESULT', 'LOTTERY_RESULT')
                  <if test="content != null">AND LOCATE(LOWER(#{content}), LOWER(m.content)) &gt; 0</if>
                  <if test="nickname != null">AND LOCATE(LOWER(#{nickname}), LOWER(m.member)) &gt; 0</if>)
              )
            </script>
            """)
    long countDisplayRows(@Param("tenantId") Long tenantId, @Param("userId") Long userId,
                          @Param("period") String period, @Param("content") String content,
                          @Param("nickname") String nickname,
                          @Param("nicknameCanMatchRobot") boolean nicknameCanMatchRobot);

    @Data
    class MessagePageEntry {
        private Long messageId;
        /** Robot rows sort before the member row from the same message. */
        private Integer kind;
    }
}

