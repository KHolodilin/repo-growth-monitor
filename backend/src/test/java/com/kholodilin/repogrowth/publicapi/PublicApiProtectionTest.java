package com.kholodilin.repogrowth.publicapi;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.*;
import static org.assertj.core.api.Assertions.*;
class PublicApiProtectionTest {
 @Test void periodAndForgedForwardedHeadersCannotResetPeerBudget()throws Exception{var filter=new PublicApiProtection(1,2);for(int n=0;n<3;n++){var request=new MockHttpServletRequest("GET","/api/v1/public/owners/a/stats");request.setRemoteAddr("192.0.2.1");request.addHeader("X-Forwarded-For","198.51.100."+n);var response=new MockHttpServletResponse();filter.doFilter(request,response,(req,res)->((jakarta.servlet.http.HttpServletResponse)res).setStatus(200));assertThat(response.getStatus()).isEqualTo(n<2?200:429);if(n==2)assertThat(response.getHeader("Retry-After")).isNotNull();}}
 @Test void headDoesNotWriteBodyAndProtectionCreatesNoCookies()throws Exception{var filter=new PublicApiProtection(120,30);var request=new MockHttpServletRequest("HEAD","/api/v1/public/owners/a");request.setRemoteAddr("192.0.2.2");var response=new MockHttpServletResponse();filter.doFilter(request,response,(req,res)->res.getOutputStream().write("body".getBytes()));assertThat(response.getContentAsString()).isEmpty();assertThat(response.getCookies()).isEmpty();}
 @Test void trustedProxyWalksFromRightAndDoesNotTrustForgedLeftmostAddress()throws Exception{var filter=new PublicApiProtection(1,2,java.util.Set.of("10.0.0.2"));for(int n=0;n<3;n++){var request=new MockHttpServletRequest("GET","/api/v1/public/owners");request.setRemoteAddr("10.0.0.2");request.addHeader("X-Forwarded-For","198.51.100."+n+", 192.0.2.99");var response=new MockHttpServletResponse();filter.doFilter(request,response,(req,res)->{});assertThat(response.getStatus()).isEqualTo(n<2?200:429);}}
}
