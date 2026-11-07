package com.ruoyi.qicai;

import com.ruoyi.common.filter.RefererFilter;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterConfig;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import static org.junit.jupiter.api.Assertions.*;

class RefererFilterTest
{
    private boolean allowed(String referer) throws Exception
    {
        RefererFilter filter = new RefererFilter();
        MockFilterConfig config = new MockFilterConfig();
        config.addInitParameter("allowedDomains", "localhost,127.0.0.1,dingdan.uuok.top,https://servicewechat.com/wx309489fb11166079/");
        filter.init(config);
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/profile/qicai-proof/example.png");
        if (referer != null) request.addHeader("Referer", referer);
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicBoolean reached = new AtomicBoolean(false);
        filter.doFilter(request, response, (req, res) -> reached.set(true));
        if (!reached.get()) assertEquals(403, response.getStatus());
        return reached.get();
    }

    @Test void currentMiniappAndExistingSitesReachOwnershipChecks() throws Exception
    {
        assertTrue(allowed("https://servicewechat.com/wx309489fb11166079/devtools/page-frame.html"));
        assertTrue(allowed("https://servicewechat.com/wx309489fb11166079/3/page-frame.html"));
        assertTrue(allowed("https://dingdan.uuok.top/pages/buyer/delivery"));
        assertTrue(allowed("http://localhost:25187/"));
    }
    @Test void anotherMiniappAndNonHttpsWechatAreRejected() throws Exception
    {
        assertFalse(allowed("https://servicewechat.com/wx0000000000000000/3/page-frame.html"));
        assertFalse(allowed("https://servicewechat.com/wx309489fb11166079-evil/page-frame.html"));
        assertFalse(allowed("http://servicewechat.com/wx309489fb11166079/3/page-frame.html"));
    }
    @Test void hostMustNotBeMatchedInsideQueryOrAnotherHost() throws Exception
    {
        assertFalse(allowed("https://evil.example/?from=https://dingdan.uuok.top/"));
        assertFalse(allowed("https://dingdan.uuok.top.evil.example/"));
        assertFalse(allowed("https://evil.example/https://servicewechat.com/wx309489fb11166079/"));
        assertFalse(allowed("https://dingdan.uuok.top@evil.example/"));
    }
    @Test void ambiguousMiniappPathsAreRejected() throws Exception
    {
        assertFalse(allowed("https://servicewechat.com/wx309489fb11166079/../wx000/1/page-frame.html"));
        assertFalse(allowed("https://servicewechat.com/wx309489fb11166079/%2e%2e/wx000/1/page-frame.html"));
    }
    @Test void missingAndMalformedRefererAreRejected() throws Exception
    {
        assertFalse(allowed(null));
        assertFalse(allowed(""));
        assertFalse(allowed("dingdan.uuok.top"));
        assertFalse(allowed("javascript:dingdan.uuok.top"));
        assertFalse(allowed("https://user@dingdan.uuok.top/"));
    }
}
