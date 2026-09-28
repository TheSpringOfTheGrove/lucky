package com.hnz.luck5.module.system.service.auth;

import com.hnz.luck5.framework.common.biz.system.oauth2.OAuth2TokenCommonApi;
import com.hnz.luck5.framework.common.enums.UserTypeEnum;
import com.hnz.luck5.framework.common.pojo.CommonResult;
import com.hnz.luck5.framework.security.config.SecurityProperties;
import com.hnz.luck5.framework.security.core.filter.TokenAuthenticationFilter;
import com.hnz.luck5.framework.web.core.handler.GlobalExceptionHandler;
import com.hnz.luck5.framework.web.core.util.WebFrameworkUtils;
import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static com.hnz.luck5.framework.common.exception.util.ServiceExceptionUtil.exception;
import static com.hnz.luck5.module.system.enums.ErrorCodeConstants.AUTH_LOGIN_USER_EXPIRED;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.*;

class AdminExpirationTokenFilterTest {

    @Test
    void adminReceivesExpirationReasonInsteadOfGenericUnauthorized() throws Exception {
        MockHttpServletRequest request = request(UserTypeEnum.ADMIN);
        MockHttpServletResponse response = new MockHttpServletResponse();
        OAuth2TokenCommonApi api = mock(OAuth2TokenCommonApi.class);
        GlobalExceptionHandler handler = mock(GlobalExceptionHandler.class);
        FilterChain chain = mock(FilterChain.class);
        var error = exception(AUTH_LOGIN_USER_EXPIRED);
        when(api.checkAccessToken("expired-token")).thenThrow(error);
        doReturn(CommonResult.error(AUTH_LOGIN_USER_EXPIRED)).when(handler).allExceptionHandler(request, error);

        new TokenAuthenticationFilter(new SecurityProperties(), handler, api).doFilter(request, response, chain);
        assertTrue(response.getContentAsString().contains("1002000006"));
        verifyNoInteractions(chain);
    }

    @Test
    void publicRoomIgnoresResidualExpiredAdminToken() throws Exception {
        MockHttpServletRequest request = request(UserTypeEnum.MEMBER);
        MockHttpServletResponse response = new MockHttpServletResponse();
        OAuth2TokenCommonApi api = mock(OAuth2TokenCommonApi.class);
        GlobalExceptionHandler handler = mock(GlobalExceptionHandler.class);
        FilterChain chain = mock(FilterChain.class);
        when(api.checkAccessToken("expired-token")).thenThrow(exception(AUTH_LOGIN_USER_EXPIRED));

        new TokenAuthenticationFilter(new SecurityProperties(), handler, api).doFilter(request, response, chain);
        verify(chain).doFilter(request, response);
        verifyNoInteractions(handler);
    }

    private MockHttpServletRequest request(UserTypeEnum userType) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("Authorization", "Bearer expired-token");
        WebFrameworkUtils.setLoginUserType(request, userType.getValue());
        return request;
    }
}
