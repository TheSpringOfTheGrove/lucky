package com.hnz.luck5.module.pay.convert.wallet;

import com.hnz.luck5.framework.common.pojo.PageResult;
import com.hnz.luck5.module.pay.controller.admin.wallet.vo.transaction.PayWalletTransactionRespVO;
import com.hnz.luck5.module.pay.controller.app.wallet.vo.transaction.AppPayWalletTransactionRespVO;
import com.hnz.luck5.module.pay.dal.dataobject.wallet.PayWalletTransactionDO;
import com.hnz.luck5.module.pay.service.wallet.bo.WalletTransactionCreateReqBO;
import org.mapstruct.Mapper;
import org.mapstruct.factory.Mappers;

@Mapper
public interface PayWalletTransactionConvert {

    PayWalletTransactionConvert INSTANCE = Mappers.getMapper(PayWalletTransactionConvert.class);

    PageResult<PayWalletTransactionRespVO> convertPage2(PageResult<PayWalletTransactionDO> page);

    PayWalletTransactionDO convert(WalletTransactionCreateReqBO bean);

}
