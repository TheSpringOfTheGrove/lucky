package com.hnz.luck5.module.lottery.service;

import com.hnz.luck5.module.lottery.dal.dataobject.MemberDO;
import com.hnz.luck5.module.lottery.dal.dataobject.PresetOrderDO;
import com.hnz.luck5.module.lottery.dal.mysql.MemberMapper;
import com.hnz.luck5.module.lottery.dal.mysql.PresetOrderMapper;
import com.hnz.luck5.module.system.dal.mysql.user.AdminUserMapper;
import com.hnz.luck5.module.system.service.permission.PermissionService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class LotteryAutoProxySeedServiceTest {

    @Mock private MemberMapper memberMapper;
    @Mock private PresetOrderMapper presetOrderMapper;
    @Mock private AdminUserMapper adminUserMapper;
    @Mock private PermissionService permissionService;

    @InjectMocks private LotteryAutoProxySeedService service;

    @Test
    void fillsTenBotsAndCopiesAdminPresetsWithoutChangingExistingMembers() {
        MemberDO real = member("real", "阿青", false);
        MemberDO bot = member("bot", "小云", true);
        when(memberMapper.selectList(any())).thenReturn(List.of(real, bot));
        PresetOrderDO template = preset("admin-preset", "千1各1");
        template.setEnabled(false);
        when(presetOrderMapper.selectList(any())).thenReturn(List.of(), List.of(template));

        LotteryAutoProxySeedService.SeedResult result = service.seedCurrentTenant(1L, 200L);

        assertThat(result.botsCreated()).isEqualTo(9);
        assertThat(result.presetsCreated()).isEqualTo(1);
        assertThat(result.totalBots()).isEqualTo(10);
        ArgumentCaptor<MemberDO> bots = ArgumentCaptor.forClass(MemberDO.class);
        verify(memberMapper, times(9)).insert(bots.capture());
        Set<String> names = bots.getAllValues().stream().map(MemberDO::getName).collect(Collectors.toSet());
        assertThat(names).hasSize(9).doesNotContain("阿青", "小云");
        assertThat(bots.getAllValues()).allSatisfy(created -> {
            assertThat(created.getUserId()).isEqualTo(200L);
            assertThat(created.getMemberType()).isEqualTo("BOT");
            assertThat(created.getAutoProxy()).isTrue();
            assertThat(created.getAutoBetEnabled()).isTrue();
            assertThat(created.getBalance()).isZero();
            assertThat(created.getOpenId()).isNotBlank();
            assertThat(created.getAvatarPath()).startsWith("player-avatars/");
        });
        ArgumentCaptor<PresetOrderDO> presets = ArgumentCaptor.forClass(PresetOrderDO.class);
        verify(presetOrderMapper).insert(presets.capture());
        assertThat(presets.getValue().getUserId()).isEqualTo(200L);
        assertThat(presets.getValue().getContent()).isEqualTo("千1各1");
        assertThat(presets.getValue().getEnabled()).isFalse();
        verify(memberMapper, never()).updateById(any(MemberDO.class));
    }

    @Test
    void secondRunDoesNotDuplicateBotsOrPresets() {
        List<MemberDO> existing = new ArrayList<>();
        for (int i = 0; i < 10; i++) existing.add(member("bot-" + i, "玩家" + i, true));
        when(memberMapper.selectList(any())).thenReturn(existing);
        when(presetOrderMapper.selectList(any())).thenReturn(
                List.of(preset("own-preset", "千1各1")), List.of(preset("admin-preset", "千1各1")));

        LotteryAutoProxySeedService.SeedResult result = service.seedCurrentTenant(1L, 200L);

        assertThat(result.botsCreated()).isZero();
        assertThat(result.presetsCreated()).isZero();
        assertThat(result.totalBots()).isEqualTo(10);
        verify(memberMapper, never()).insert(any(MemberDO.class));
        verify(presetOrderMapper, never()).insert(any(PresetOrderDO.class));
    }

    @Test
    void distinctAdminPresetsWithSameContentRemainDistinctOnFirstCopy() {
        when(memberMapper.selectList(any())).thenReturn(List.of());
        when(presetOrderMapper.selectList(any())).thenReturn(List.of(),
                List.of(preset("source-a", "千1各1"), preset("source-b", "千1各1")));

        LotteryAutoProxySeedService.SeedResult result = service.seedCurrentTenant(1L, 200L);

        assertThat(result.presetsCreated()).isEqualTo(2);
        ArgumentCaptor<PresetOrderDO> presets = ArgumentCaptor.forClass(PresetOrderDO.class);
        verify(presetOrderMapper, times(2)).insert(presets.capture());
        assertThat(presets.getAllValues()).extracting(PresetOrderDO::getId).doesNotHaveDuplicates();
    }

    private MemberDO member(String id, String name, boolean bot) {
        MemberDO item = new MemberDO();
        item.setId(id);
        item.setName(name);
        item.setMemberType(bot ? "BOT" : "REAL");
        item.setAutoProxy(bot);
        return item;
    }

    private PresetOrderDO preset(String id, String content) {
        PresetOrderDO item = new PresetOrderDO();
        item.setId(id);
        item.setContent(content);
        return item;
    }
}
