package com.max.ops.processor.context;

import org.springframework.http.HttpHeaders;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;

/**
 * Gateway Context Use Cache Request Content
 * @Author: liangximing
 * @Date: 2021/1/13 17:15
 * @Version 1.0
 */
//@Getter
//@Setter
//@ToString
public class GatewayContext {

    public static final String CACHE_GATEWAY_CONTEXT = "cacheGatewayContext";
    /**
     * 请求报文跟踪ID
     */
    protected String traceId;
    /**
     * 接收请求时间，主要用于计算耗时和记录接收请求时间
     */
    protected long startTime;
    /**
     * 原始请求URL
     */
    protected String originalRequestUrl;
    /**
     * 请求IP
     */
    protected String ip;
    /**
     * 代理请求URL
     */
//    protected String agentRequestUrl;
    /**
     * cache json body
     */
    protected String requestBody;
    /**
     * cache Response Body
     */
    protected byte[] responseBody;
    /**
     * request headers
     */
    protected HttpHeaders requestHeaders;
    /**
     * cache form data
     */
    protected MultiValueMap<String, String> formData;
    /**
     * cache all request data include:form data and query param
     */
    protected MultiValueMap<String, String> allRequestData = new LinkedMultiValueMap<>(0);

    public String getTraceId() {
        return traceId;
    }

    public void setTraceId(String traceId) {
        this.traceId = traceId;
    }

    public long getStartTime() {
        return startTime;
    }

    public void setStartTime(long startTime) {
        this.startTime = startTime;
    }

    public String getOriginalRequestUrl() {
        return originalRequestUrl;
    }

    public void setOriginalRequestUrl(String originalRequestUrl) {
        this.originalRequestUrl = originalRequestUrl;
    }

    public String getIp() {
        return ip;
    }

    public void setIp(String ip) {
        this.ip = ip;
    }

//    public String getAgentRequestUrl() {
//        return agentRequestUrl;
//    }
//
//    public void setAgentRequestUrl(String agentRequestUrl) {
//        this.agentRequestUrl = agentRequestUrl;
//    }

    public String getRequestBody() {
        return requestBody;
    }

    public void setRequestBody(String requestBody) {
        this.requestBody = requestBody;
    }

    public byte[] getResponseBody() {
        return responseBody;
    }

    public void setResponseBody(byte[] responseBody) {
        this.responseBody = responseBody;
    }

    public void addResponseBody(byte[] newResponse){
        if(this.responseBody==null){
            this.responseBody = newResponse;
            return;
        }

        this.responseBody = byteMerger(this.responseBody,newResponse);
    }

    public HttpHeaders getRequestHeaders() {
        return requestHeaders;
    }

    public void setRequestHeaders(HttpHeaders requestHeaders) {
        this.requestHeaders = requestHeaders;
    }

    public MultiValueMap<String, String> getFormData() {
        return formData;
    }

    public void setFormData(MultiValueMap<String, String> formData) {
        this.formData = formData;
    }

    public MultiValueMap<String, String> getAllRequestData() {
        return allRequestData;
    }

    public void setAllRequestData(MultiValueMap<String, String> allRequestData) {
        this.allRequestData = allRequestData;
    }

    /**
     * 合并byte[]数组 （不改变原数组）
     * @param byte_1
     * @param byte_2
     * @return 合并后的数组
     */
    private byte[] byteMerger(byte[] byte_1, byte[] byte_2){
        byte[] byte_3 = new byte[byte_1.length+byte_2.length];
        System.arraycopy(byte_1, 0, byte_3, 0, byte_1.length);
        System.arraycopy(byte_2, 0, byte_3, byte_1.length, byte_2.length);
        return byte_3;
    }
}
