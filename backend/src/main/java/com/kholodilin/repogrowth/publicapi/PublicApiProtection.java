package com.kholodilin.repogrowth.publicapi;
import com.github.benmanes.caffeine.cache.*;
import io.github.bucket4j.*;
import java.time.Duration;
import jakarta.servlet.*;
import jakarta.servlet.http.*;
import java.io.*;
import org.springframework.web.filter.OncePerRequestFilter;
/** Bound per-peer protection; untrusted forwarding headers cannot change the bucket key. */
public final class PublicApiProtection extends OncePerRequestFilter {
 private final Cache<String,Bucket> buckets=Caffeine.newBuilder().maximumSize(10000).expireAfterAccess(Duration.ofMinutes(10)).build();
 private final java.util.Set<String> trusted;
 private final long perMinute,burst;public PublicApiProtection(long perMinute,long burst){this(perMinute,burst,java.util.Set.of());}
 public PublicApiProtection(long perMinute,long burst,java.util.Set<String> trusted){this.trusted=java.util.Set.copyOf(trusted);if(perMinute<1||burst<1)throw new IllegalArgumentException("Positive public limits required");this.perMinute=perMinute;this.burst=burst;}
 private String peer(HttpServletRequest request){String remote=request.getRemoteAddr();if(!trusted.contains(remote))return remote;String forwarded=request.getHeader("X-Forwarded-For");if(forwarded==null||forwarded.length()>2048)return remote;String[] hops=forwarded.split(",");if(hops.length>16)return remote;for(int i=hops.length-1;i>=0;i--){String candidate=hops[i].trim();if(!candidate.matches("[0-9a-fA-F:.]{2,64}"))return remote;if(!trusted.contains(candidate))return candidate;}return remote;}
 @Override protected void doFilterInternal(HttpServletRequest request,HttpServletResponse response,FilterChain chain)throws ServletException,IOException{
 response.setHeader("Cache-Control","no-cache");var bucket=buckets.get(peer(request),key->Bucket.builder().addLimit(Bandwidth.builder().capacity(burst).refillGreedy(perMinute,Duration.ofMinutes(1)).build()).build());var probe=bucket.tryConsumeAndReturnRemaining(1);
 if(!probe.isConsumed()){response.setStatus(429);response.setHeader("Retry-After",Long.toString(Math.max(1,(probe.getNanosToWaitForRefill()+999999999)/1000000000)));response.setContentType("application/json");if(!request.getMethod().equals("HEAD"))response.getWriter().write("{\"code\":\"RATE_LIMITED\",\"message\":\"Too many requests\",\"requestId\":\""+java.util.UUID.randomUUID()+"\"}");return;}
 if(request.getMethod().equals("HEAD")){chain.doFilter(request,new HttpServletResponseWrapper(response){@Override public ServletOutputStream getOutputStream(){return new ServletOutputStream(){public boolean isReady(){return true;}public void setWriteListener(WriteListener listener){}public void write(int value){}};}@Override public PrintWriter getWriter(){return new PrintWriter(Writer.nullWriter());}});}else chain.doFilter(request,response);
 }
}
