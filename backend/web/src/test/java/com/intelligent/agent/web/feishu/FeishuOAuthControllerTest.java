package com.intelligent.agent.web.feishu;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.intelligent.agent.web.service.PythonProxyService;
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

    @Mock PythonProxyService proxy;

    private FeishuOAuthController controller;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        controller = new FeishuOAuthController();
        // proxy and objectMapper are protected in AbstractProxyController (different package)
        ReflectionTestUtils.setField(controller, "proxy", proxy);
        ReflectionTestUtils.setField(controller, "objectMapper", new ObjectMapper());
    }

    @Test
    void callbackProxiesToPythonWithoutJwt() {
        when(proxy.get(contains("/api/feishu/oauth/callback")))
                .thenReturn(ResponseEntity.ok("<html>授权成功</html>"));

        MockHttpServletRequest req = new MockHttpServletRequest();
        req.setQueryString("code=c123&state=s456");

        ResponseEntity<String> resp = controller.oauthCallback(req);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(resp.getBody()).contains("授权成功");
        verify(proxy).get("/api/feishu/oauth/callback?code=c123&state=s456");
    }

    @Test
    void callbackPassesErrorParamThrough() {
        when(proxy.get(contains("error=access_denied")))
                .thenReturn(ResponseEntity.ok("<html>拒绝</html>"));

        MockHttpServletRequest req = new MockHttpServletRequest();
        req.setQueryString("error=access_denied&state=s456");

        ResponseEntity<String> resp = controller.oauthCallback(req);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(resp.getBody()).contains("拒绝");
        verify(proxy).get("/api/feishu/oauth/callback?error=access_denied&state=s456");
    }
}
