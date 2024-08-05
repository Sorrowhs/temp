package com.max.ops.processor.filter;

import cn.hutool.core.date.DateUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.max.base.exception.BusinessException;
import com.max.base.util.CheckUtil;
import com.max.ops.base.exception.ErrCode;
import com.max.ops.dao.mybatis.entity.PrvCtlRuleDef;
import com.max.ops.dao.mybatis.entity.PrvOperLoginLog;
import com.max.ops.dao.mybatis.entity.PrvOperator;
import com.max.ops.dao.mybatis.entity.PrvRuleFn;
import com.max.ops.dao.mybatis.mapper.PrvCtlRuleDefMapper;
import com.max.ops.dao.mybatis.mapper.PrvOperLoginLogMapper;
import com.max.ops.dao.mybatis.mapper.PrvOperatorMapper;
import com.max.ops.dao.mybatis.mapper.PrvRuleFnMapper;
import com.max.ops.processor.context.GatewayContext;
import com.max.security.jwt.JwtPair;
import com.max.security.jwt.JwtPayload;
import com.max.security.jwt.JwtTokenGenerator;
import com.max.security.jwt.JwtTokenStorage;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cloud.gateway.filter.GatewayFilter;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.factory.AbstractGatewayFilterFactory;
import org.springframework.cloud.gateway.filter.factory.rewrite.CachedBodyOutputMessage;
import org.springframework.cloud.gateway.support.BodyInserterContext;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.http.HttpHeaders;
import org.springframework.http.server.reactive.ServerHttpRequestDecorator;
import org.springframework.stereotype.Component;
import org.springframework.util.CollectionUtils;
import org.springframework.util.StringUtils;
import org.springframework.web.reactive.function.BodyInserter;
import org.springframework.web.reactive.function.BodyInserters;
import org.springframework.web.reactive.function.server.HandlerStrategies;
import org.springframework.web.reactive.function.server.ServerRequest;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import javax.annotation.Resource;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;

import static org.springframework.cloud.gateway.support.GatewayToStringStyler.filterToStringCreator;

/**
 * @Author: liangximing
 * @Date: 2023/1/31 9:57
 * ClassName:AuGatewayFilterFactory
 * @Description: 主要用于功能鉴权
 * @Version 1.0
 */
@Slf4j
@Component
public class AuGatewayFilterFactory extends AbstractGatewayFilterFactory<AuGatewayFilterFactory.Config> {


    @Resource
    private JwtTokenGenerator gateWayJwtTokenGenerator;
    @Resource
    private JwtTokenStorage jwtTokenStorage;
    @Resource
    private PrvCtlRuleDefMapper prvCtlRuleDefMapper;
    @Resource
    private PrvRuleFnMapper prvRuleFnMapper;
    @Resource
    private PrvOperatorMapper prvOperatorMapper;
    @Resource
    private PrvOperLoginLogMapper prvOperLoginLogMapper;

    /**
     * enabled key.
     */
    public static final String ENABLED_KEY = "enabled";
    private static final String AUTHENTICATION_HEADER = "UserInfo";

    /**
     * 登录入口
     */
//    private static final String LOGIN_ENTRY_POINT = "/AUTH-PLATFORM/auth/login-process";
    /**
     * token 刷新端口
     */
//    private static final String REFRESH_TOKEN_ENTRY_POINT = "/AUTH-PLATFORM/auth/token-refresh";

    public AuGatewayFilterFactory(){super(Config.class);}

    @Override
    public GatewayFilter apply(Config config) {
        return new GatewayFilter() {
            @SneakyThrows
            @Override
            public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {

                if (!config.isEnabled()) {
                    return chain.filter(exchange);
                }

//                ServerHttpRequest request = exchange.getRequest();
//                HttpHeaders headers = request.getHeaders();
//                validateHeaderParams(headers);
//
//                GatewayContext gatewayContext = new GatewayContext();
//                gatewayContext.setRequestHeaders(headers);
//                gatewayContext.getAllRequestData().addAll(request.getQueryParams());

                GatewayContext gatewayContext = (GatewayContext) exchange.getAttributes().get(GatewayContext.CACHE_GATEWAY_CONTEXT);

                //非忽略请求，需验证合法性
                if(!isIgnore(config.getIgnores(), gatewayContext.getOriginalRequestUrl())){
                    JwtPayload jwtPayload = validateJwt(gatewayContext);
                    validateCtlRule(gatewayContext,jwtPayload);
                }


                /**
                 * GatewayContext 用于装载请求方的请求内容。
                 * 原因：无论在Spring5的webflux编程或者普通web编程中，只能从request中获取body一次，后面无法再获取
                 *
                 * 注意：此处只是为了获取body内容装载到GatewayContext中，为后面接口打印body内容服务，如果性能影响大，则可以去除
                 */
                return reBuildBody(exchange, chain,gatewayContext);
//                return chain.filter(exchange);
            }

            @Override
            public String toString() {
                return filterToStringCreator(AuGatewayFilterFactory.this)
                        .append("enabled", config.isEnabled()).toString();
            }
        };
    }

