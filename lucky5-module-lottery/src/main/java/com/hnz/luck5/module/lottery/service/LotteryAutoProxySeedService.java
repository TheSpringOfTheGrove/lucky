package com.hnz.luck5.module.lottery.service;

import cn.hutool.core.util.IdUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.hnz.luck5.framework.datapermission.core.util.DataPermissionUtils;
import com.hnz.luck5.framework.tenant.core.util.TenantUtils;
import com.hnz.luck5.module.lottery.dal.dataobject.MemberDO;
import com.hnz.luck5.module.lottery.dal.dataobject.PresetOrderDO;
import com.hnz.luck5.module.lottery.dal.mysql.MemberMapper;
import com.hnz.luck5.module.lottery.dal.mysql.PresetOrderMapper;
import com.hnz.luck5.module.system.dal.dataobject.user.AdminUserDO;
import com.hnz.luck5.module.system.dal.mysql.user.AdminUserMapper;
import com.hnz.luck5.module.system.service.permission.PermissionService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

import static com.hnz.luck5.framework.common.exception.util.ServiceExceptionUtil.exception;
import static com.hnz.luck5.module.lottery.enums.ErrorCodeConstants.OWNER_INITIALIZATION_NOT_ALLOWED;
import static com.hnz.luck5.module.lottery.enums.ErrorCodeConstants.RECORD_NOT_FOUND;

/** One-time owner seed, also callable separately to fill missing bot data. */
@Service
@RequiredArgsConstructor
public class LotteryAutoProxySeedService {

    private static final long ADMIN_USER_ID = 1L;
    private static final int TARGET_BOTS = 10;
    private static final BigDecimal ZERO = new BigDecimal("0.00");
    private static final String[] NAME_STARTS = {"阿", "小", "一", "海", "清", "乐", "晨", "明", "晓", "星", "子", "若"};
    private static final String[] NAME_ENDS = {"青", "云", "宁", "林", "澄", "航", "远", "川", "安", "琪", "杰", "峰", "雪", "夏", "宇", "南", "阳", "月", "凡", "然"};

    private final MemberMapper memberMapper;
    private final PresetOrderMapper presetOrderMapper;
    private final AdminUserMapper adminUserMapper;
    private final PermissionService permissionService;

    /** The caller owns the new-user transaction and has already inserted its initialization marker. */
    SeedResult seedCurrentTenant(Long tenantId, Long ownerUserId) {
        List<MemberDO> members = memberMapper.selectList(new LambdaQueryWrapper<MemberDO>()
                .eq(MemberDO::getUserId, ownerUserId));
        Set<String> usedNames = new HashSet<>();
        members.forEach(member -> usedNames.add(member.getName()));
        long existingBots = members.stream().filter(member -> "BOT".equals(member.getMemberType())
                || Boolean.TRUE.equals(member.getAutoProxy())).count();

        int presetsCreated = copyMissingPresets(tenantId, ownerUserId);
        int botsCreated = 0;
        while (existingBots + botsCreated < TARGET_BOTS) {
            MemberDO bot = new MemberDO();
            bot.setId(IdUtil.fastSimpleUUID());
            bot.setUserId(ownerUserId);
            bot.setName(randomAvailableName(usedNames));
            bot.setBalance(ZERO);
            bot.setStatus("离线");
            bot.setPartner("无");
            bot.setNormalRate(ZERO);
            bot.setLhhRate(ZERO);
            bot.setPartnerNormalRate(ZERO);
            bot.setPartnerLhhRate(ZERO);
            bot.setTag("普通");
            bot.setExternalNickname("");
            bot.setTotalBet(ZERO);
            bot.setProfitLoss(ZERO);
            bot.setMemberType("BOT");
            bot.setAutoProxy(true);
            bot.setAutoBetEnabled(true);
            bot.setAutoTopUpAmount(new BigDecimal("1000.00"));
            bot.setEatEnabled(false);
            bot.setSearchable(true);
            bot.setOpenId(IdUtil.fastSimpleUUID());
            bot.setFingerprint("");
            bot.setPrivateChat(false);
            bot.setWebOnly(false);
            bot.setBlueWhalePassword("");
            int avatar = LotteryMemberAvatar.randomAvatar();
            bot.setAvatar(avatar);
            bot.setAvatarPath(LotteryMemberAvatar.path(avatar));
            bot.setVersion(0);
            memberMapper.insert(bot);
            botsCreated++;
        }
        return new SeedResult(botsCreated, presetsCreated, existingBots + botsCreated);
    }

    /** Serializes repeated admin clicks without rerunning the unrelated owner configuration initializer. */
    @Transactional(rollbackFor = Exception.class)
    public SeedResult seedManually(Long tenantId, Long ownerUserId) {
        return TenantUtils.execute(tenantId, () -> DataPermissionUtils.executeIgnore(() -> {
            if (ADMIN_USER_ID == ownerUserId || permissionService.hasAnyRoles(ownerUserId, "super_admin")) {
                throw exception(OWNER_INITIALIZATION_NOT_ALLOWED);
            }
            AdminUserDO owner = adminUserMapper.selectOne(new LambdaQueryWrapper<AdminUserDO>()
                    .eq(AdminUserDO::getId, ownerUserId).last("LIMIT 1 FOR UPDATE"));
            if (owner == null) {
                throw exception(RECORD_NOT_FOUND);
            }
            return seedCurrentTenant(tenantId, ownerUserId);
        }));
    }

    private int copyMissingPresets(Long tenantId, Long ownerUserId) {
        List<PresetOrderDO> existing = presetOrderMapper.selectList(new LambdaQueryWrapper<PresetOrderDO>()
                .eq(PresetOrderDO::getUserId, ownerUserId));
        Set<String> existingIds = new HashSet<>();
        Set<String> existingContents = new HashSet<>();
        existing.forEach(item -> {
            existingIds.add(item.getId());
            existingContents.add(contentKey(item.getContent()));
        });
        List<PresetOrderDO> templates = presetOrderMapper.selectList(new LambdaQueryWrapper<PresetOrderDO>()
                .eq(PresetOrderDO::getUserId, ADMIN_USER_ID).orderByAsc(PresetOrderDO::getId));
        int created = 0;
        for (PresetOrderDO template : templates) {
            String content = contentKey(template.getContent());
            if (content.isEmpty()) continue;
            String id = UUID.nameUUIDFromBytes(("preset:" + tenantId + ":" + ownerUserId + ":" + template.getId())
                    .getBytes(StandardCharsets.UTF_8)).toString();
            if (existingIds.contains(id) || existingContents.contains(content)) continue;
            PresetOrderDO copy = new PresetOrderDO();
            copy.setId(id);
            copy.setUserId(ownerUserId);
            copy.setMember("-");
            copy.setContent(template.getContent());
            copy.setEnabled(template.getEnabled());
            presetOrderMapper.insert(copy);
            existingIds.add(id);
            created++;
        }
        return created;
    }

    private String randomAvailableName(Set<String> usedNames) {
        ThreadLocalRandom random = ThreadLocalRandom.current();
        for (int attempt = 0; attempt < 100; attempt++) {
            String name = NAME_STARTS[random.nextInt(NAME_STARTS.length)]
                    + NAME_ENDS[random.nextInt(NAME_ENDS.length)];
            if (usedNames.add(name)) return name;
        }
        String name;
        do {
            name = "小友" + IdUtil.fastSimpleUUID().substring(0, 8);
        } while (!usedNames.add(name));
        return name;
    }

    private String contentKey(String content) {
        return content == null ? "" : content.trim();
    }

    public record SeedResult(int botsCreated, int presetsCreated, long totalBots) {
    }
}
