package com.hnz.luck5.module.member.convert.tag;

import com.hnz.luck5.framework.common.pojo.PageResult;
import com.hnz.luck5.module.member.controller.admin.tag.vo.MemberTagCreateReqVO;
import com.hnz.luck5.module.member.controller.admin.tag.vo.MemberTagRespVO;
import com.hnz.luck5.module.member.controller.admin.tag.vo.MemberTagUpdateReqVO;
import com.hnz.luck5.module.member.dal.dataobject.tag.MemberTagDO;
import org.mapstruct.Mapper;
import org.mapstruct.factory.Mappers;

import java.util.List;

/**
 * 会员标签 Convert
 *
 * @author 芋道源码
 */
@Mapper
public interface MemberTagConvert {

    MemberTagConvert INSTANCE = Mappers.getMapper(MemberTagConvert.class);

    MemberTagDO convert(MemberTagCreateReqVO bean);

    MemberTagDO convert(MemberTagUpdateReqVO bean);

    MemberTagRespVO convert(MemberTagDO bean);

    List<MemberTagRespVO> convertList(List<MemberTagDO> list);

    PageResult<MemberTagRespVO> convertPage(PageResult<MemberTagDO> page);

}