    /**
     * 验证jwt是否合法
     * @param gatewayContext
     */
    private JwtPayload validateJwt(GatewayContext gatewayContext) throws Exception{
        HttpHeaders headers = gatewayContext.getRequestHeaders();
        String jwtToken = headers.getFirst(AUTHENTICATION_HEADER);
        CheckUtil.checkEmpty(jwtToken,"请求协议不存在！", ErrCode.MEDIATYPE_BLOCKED.code());
        //解码校验jwt 并获取 载体信息.
        JwtPayload jwtPayload = null;
        try{
            jwtPayload= gateWayJwtTokenGenerator.decodeAndVerify(jwtToken);
        }catch (Exception e){
//            log.error("解码校验jwt失败！",e);

            throw new BusinessException(ErrCode.MEDIATYPE_BLOCKED.code(),"请求协议格式有误！");
        }

        //jwt唯一ID
        String jti = jwtPayload.getJti();
        String expireTime = jwtPayload.getExp();

        // 是否过期
        if (isExpired(expireTime)) {
            throw new BusinessException(ErrCode.JWT_ERROR.code(),ErrCode.JWT_ERROR.getReasonPhrase());
        }


        // 从缓存获取 token （由于gateway没有退出机制，所以需要在此判断是否已登出系统）;
        //如果应用与认证中心，不是使用同一redis，应用需要实现JwtTokenStorage
        JwtPair jwtPair = jwtTokenStorage.get(jti);
        CheckUtil.checkNull(jwtPair,ErrCode.JWT_ERROR.getReasonPhrase(),ErrCode.JWT_ERROR.code());

        return jwtPayload;
    }

    /**
     * 验证接口功能权限
     * @param gatewayContext
     * @param jwtPayload
     * @throws Exception
     */
    private void validateCtlRule(GatewayContext gatewayContext,JwtPayload jwtPayload) throws Exception{

        //检查是否为接口类工号，才可以通过公网访问
//        PrvOperator operator = prvOperatorMapper.selectById(Long.parseLong(jwtPayload.getAud()));
//        if(!"2".equalsIgnoreCase(operator.getOperType())){
//            throw new BusinessException(ErrCode.RULELIMIT.code(),"非法请求,非接口类工号！");
//        }
        Date current = new Date();

        LambdaQueryWrapper<PrvOperLoginLog> logQueryWrapper = new LambdaQueryWrapper<>();
        logQueryWrapper
                .eq(PrvOperLoginLog::getAction,"S")
                .eq(PrvOperLoginLog::getOperId,Long.parseLong(jwtPayload.getAud()))
                .gt(PrvOperLoginLog::getOptime, DateUtil.beginOfDay(current))
                .orderByDesc(PrvOperLoginLog::getOptime);

        //最后一次登录日志信息
        List<PrvOperLoginLog> loginLogs = prvOperLoginLogMapper.selectList(logQueryWrapper);
        if(CollectionUtils.isEmpty(loginLogs) || !loginLogs.get(0).getLoginIp().equalsIgnoreCase(gatewayContext.getIp())){
            throw new BusinessException(ErrCode.RULELIMIT.code(),"非法请求,请求IP与登录IP不一致，请重新登录！");
        }


        QueryWrapper<PrvCtlRuleDef> prvCtlRuleDefQueryWrapper = new QueryWrapper<>();
        prvCtlRuleDefQueryWrapper.lambda()
                .eq(PrvCtlRuleDef::getKind,"2")
                .eq(PrvCtlRuleDef::getUri,gatewayContext.getOriginalRequestUrl());

        PrvCtlRuleDef prvCtlRuleDef = prvCtlRuleDefMapper.selectOne(prvCtlRuleDefQueryWrapper);
        if(Objects.isNull(prvCtlRuleDef)){
            return;
        }

        //由于JWT rules内容是deptid 与 rule的集合，所以需要分解
        Set<Long> rules = new HashSet<>();
        jwtPayload.getRoles().forEach(r->{
            rules.add(Long.parseLong(r.split("~")[1]));
        });
        //权限控制点
        Long ctrlId = prvCtlRuleDef.getCtrlId();
        Date now = new Date();
        QueryWrapper<PrvRuleFn> prvRuleFnQueryWrapper = new QueryWrapper<>();
        prvRuleFnQueryWrapper.lambda()
                .eq(PrvRuleFn::getCtrlId,ctrlId)
                .in(PrvRuleFn::getRoleId,rules)
                .le(PrvRuleFn::getStartTime,now)
                .gt(PrvRuleFn::getStopTime,now);

        Integer count = prvRuleFnMapper.selectCount(prvRuleFnQueryWrapper);

        if(Objects.isNull(count) || count.intValue()==0){
            throw new BusinessException(ErrCode.RULELIMIT.code(),"非法请求！");
        }
    }

