package com.max.ops.processor.filter;

import cn.hutool.extra.servlet.ServletUtil;
import com.max.base.util.AppContext;
import com.max.ops.processor.context.GatewayContext;
import com.max.ops.processor.log.GatewayLogUtil;
import lombok.extern.slf4j.Slf4j;
import org.reactivestreams.Publisher;
import org.slf4j.MDC;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.core.io.buffer.DataBufferFactory;
import org.springframework.core.io.buffer.DataBufferUtils;
import org.springframework.http.HttpHeaders;
import org.springframework.http.codec.HttpMessageReader;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.http.server.reactive.ServerHttpResponseDecorator;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.reactive.function.server.HandlerStrategies;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.List;

/**
 * Gateway Context Filter
 * @Author: liangximing
 * @Date: 2021/1/13 17:16
 * @Version 1.0
 */
@Slf4j
@Component
public class LogFilter implements GlobalFilter , Ordered {

    /**
     * default HttpMessageReader
     */
    private static final List<HttpMessageReader<?>> MESSAGE_READERS = HandlerStrategies.withDefaults().messageReaders();


    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {

        ServerHttpRequest request = exchange.getRequest();
        HttpHeaders headers = request.getHeaders();

        /**
         * MDC使用的InheritableThreadLocal只是在线程被创建时继承，但是线程池中的线程是复用的，后续请求使用已有的线程将打印出之前请求的traceId。
         */
        String traceId = headers.getFirst(AppContext.TRACE_ID_HEADER);

        try{

            if (StringUtils.hasText(traceId)) {
                AppContext.getContext().setTraceId(traceId);

                //放到log4j的MDC里
                MDC.put(AppContext.TRACE_ID_HEADER, AppContext.getContext().getTraceId());
            }


            GatewayContext gatewayContext = new GatewayContext();
            gatewayContext.setTraceId(traceId);
            gatewayContext.setStartTime(System.currentTimeMillis());
            gatewayContext.setOriginalRequestUrl(request.getURI().getPath());
            gatewayContext.setIp(this.getIpAddr(request,headers));
            gatewayContext.setRequestHeaders(headers);
            gatewayContext.getAllRequestData().addAll(request.getQueryParams());


            GatewayLogUtil.writeAccessLogBefore(log,exchange,gatewayContext);
            /**
             * GatewayContext 用于装载请求方的请求内容。
             * 原因：无论在Spring5的webflux编程或者普通web编程中，只能从request中获取body一次，后面无法再获取
             */
            exchange.getAttributes().put(GatewayContext.CACHE_GATEWAY_CONTEXT,gatewayContext);


            /**
             * 自定义ServerHttpResponse 代理类，用于获取输出内容，写日志
             */
            ServerHttpResponse serverHttpResponse = exchange.getResponse();
            DataBufferFactory bufferFactory = serverHttpResponse.bufferFactory();
            ServerHttpResponseDecorator response = new ServerHttpResponseDecorator(serverHttpResponse){
                @Override
                public Mono<Void> writeWith(Publisher<? extends DataBuffer> body) {

                    if (body instanceof Flux) {
                        Flux<? extends DataBuffer> fluxBody = (Flux<? extends DataBuffer>) body;
                        return super.writeWith(fluxBody.map(dataBuffer -> {
                            byte[] content = new byte[dataBuffer.readableByteCount()];
                            dataBuffer.read(content);
                            DataBufferUtils.release(dataBuffer);


                            gatewayContext.addResponseBody(content);
//
                                return bufferFactory.wrap(content);
//

                        }));
                    }

                    return super.writeWith(body);
                }
            };
            return chain.filter(exchange.mutate().response(response).build())
                    .doOnSuccess(aVoid -> handleResponseOnComplete(exchange,null))
                    .doOnError(throwable -> handleResponseOnComplete(exchange,throwable));
        }finally {

            if (StringUtils.hasText(traceId)) {
                MDC.remove(AppContext.TRACE_ID_HEADER);
            }
        }



    }

    /**
     * response完成后，输出日志
     * @param exchange
     * @param throwable
     */
    private void handleResponseOnComplete(ServerWebExchange exchange,Throwable throwable) {

//        ServerHttpResponse response = exchange.getResponse();
        GatewayContext gatewayContext = (GatewayContext) exchange.getAttributes().get(GatewayContext.CACHE_GATEWAY_CONTEXT);
//        GatewayLogUtil.writeAccessLogAfter(log,exchange,gatewayContext,throwable);
        String traceId = gatewayContext.getRequestHeaders().getFirst(AppContext.TRACE_ID_HEADER);
        try {
//
            if (StringUtils.hasText(traceId)) {
                AppContext.getContext().setTraceId(traceId);

                //放到log4j的MDC里
                MDC.put(AppContext.TRACE_ID_HEADER, AppContext.getContext().getTraceId());
            }
            GatewayLogUtil.writeAccessLogAfter(log,exchange,gatewayContext,throwable);
        }finally {
            if (StringUtils.hasText(traceId)) {
                MDC.remove(AppContext.TRACE_ID_HEADER);
            }
        }
    }


    @Override
    public int getOrder() {
        return -2;
    }

    private String getIpAddr(ServerHttpRequest request,HttpHeaders headers){
        String ipAddress = null;
        ipAddress = headers.getFirst("X-Forwarded-For");
        if (StringUtils.isEmpty(ipAddress)) {
            ipAddress = headers.getFirst("Proxy-Client-IP");
        }

        if (StringUtils.isEmpty(ipAddress)) {
            ipAddress = headers.getFirst("WL-Proxy-Client-IP");
        }

        if (StringUtils.isEmpty(ipAddress)) {
            ipAddress = headers.getFirst("HTTP_CLIENT_IP");
        }

        if (StringUtils.isEmpty(ipAddress)) {
            ipAddress = headers.getFirst("HTTP_X_FORWARDED_FOR");
        }

        if (StringUtils.isEmpty(ipAddress)) {
            ipAddress = request.getRemoteAddress().toString();
            if ("127.0.0.1".equals(ipAddress) || "0:0:0:0:0:0:0:1".equals(ipAddress)
                    || "localhost".equalsIgnoreCase(ipAddress)) {
                // 根据网卡取本机配置的IP
                InetAddress inet = null;
                try {
                    inet = InetAddress.getLocalHost();
                } catch (UnknownHostException e) {
                    e.printStackTrace();
                }
                ipAddress = inet.getHostAddress();
            }

        }
        // 对于通过多个代理的情况，第一个IP为客户端真实IP,多个IP按照','分割
        if (ipAddress != null && ipAddress.length() > 15) { // "***.***.***.***".length()= 15
            if (ipAddress.indexOf(",") > 0) {
                ipAddress = ipAddress.substring(0, ipAddress.indexOf(","));
            }
        }
        return ipAddress;
    }
}
