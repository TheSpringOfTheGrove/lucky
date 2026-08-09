package com.hnz.luck5.module.pay.convert.channel;

import com.hnz.luck5.framework.common.pojo.PageResult;
import com.hnz.luck5.framework.common.util.collection.CollectionUtils;
import com.hnz.luck5.module.pay.controller.admin.channel.vo.PayChannelCreateReqVO;
import com.hnz.luck5.module.pay.controller.admin.channel.vo.PayChannelRespVO;
import com.hnz.luck5.module.pay.controller.admin.channel.vo.PayChannelUpdateReqVO;
import com.hnz.luck5.module.pay.dal.dataobject.channel.PayChannelDO;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.factory.Mappers;

import java.util.List;

@Mapper
public interface PayChannelConvert {

    PayChannelConvert INSTANCE = Mappers.getMapper(PayChannelConvert.class);

    @Mapping(target = "config",ignore = true)
    PayChannelDO convert(PayChannelCreateReqVO bean);

    @Mapping(target = "config",ignore = true)
    PayChannelDO convert(PayChannelUpdateReqVO bean);

    @Mapping(target = "config",expression = "java(com.hnz.luck5.framework.common.util.json.JsonUtils.toJsonString(bean.getConfig()))")
    PayChannelRespVO convert(PayChannelDO bean);

    default List<PayChannelRespVO> convertList(List<PayChannelDO> list) {
        // 列表不下发 config（密钥/证书），详情接口再返回完整配置
        return CollectionUtils.convertList(list, channel -> {
            PayChannelRespVO vo = convert(channel);
            vo.setConfig(null);
            return vo;
        });
    }

    PageResult<PayChannelRespVO> convertPage(PageResult<PayChannelDO> page);

}