    /**
     * 判断是否过期
     *
     * @param exp jwt exp
     * @return is exp or not
     */
    private boolean isExpired(String exp) {
        return LocalDateTime.now().isAfter(LocalDateTime.parse(exp, DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")));
    }

    /**
     * 是否忽略请求
     * @param ignores 忽略的地址
     * @param originalRequestUrl
     * @return
     */
    private boolean isIgnore(String ignores,String originalRequestUrl){

        if(!StringUtils.isEmpty(ignores)){
            String[] ignoreArr = ignores.split(",");
            for (String i : ignoreArr){
                if(i.equalsIgnoreCase(originalRequestUrl)){
                    return true;
                }
            }
        }
//
//        if(LOGIN_ENTRY_POINT.equalsIgnoreCase(originalRequestUrl) || REFRESH_TOKEN_ENTRY_POINT.equalsIgnoreCase(originalRequestUrl)){
//            return true;
//        }

        return false;
    }

    /**
     * 读取body 内容，并生成新的body内容
     * @param exchange
     * @param chain
     * @return
     */
    private Mono<Void> reBuildBody(ServerWebExchange exchange, GatewayFilterChain chain, GatewayContext gatewayContext){

        ServerRequest serverRequest = ServerRequest.create(exchange,
                HandlerStrategies.withDefaults().messageReaders());

        return serverRequest.bodyToMono(String.class)
                .doOnNext(objectValue -> {
                    /**
                     * 装载BODY内容
                     */
                    gatewayContext.setRequestBody(objectValue);
                }).then(Mono.defer(() -> {

                    // 重新生成新的body内容
                    JSONObject jsonObject = null;
                    try{
                        jsonObject = JSONUtil.parseObj(gatewayContext.getRequestBody());
                    }catch (Exception e){//出异常，表示body内容有误或者空，则直接返回
                        return chain.filter(exchange);
                    }

//                    jsonObject.putAll(gatewayContext.getRequestHeaders().toSingleValueMap());
                    BodyInserter bodyInserter = BodyInserters.fromValue(jsonObject.toString());


                    HttpHeaders headers = new HttpHeaders();
                    headers.putAll(exchange.getRequest().getHeaders());

                    // the new content type will be computed by bodyInserter
                    // and then set in the request decorator
                    headers.remove(HttpHeaders.CONTENT_LENGTH);

                    CachedBodyOutputMessage cachedBodyOutputMessage = new CachedBodyOutputMessage(exchange, headers);
                    return bodyInserter.insert(cachedBodyOutputMessage,  new BodyInserterContext())
                            .then(Mono.defer(() -> {
                                ServerHttpRequestDecorator decorator = new ServerHttpRequestDecorator(
                                        exchange.getRequest()) {
                                    @Override
                                    public HttpHeaders getHeaders() {
                                        return headers;
                                    }
                                    @Override
                                    public Flux<DataBuffer> getBody() {
                                        return cachedBodyOutputMessage.getBody();
                                    }
                                };
                                return chain.filter(exchange.mutate().request(decorator).build());
                            }));
                }));


    }



    @Override
    public List<String> shortcutFieldOrder() {
        return Arrays.asList(ENABLED_KEY);
    }
    public static class Config {
        // 控制是否开启认证
        private boolean enabled;
        private String ignores;

        public Config() {}

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public String getIgnores() {
            return ignores;
        }

        public void setIgnores(String ignores) {
            this.ignores = ignores;
        }
    }
}
