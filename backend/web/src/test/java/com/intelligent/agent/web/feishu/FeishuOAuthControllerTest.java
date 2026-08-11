package com.intelligent.agent.web.feishu;

import com.intelligent.agent.web.service.FeishuOAuthService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.*;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class FeishuOAuthControllerTest {

    @Mock FeishuOAuthService feishuOAuthService;

    private FeishuOAuthController controller;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        controller = new FeishuOAuthController();
        ReflectionTestUtils.setField(controller, "feishuOAuthService", feishuOAuthService);
    }

    @Test
    void callbackUsesLocalOAuthService() {
        when(feishuOAuthService.callback("c123", "s456")).thenReturn("<html>授权成功</html>");

        MockHttpServletRequest req = new MockHttpServletRequest();
        req.setParameter("code", "c123");
        req.setParameter("state", "s456");

        ResponseEntity<String> resp = controller.oauthCallback(req);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(resp.getBody()).contains("授权成功");
        verify(feishuOAuthService).callback("c123", "s456");
    }

    @Test
    void callbackReturnsDeniedOnErrorParam() {
        MockHttpServletRequest req = new MockHttpServletRequest();
        req.setParameter("error", "access_denied");
        req.setParameter("state", "s456");

        ResponseEntity<String> resp = controller.oauthCallback(req);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(resp.getBody()).contains("拒绝授权");
        verifyNoInteractions(feishuOAuthService);
    }
}
