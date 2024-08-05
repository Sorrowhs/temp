package com.max.ops.processor.log;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.base.Joiner;
import com.max.base.exception.BusinessException;
import com.max.ops.base.exception.ErrCode;
import com.max.ops.processor.context.GatewayContext;
import org.slf4j.Logger;
import org.springframework.util.CollectionUtils;
import org.springframework.web.server.ServerWebExchange;

import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

import static org.springframework.cloud.gateway.support.ServerWebExchangeUtils.GATEWAY_REQUEST_URL_ATTR;

/**
 * @Author: liangximing
 * @Date: 2021/1/29 9:46
 * @Version 1.0
 */
public class GatewayLogUtil {

//    public static final String KEY_TRACE_ID = "requestid";
//    public static final String TRACE_ID_HEADER = "requestid";

    public static void writeAccessLogBefore(Logger log, ServerWebExchange exchange, GatewayContext gatewayContext){
        // 打印请求相关参数
        log.info("========================================== Start ==========================================");
        log.info("IP             :{}", gatewayContext.getIp());
        // 打印请求 url
        log.info("原始请求        : {}", gatewayContext.getOriginalRequestUrl());

        log.info("----------- 头部信息 -----------------");
        String headerInfo = Joiner.on("  ").withKeyValueSeparator(":").join(gatewayContext.getRequestHeaders().toSingleValueMap());
        //头部信息
        log.info(headerInfo);

    }
    public static void writeAccessLogAfter(Logger log, ServerWebExchange exchange,GatewayContext gatewayContext, Throwable throwable){
        Map<String, Object> errorAttributes = new HashMap<>(3);
        if(throwable!=null){
            if(throwable instanceof BusinessException){
                BusinessException ex = (BusinessException) throwable;
                errorAttributes.put("code",ex.getCode());
                errorAttributes.put("msg",ex.getMessage());
            }else{
                errorAttributes.put("code", ErrCode.SYSTEM_ERROR.code());
                errorAttributes.put("msg",ErrCode.SYSTEM_ERROR.getReasonPhrase()+":"+throwable.getMessage());
            }
        }


        try {
            ObjectMapper mapper = new ObjectMapper();
            log.info("----------- 请求体信息 -----------------");
            log.info("params          : {}",gatewayContext.getAllRequestData());
            log.info("body            : {}",gatewayContext.getRequestBody());
            log.info("proxy           : {}",exchange.getAttributes().get(GATEWAY_REQUEST_URL_ATTR));

            log.info("----------- 返回信息 -----------------");
            //返回信息
            if(CollectionUtils.isEmpty(errorAttributes)){
                String resp = new String((byte[]) gatewayContext.getResponseBody(), StandardCharsets.UTF_8);
                log.info("返回结果        : {}",resp);
            }else {
                log.info("返回结果        : {}",mapper.writeValueAsString(errorAttributes));
            }
        } catch (JsonProcessingException e) {
            e.printStackTrace();
        } finally {
            // 执行耗时
            log.info("Time-Consuming  : {} ms", System.currentTimeMillis() - gatewayContext.getStartTime());
            log.info("========================================== END ==========================================");
            if(throwable!=null){
                log.error("请求异常："+throwable.getMessage(),throwable);
            }

        }

    }
}
