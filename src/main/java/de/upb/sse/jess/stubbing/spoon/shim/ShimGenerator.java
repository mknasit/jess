package de.upb.sse.jess.stubbing.spoon.shim;

import spoon.reflect.code.CtBlock;
import spoon.reflect.code.CtExpression;
import spoon.reflect.code.CtReturn;
import spoon.reflect.declaration.*;
import spoon.reflect.factory.Factory;
import spoon.reflect.reference.CtTypeReference;
import spoon.reflect.reference.CtTypeParameterReference;

import java.util.*;

/**
 * Generates minimal shim classes for common libraries that are often missing
 * from the classpath but are needed for compilation.
 *
 * These shims provide basic stub implementations to allow compilation to succeed.
 */
public class ShimGenerator {

    private final Factory factory;
    private final Map<String, ShimDefinition> shimDefinitions;

    public ShimGenerator(Factory factory) {
        this.factory = factory;
        this.shimDefinitions = new HashMap<>();
        initializeCommonShims();
    }

    /**
     * Initialize predefined shims for common libraries.
     */
    private void initializeCommonShims() {
        // ANTLR shims
        addShim("org.antlr.v4.runtime", "Parser", createClassShim("org.antlr.v4.runtime.Parser"));
        addShim("org.antlr.v4.runtime", "Lexer", createClassShim("org.antlr.v4.runtime.Lexer"));
        addShim("org.antlr.v4.runtime", "Token", createInterfaceShim("org.antlr.v4.runtime.Token"));
        addShim("org.antlr.v4.runtime", "ParserRuleContext", createClassShim("org.antlr.v4.runtime.ParserRuleContext"));
        addShim("org.antlr.v4.runtime.tree", "ParseTree", createInterfaceShim("org.antlr.v4.runtime.tree.ParseTree"));
        addShim("org.antlr.v4.runtime.tree", "ParseTreeVisitor", createInterfaceShim("org.antlr.v4.runtime.tree.ParseTreeVisitor"));

        // SLF4J shims
        addShim("org.slf4j", "Logger", createInterfaceShim("org.slf4j.Logger",
                Arrays.asList("info", "debug", "warn", "error", "trace")));
        addShim("org.slf4j", "LoggerFactory", createClassShim("org.slf4j.LoggerFactory",
                Arrays.asList("getLogger")));
        addShim("org.slf4j", "MDC", createClassShim("org.slf4j.MDC",
                Arrays.asList("put", "get", "remove", "clear", "getCopyOfContextMap", "setContextMap")));

        // Apache Commons Lang shims
        // Note: Don't add toString, equals, hashCode to classes - they already exist in Object
        addShim("org.apache.commons.lang3", "StringUtils", createClassShim("org.apache.commons.lang3.StringUtils",
                Arrays.asList("isEmpty", "isNotEmpty", "isBlank", "isNotBlank", "trim")));
        addShim("org.apache.commons.lang3", "ObjectUtils", createClassShim("org.apache.commons.lang3.ObjectUtils",
                Arrays.asList("defaultIfNull")));
        addShim("org.apache.commons.lang3", "ArrayUtils", createClassShim("org.apache.commons.lang3.ArrayUtils",
                Arrays.asList("isEmpty", "isNotEmpty", "contains")));

        // ASM shims
        addShim("org.objectweb.asm", "ClassVisitor", createClassShim("org.objectweb.asm.ClassVisitor"));
        addShim("org.objectweb.asm", "MethodVisitor", createClassShim("org.objectweb.asm.MethodVisitor"));
        addShim("org.objectweb.asm", "FieldVisitor", createClassShim("org.objectweb.asm.FieldVisitor"));
        addShim("org.objectweb.asm", "AnnotationVisitor", createClassShim("org.objectweb.asm.AnnotationVisitor"));
        addShim("org.objectweb.asm", "Opcodes", createInterfaceShim("org.objectweb.asm.Opcodes"));

        // JUnit shims (commonly used in tests)
        addShim("org.junit", "Test", createAnnotationShim("org.junit.Test"));
        addShim("org.junit", "Before", createAnnotationShim("org.junit.Before"));
        addShim("org.junit", "After", createAnnotationShim("org.junit.After"));
        addShim("org.junit", "BeforeClass", createAnnotationShim("org.junit.BeforeClass"));
        addShim("org.junit", "AfterClass", createAnnotationShim("org.junit.AfterClass"));
        addShim("org.junit", "Assert", createClassShim("org.junit.Assert",
                Arrays.asList("assertEquals", "assertNotNull", "assertTrue", "assertFalse")));

        // JUnit Jupiter shims
        addShim("org.junit.jupiter.api", "Test", createAnnotationShim("org.junit.jupiter.api.Test"));
        addShim("org.junit.jupiter.api", "BeforeEach", createAnnotationShim("org.junit.jupiter.api.BeforeEach"));
        addShim("org.junit.jupiter.api", "AfterEach", createAnnotationShim("org.junit.jupiter.api.AfterEach"));
        addShim("org.junit.jupiter.api", "BeforeAll", createAnnotationShim("org.junit.jupiter.api.BeforeAll"));
        addShim("org.junit.jupiter.api", "AfterAll", createAnnotationShim("org.junit.jupiter.api.AfterAll"));
        addShim("org.junit.jupiter.api", "Assertions", createClassShim("org.junit.jupiter.api.Assertions",
                Arrays.asList("assertEquals", "assertNotNull", "assertTrue", "assertFalse")));

        // Mockito shims
        addShim("org.mockito", "Mock", createAnnotationShim("org.mockito.Mock"));
        addShim("org.mockito", "Mockito", createClassShim("org.mockito.Mockito",
                Arrays.asList("mock", "when", "verify", "verify", "any")));

        // Guava shims (common types)
        addShim("com.google.common.base", "Optional", createClassShim("com.google.common.base.Optional",
                Arrays.asList("of", "absent", "isPresent", "get", "or")));
        addShim("com.google.common.collect", "ImmutableList", createClassShim("com.google.common.collect.ImmutableList",
                Arrays.asList("of", "copyOf")));
        addShim("com.google.common.collect", "ImmutableSet", createClassShim("com.google.common.collect.ImmutableSet",
                Arrays.asList("of", "copyOf")));
        addShim("com.google.common.collect", "ImmutableMap", createClassShim("com.google.common.collect.ImmutableMap",
                Arrays.asList("of", "copyOf")));

        // gRPC shims
        addShim("io.grpc", "MethodDescriptor", createClassShim("io.grpc.MethodDescriptor"));
        addShim("io.grpc", "ServerCall", createClassShim("io.grpc.ServerCall",
                Arrays.asList("sendHeaders", "sendMessage", "close", "isReady", "setCompression")));
        addShim("io.grpc", "Status", createClassShim("io.grpc.Status",
                Arrays.asList("ok", "fromThrowable", "getCode", "withDescription", "withCause")));
        addShim("io.grpc.stub", "StreamObserver", createInterfaceShim("io.grpc.stub.StreamObserver",
                Arrays.asList("onNext", "onCompleted", "onError")));
        addShim("io.grpc", "Channel", createInterfaceShim("io.grpc.Channel",
                Arrays.asList("newCall", "authority")));
        addShim("io.grpc", "CallOptions", createClassShim("io.grpc.CallOptions",
                Arrays.asList("withDeadline", "withWaitForReady", "withCompression")));

        // Protocol Buffers shims (standard package)
        addShim("com.google.protobuf", "GeneratedMessageLite",
                createClassShim("com.google.protobuf.GeneratedMessageLite",
                        Arrays.asList("parseFrom", "toByteArray", "getSerializedSize")));
        addShim("com.google.protobuf", "GeneratedMessage",
                createClassShim("com.google.protobuf.GeneratedMessage",
                        Arrays.asList("parseFrom", "toByteArray", "getSerializedSize")));
        addShim("com.google.protobuf", "Message",
                createInterfaceShim("com.google.protobuf.Message",
                        Arrays.asList("toByteArray", "getSerializedSize", "parseFrom")));
        addShim("com.google.protobuf", "MessageLite",
                createInterfaceShim("com.google.protobuf.MessageLite",
                        Arrays.asList("toByteArray", "getSerializedSize")));
        addShim("com.google.protobuf", "MessageOrBuilder",
                createInterfaceShim("com.google.protobuf.MessageOrBuilder",
                        Arrays.asList("toByteArray", "getSerializedSize")));
        addShim("com.google.protobuf", "Parser",
                createInterfaceShim("com.google.protobuf.Parser",
                        Arrays.asList("parseFrom", "parseDelimitedFrom")));
        addShim("com.google.protobuf", "ExtensionRegistryLite",
                createClassShim("com.google.protobuf.ExtensionRegistryLite"));
        addShim("com.google.protobuf", "InvalidProtocolBufferException",
                createClassShim("com.google.protobuf.InvalidProtocolBufferException"));

        // Apache Thrift shims
        addShim("org.apache.thrift", "TBase", createInterfaceShim("org.apache.thrift.TBase"));
        addShim("org.apache.thrift", "TException", createClassShim("org.apache.thrift.TException"));
        addShim("org.apache.thrift", "TIOError", createClassShim("org.apache.thrift.TIOError"));
        addShim("org.apache.thrift", "TResult", createClassShim("org.apache.thrift.TResult"));
        addShim("org.apache.thrift", "TFieldIdEnum", createInterfaceShim("org.apache.thrift.TFieldIdEnum"));
        addShim("org.apache.thrift.protocol", "TProtocol", createClassShim("org.apache.thrift.protocol.TProtocol",
                Arrays.asList("readString", "writeString", "readI32", "writeI32")));
        addShim("org.apache.thrift.scheme", "StandardScheme", createClassShim("org.apache.thrift.scheme.StandardScheme"));
        addShim("org.apache.thrift.scheme", "TupleScheme", createClassShim("org.apache.thrift.scheme.TupleScheme"));

        // Apache Hadoop shims
        addShim("org.apache.hadoop.util", "Shell", createClassShim("org.apache.hadoop.util.Shell",
                Arrays.asList("execCommand", "execCommand", "getExitCode")));
        addShim("org.apache.hadoop.conf", "Configuration", createClassShim("org.apache.hadoop.conf.Configuration",
                Arrays.asList("get", "set", "getInt", "getLong", "getBoolean")));

        // Apache HBase shims
        addShim("org.apache.hadoop.hbase.util", "FSUtils", createClassShim("org.apache.hadoop.hbase.util.FSUtils",
                Arrays.asList("getRootDir", "getWALDir")));

        // Apache ZooKeeper shims
        addShim("org.apache.zookeeper", "Watcher", createInterfaceShim("org.apache.zookeeper.Watcher",
                Arrays.asList("process")));

        // ======================================================================
        // FRAMEWORK SHIMS - High Priority (from failure logs analysis)
        // ======================================================================

        // MyBatis Plus shims (com.baomidou.mybatisplus.*)
        addShim("com.baomidou.mybatisplus.core.mapper", "BaseMapper",
                createInterfaceShim("com.baomidou.mybatisplus.core.mapper.BaseMapper",
                        Arrays.asList("selectById", "selectList", "insert", "updateById", "deleteById")));
        addShim("com.baomidou.mybatisplus.core.conditions.query", "QueryWrapper",
                createClassShim("com.baomidou.mybatisplus.core.conditions.query.QueryWrapper",
                        Arrays.asList("eq", "ne", "gt", "ge", "lt", "le", "like", "in", "isNull", "isNotNull")));
        addShim("com.baomidou.mybatisplus.core.conditions.query", "LambdaQueryWrapper",
                createClassShim("com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper",
                        Arrays.asList("eq", "ne", "gt", "ge", "lt", "le", "like", "in")));
        addShim("com.baomidou.mybatisplus.core.conditions.update", "UpdateWrapper",
                createClassShim("com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper",
                        Arrays.asList("set", "eq", "ne")));
        addShim("com.baomidou.mybatisplus.annotation", "TableName", createAnnotationShim("com.baomidou.mybatisplus.annotation.TableName"));
        addShim("com.baomidou.mybatisplus.annotation", "TableId", createAnnotationShim("com.baomidou.mybatisplus.annotation.TableId"));
        addShim("com.baomidou.mybatisplus.annotation", "TableField", createAnnotationShim("com.baomidou.mybatisplus.annotation.TableField"));
        addShim("com.baomidou.mybatisplus.extension.plugins", "PaginationInterceptor",
                createClassShim("com.baomidou.mybatisplus.extension.plugins.PaginationInterceptor"));
        addShim("com.baomidou.mybatisplus.extension.plugins.inner", "PaginationInnerInterceptor",
                createClassShim("com.baomidou.mybatisplus.extension.plugins.inner.PaginationInnerInterceptor"));
        addShim("com.baomidou.mybatisplus.extension.plugins.pagination", "Page",
                createClassShim("com.baomidou.mybatisplus.extension.plugins.pagination.Page",
                        Arrays.asList("getRecords", "getTotal", "getSize", "getCurrent")));
        addShim("com.baomidou.mybatisplus.core.metadata", "IPage",
                createInterfaceShim("com.baomidou.mybatisplus.core.metadata.IPage",
                        Arrays.asList("getRecords", "getTotal", "getSize", "getCurrent")));
        addShim("com.baomidou.mybatisplus.core.toolkit", "CollectionUtils",
                createClassShim("com.baomidou.mybatisplus.core.toolkit.CollectionUtils",
                        Arrays.asList("isEmpty", "isNotEmpty")));
        addShim("com.baomidou.mybatisplus.core.toolkit", "StringUtils",
                createClassShim("com.baomidou.mybatisplus.core.toolkit.StringUtils",
                        Arrays.asList("isBlank", "isNotBlank")));

        // Jakarta Servlet API shims (jakarta.servlet.*)
        addShim("jakarta.servlet", "Servlet", createInterfaceShim("jakarta.servlet.Servlet",
                Arrays.asList("init", "service", "destroy", "getServletConfig", "getServletInfo")));
        addShim("jakarta.servlet.http", "HttpServlet", createClassShim("jakarta.servlet.http.HttpServlet",
                Arrays.asList("doGet", "doPost", "doPut", "doDelete", "doHead", "doOptions", "doTrace")));
        addShim("jakarta.servlet.http", "HttpServletRequest", createInterfaceShim("jakarta.servlet.http.HttpServletRequest",
                Arrays.asList("getParameter", "getParameterValues", "getHeader", "getMethod", "getRequestURI",
                        "getQueryString", "getSession", "getCookies", "getAttribute", "setAttribute")));
        addShim("jakarta.servlet.http", "HttpServletResponse", createInterfaceShim("jakarta.servlet.http.HttpServletResponse",
                Arrays.asList("setStatus", "setHeader", "addHeader", "setContentType", "getWriter", "getOutputStream",
                        "sendRedirect", "sendError", "addCookie")));
        addShim("jakarta.servlet.http", "HttpSession", createInterfaceShim("jakarta.servlet.http.HttpSession",
                Arrays.asList("getAttribute", "setAttribute", "removeAttribute", "invalidate", "getId")));
        addShim("jakarta.servlet", "Filter", createInterfaceShim("jakarta.servlet.Filter",
                Arrays.asList("init", "doFilter", "destroy")));
        addShim("jakarta.servlet", "FilterChain", createInterfaceShim("jakarta.servlet.FilterChain",
                Arrays.asList("doFilter")));
        addShim("jakarta.servlet", "ServletContext", createInterfaceShim("jakarta.servlet.ServletContext",
                Arrays.asList("getAttribute", "setAttribute", "getInitParameter")));
        addShim("jakarta.servlet", "RequestDispatcher", createInterfaceShim("jakarta.servlet.RequestDispatcher",
                Arrays.asList("forward", "include")));
        addShim("jakarta.servlet", "ServletException", createClassShim("jakarta.servlet.ServletException"));
        addShim("jakarta.servlet", "ServletInputStream", createClassShim("jakarta.servlet.ServletInputStream",
                Arrays.asList("read", "readLine")));
        addShim("jakarta.servlet", "ServletOutputStream", createClassShim("jakarta.servlet.ServletOutputStream",
                Arrays.asList("write", "print", "println")));
        addShim("jakarta.servlet.http", "Cookie", createClassShim("jakarta.servlet.http.Cookie",
                Arrays.asList("getName", "getValue", "setValue", "getMaxAge", "setMaxAge")));

        // Spring Framework Core shims (org.springframework.*)
        addShim("org.springframework.web.bind.annotation", "RestController", createAnnotationShim("org.springframework.web.bind.annotation.RestController"));
        addShim("org.springframework.web.bind.annotation", "Controller", createAnnotationShim("org.springframework.web.bind.annotation.Controller"));
        addShim("org.springframework.web.bind.annotation", "RequestMapping", createAnnotationShim("org.springframework.web.bind.annotation.RequestMapping"));
        addShim("org.springframework.web.bind.annotation", "GetMapping", createAnnotationShim("org.springframework.web.bind.annotation.GetMapping"));
        addShim("org.springframework.web.bind.annotation", "PostMapping", createAnnotationShim("org.springframework.web.bind.annotation.PostMapping"));
        addShim("org.springframework.web.bind.annotation", "PutMapping", createAnnotationShim("org.springframework.web.bind.annotation.PutMapping"));
        addShim("org.springframework.web.bind.annotation", "DeleteMapping", createAnnotationShim("org.springframework.web.bind.annotation.DeleteMapping"));
        addShim("org.springframework.web.bind.annotation", "PatchMapping", createAnnotationShim("org.springframework.web.bind.annotation.PatchMapping"));
        addShim("org.springframework.web.bind.annotation", "RequestParam", createAnnotationShim("org.springframework.web.bind.annotation.RequestParam"));
        addShim("org.springframework.web.bind.annotation", "PathVariable", createAnnotationShim("org.springframework.web.bind.annotation.PathVariable"));
        addShim("org.springframework.web.bind.annotation", "RequestBody", createAnnotationShim("org.springframework.web.bind.annotation.RequestBody"));
        addShim("org.springframework.web.bind.annotation", "ResponseBody", createAnnotationShim("org.springframework.web.bind.annotation.ResponseBody"));
        addShim("org.springframework.web.bind.annotation", "ResponseEntity", createClassShim("org.springframework.web.bind.annotation.ResponseEntity",
                Arrays.asList("ok", "status", "body", "headers")));
        addShim("org.springframework.web.servlet", "HandlerInterceptor", createInterfaceShim("org.springframework.web.servlet.HandlerInterceptor",
                Arrays.asList("preHandle", "postHandle", "afterCompletion")));
        addShim("org.springframework.web.servlet", "ModelAndView", createClassShim("org.springframework.web.servlet.ModelAndView",
                Arrays.asList("addObject", "setViewName", "getViewName")));
        addShim("org.springframework.web.servlet.mvc.method.annotation", "ResponseEntityExceptionHandler",
                createClassShim("org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler"));
        addShim("org.springframework.web.multipart", "MultipartFile", createInterfaceShim("org.springframework.web.multipart.MultipartFile",
                Arrays.asList("getName", "getOriginalFilename", "getContentType", "isEmpty", "getSize", "getBytes", "getInputStream")));
        addShim("org.springframework.web.cors", "CorsConfiguration", createClassShim("org.springframework.web.cors.CorsConfiguration",
                Arrays.asList("addAllowedOrigin", "addAllowedMethod", "addAllowedHeader", "setAllowCredentials")));
        addShim("org.springframework.web.cors", "CorsConfigurationSource", createInterfaceShim("org.springframework.web.cors.CorsConfigurationSource",
                Arrays.asList("getCorsConfiguration")));
        addShim("org.springframework.web.filter", "CorsFilter", createClassShim("org.springframework.web.filter.CorsFilter"));
        addShim("org.springframework.http", "HttpStatus", createEnumShim("org.springframework.http.HttpStatus"));
        addShim("org.springframework.http", "HttpHeaders", createClassShim("org.springframework.http.HttpHeaders",
                Arrays.asList("set", "add", "get", "getFirst")));
        addShim("org.springframework.http", "MediaType", createClassShim("org.springframework.http.MediaType",
                Arrays.asList("APPLICATION_JSON", "APPLICATION_XML", "TEXT_PLAIN")));
        addShim("org.springframework.http.client", "ClientHttpRequestFactory", createInterfaceShim("org.springframework.http.client.ClientHttpRequestFactory",
                Arrays.asList("createRequest")));
        addShim("org.springframework.web.client", "RestClient", createClassShim("org.springframework.web.client.RestClient",
                Arrays.asList("create", "get", "post", "put", "delete")));
        addShim("org.springframework.util", "StringUtils", createClassShim("org.springframework.util.StringUtils",
                Arrays.asList("hasText", "isEmpty", "trimWhitespace", "commaDelimitedListToSet")));
        addShim("org.springframework.cache", "CacheManager", createInterfaceShim("org.springframework.cache.CacheManager",
                Arrays.asList("getCache", "getCacheNames")));
        addShim("org.springframework.cache", "Cache", createInterfaceShim("org.springframework.cache.Cache",
                Arrays.asList("get", "put", "evict", "clear")));
        addShim("org.springframework.cache.caffeine", "CaffeineCacheManager", createClassShim("org.springframework.cache.caffeine.CaffeineCacheManager"));
        addShim("org.springframework.cache.support", "SimpleCacheManager", createClassShim("org.springframework.cache.support.SimpleCacheManager"));
        addShim("org.springframework.data.redis.cache", "RedisCacheConfiguration", createClassShim("org.springframework.data.redis.cache.RedisCacheConfiguration",
                Arrays.asList("defaultCacheConfig", "entryTtl")));
        addShim("org.springframework.data.redis.cache", "RedisCacheManager", createClassShim("org.springframework.data.redis.cache.RedisCacheManager"));
        addShim("org.springframework.data.redis.connection", "RedisConnectionFactory", createInterfaceShim("org.springframework.data.redis.connection.RedisConnectionFactory",
                Arrays.asList("getConnection")));
        addShim("org.springframework.data.redis.core", "RedisTemplate", createClassShim("org.springframework.data.redis.core.RedisTemplate",
                Arrays.asList("opsForValue", "opsForHash", "opsForList", "opsForSet", "opsForZSet")));
        addShim("org.springframework.data.redis.core", "StringRedisTemplate", createClassShim("org.springframework.data.redis.core.StringRedisTemplate"));
        addShim("org.springframework.data.redis.core", "ValueOperations", createInterfaceShim("org.springframework.data.redis.core.ValueOperations",
                Arrays.asList("get", "set", "setIfAbsent", "increment", "decrement")));
        addShim("org.springframework.data.redis.core", "HashOperations", createInterfaceShim("org.springframework.data.redis.core.HashOperations",
                Arrays.asList("get", "put", "delete", "hasKey")));
        addShim("org.springframework.data.redis.core", "ListOperations", createInterfaceShim("org.springframework.data.redis.core.ListOperations",
                Arrays.asList("leftPush", "rightPush", "leftPop", "rightPop", "range")));
        addShim("org.springframework.data.redis.core", "SetOperations", createInterfaceShim("org.springframework.data.redis.core.SetOperations",
                Arrays.asList("add", "remove", "members", "isMember")));
        addShim("org.springframework.data.redis.core", "ZSetOperations", createInterfaceShim("org.springframework.data.redis.core.ZSetOperations",
                Arrays.asList("add", "remove", "range", "rangeByScore")));
        addShim("org.springframework.ai.chat.client", "ChatClient", createInterfaceShim("org.springframework.ai.chat.client.ChatClient",
                Arrays.asList("prompt", "call", "stream")));
        addShim("org.springframework.ai.chat.client.advisor", "ChatClientAdvisor", createInterfaceShim("org.springframework.ai.chat.client.advisor.ChatClientAdvisor"));
        addShim("org.springframework.ai.chat.model", "ChatModel", createInterfaceShim("org.springframework.ai.chat.model.ChatModel",
                Arrays.asList("call", "stream")));
        addShim("org.springframework.ai.chat.prompt", "Prompt", createClassShim("org.springframework.ai.chat.prompt.Prompt"));
        addShim("org.springframework.ai.chat.messages", "Message", createInterfaceShim("org.springframework.ai.chat.messages.Message",
                Arrays.asList("getContent", "getMessageType")));

        // Lombok shims (lombok.*) - Just annotations, no implementation needed
        addShim("lombok", "Data", createAnnotationShim("lombok.Data"));
        addShim("lombok", "Getter", createAnnotationShim("lombok.Getter"));
        addShim("lombok", "Setter", createAnnotationShim("lombok.Setter"));
        addShim("lombok", "ToString", createAnnotationShim("lombok.ToString"));
        addShim("lombok", "EqualsAndHashCode", createAnnotationShim("lombok.EqualsAndHashCode"));
        addShim("lombok", "NoArgsConstructor", createAnnotationShim("lombok.NoArgsConstructor"));
        addShim("lombok", "AllArgsConstructor", createAnnotationShim("lombok.AllArgsConstructor"));
        addShim("lombok", "RequiredArgsConstructor", createAnnotationShim("lombok.RequiredArgsConstructor"));
        addShim("lombok", "Builder", createAnnotationShim("lombok.Builder"));
        addShim("lombok", "Slf4j", createAnnotationShim("lombok.Slf4j"));
        addShim("lombok", "Log", createAnnotationShim("lombok.Log"));
        addShim("lombok", "Value", createAnnotationShim("lombok.Value"));
        addShim("lombok", "NonNull", createAnnotationShim("lombok.NonNull"));
        addShim("lombok", "SneakyThrows", createAnnotationShim("lombok.SneakyThrows"));

        // AspectJ shims (org.aspectj.lang.*)
        addShim("org.aspectj.lang", "ProceedingJoinPoint", createInterfaceShim("org.aspectj.lang.ProceedingJoinPoint",
                Arrays.asList("proceed", "getArgs", "getTarget", "getThis", "getSignature")));
        addShim("org.aspectj.lang", "JoinPoint", createInterfaceShim("org.aspectj.lang.JoinPoint",
                Arrays.asList("getArgs", "getTarget", "getThis", "getSignature")));
        addShim("org.aspectj.lang.annotation", "Around", createAnnotationShim("org.aspectj.lang.annotation.Around"));
        addShim("org.aspectj.lang.annotation", "Before", createAnnotationShim("org.aspectj.lang.annotation.Before"));
        addShim("org.aspectj.lang.annotation", "After", createAnnotationShim("org.aspectj.lang.annotation.After"));
        addShim("org.aspectj.lang.annotation", "AfterReturning", createAnnotationShim("org.aspectj.lang.annotation.AfterReturning"));
        addShim("org.aspectj.lang.annotation", "AfterThrowing", createAnnotationShim("org.aspectj.lang.annotation.AfterThrowing"));
        addShim("org.aspectj.lang.annotation", "Pointcut", createAnnotationShim("org.aspectj.lang.annotation.Pointcut"));
        addShim("org.aspectj.lang.annotation", "Aspect", createAnnotationShim("org.aspectj.lang.annotation.Aspect"));
        addShim("org.aspectj.lang.reflect", "MethodSignature", createInterfaceShim("org.aspectj.lang.reflect.MethodSignature",
                Arrays.asList("getMethod", "getReturnType", "getParameterTypes")));
        addShim("org.aspectj.lang.reflect", "CodeSignature", createInterfaceShim("org.aspectj.lang.reflect.CodeSignature",
                Arrays.asList("getParameterNames")));

        // Redisson shims (org.redisson.api.*)
        addShim("org.redisson.api", "RLock", createInterfaceShim("org.redisson.api.RLock",
                Arrays.asList("lock", "tryLock", "unlock", "isLocked", "isHeldByCurrentThread")));
        addShim("org.redisson.api", "RReadWriteLock", createInterfaceShim("org.redisson.api.RReadWriteLock",
                Arrays.asList("readLock", "writeLock")));
        addShim("org.redisson.api", "RBucket", createInterfaceShim("org.redisson.api.RBucket",
                Arrays.asList("get", "set", "trySet", "delete", "isExists")));
        addShim("org.redisson.api", "RMap", createInterfaceShim("org.redisson.api.RMap",
                Arrays.asList("get", "put", "remove", "containsKey", "size")));
        addShim("org.redisson.api", "RList", createInterfaceShim("org.redisson.api.RList",
                Arrays.asList("get", "add", "remove", "size", "contains")));
        addShim("org.redisson.api", "RSet", createInterfaceShim("org.redisson.api.RSet",
                Arrays.asList("add", "remove", "contains", "size")));
        addShim("org.redisson.api", "RedissonClient", createInterfaceShim("org.redisson.api.RedissonClient",
                Arrays.asList("getLock", "getBucket", "getMap", "getList", "getSet", "getReadWriteLock", "shutdown")));
        addShim("org.redisson", "Redisson", createClassShim("org.redisson.Redisson",
                Arrays.asList("create")));

        // Caffeine Cache shims (com.github.benmanes.caffeine.cache.*)
        addShim("com.github.benmanes.caffeine.cache", "Cache", createInterfaceShim("com.github.benmanes.caffeine.cache.Cache",
                Arrays.asList("get", "put", "invalidate", "invalidateAll", "getIfPresent", "putAll")));
        addShim("com.github.benmanes.caffeine.cache", "Caffeine", createClassShim("com.github.benmanes.caffeine.cache.Caffeine",
                Arrays.asList("newBuilder", "maximumSize", "expireAfterWrite", "expireAfterAccess", "build")));
        addShim("com.github.benmanes.caffeine.cache", "LoadingCache", createInterfaceShim("com.github.benmanes.caffeine.cache.LoadingCache",
                Arrays.asList("get", "getAll", "refresh", "invalidate")));

        // JWT shims (io.jsonwebtoken.*)
        addShim("io.jsonwebtoken", "Jwts", createClassShim("io.jsonwebtoken.Jwts",
                Arrays.asList("builder", "parser", "parserBuilder")));
        addShim("io.jsonwebtoken", "JwtBuilder", createInterfaceShim("io.jsonwebtoken.JwtBuilder",
                Arrays.asList("setSubject", "setIssuedAt", "setExpiration", "signWith", "compact")));
        addShim("io.jsonwebtoken", "JwtParser", createInterfaceShim("io.jsonwebtoken.JwtParser",
                Arrays.asList("setSigningKey", "parseClaimsJws", "parse")));
        addShim("io.jsonwebtoken", "Claims", createInterfaceShim("io.jsonwebtoken.Claims",
                Arrays.asList("getSubject", "getIssuedAt", "getExpiration", "get", "setSubject")));
        addShim("io.jsonwebtoken.security", "Keys", createClassShim("io.jsonwebtoken.security.Keys",
                Arrays.asList("hmacShaKeyFor", "secretKeyFor")));
        addShim("io.jsonwebtoken.security", "SigningKeyResolver", createInterfaceShim("io.jsonwebtoken.security.SigningKeyResolver",
                Arrays.asList("resolveSigningKey")));

        // ShardingSphere shims (org.apache.shardingsphere.*)
        addShim("org.apache.shardingsphere.driver.api.yaml", "YamlShardingSphereDataSourceFactory",
                createClassShim("org.apache.shardingsphere.driver.api.yaml.YamlShardingSphereDataSourceFactory",
                        Arrays.asList("createDataSource")));
        addShim("org.apache.shardingsphere.infra.url.core", "URLCenter",
                createClassShim("org.apache.shardingsphere.infra.url.core.URLCenter"));
        addShim("org.apache.shardingsphere.infra.url.core", "URLCenterFactory",
                createClassShim("org.apache.shardingsphere.infra.url.core.URLCenterFactory",
                        Arrays.asList("newInstance")));

        // Elasticsearch Client shims (co.elastic.clients.elasticsearch.*)
        addShim("co.elastic.clients.elasticsearch", "ElasticsearchClient",
                createInterfaceShim("co.elastic.clients.elasticsearch.ElasticsearchClient",
                        Arrays.asList("search", "index", "get", "delete", "update")));
        addShim("co.elastic.clients.elasticsearch._types", "Query",
                createClassShim("co.elastic.clients.elasticsearch._types.Query"));
        addShim("co.elastic.clients.elasticsearch._types", "query_dsl",
                createClassShim("co.elastic.clients.elasticsearch._types.query_dsl"));
        addShim("co.elastic.clients.elasticsearch.core", "SearchRequest",
                createClassShim("co.elastic.clients.elasticsearch.core.SearchRequest",
                        Arrays.asList("index", "query")));
        addShim("co.elastic.clients.elasticsearch.core", "SearchResponse",
                createClassShim("co.elastic.clients.elasticsearch.core.SearchResponse",
                        Arrays.asList("hits", "took", "timedOut")));
        addShim("co.elastic.clients.elasticsearch.core.search", "HitsMetadata",
                createClassShim("co.elastic.clients.elasticsearch.core.search.HitsMetadata",
                        Arrays.asList("hits", "total")));
        addShim("co.elastic.clients.elasticsearch.core.search", "Hit",
                createClassShim("co.elastic.clients.elasticsearch.core.search.Hit",
                        Arrays.asList("source", "id", "index")));

        // Reactor/Reactive Types shims (reactor.core.publisher.*)
        addShim("reactor.core.publisher", "Mono", createClassShim("reactor.core.publisher.Mono",
                Arrays.asList("just", "error", "empty", "fromCallable", "zip", "map", "flatMap", "filter",
                        "block", "subscribe", "doOnNext", "doOnError", "onErrorReturn", "onErrorResume")));
        addShim("reactor.core.publisher", "Flux", createClassShim("reactor.core.publisher.Flux",
                Arrays.asList("just", "error", "empty", "fromIterable", "fromArray", "zip", "map", "flatMap",
                        "filter", "collectList", "blockFirst", "subscribe", "doOnNext", "doOnError")));
        addShim("reactor.core.publisher", "Publisher", createInterfaceShim("reactor.core.publisher.Publisher",
                Arrays.asList("subscribe")));
        addShim("org.reactivestreams", "Publisher", createInterfaceShim("org.reactivestreams.Publisher",
                Arrays.asList("subscribe")));
        addShim("org.reactivestreams", "Subscriber", createInterfaceShim("org.reactivestreams.Subscriber",
                Arrays.asList("onSubscribe", "onNext", "onError", "onComplete")));
        addShim("reactor.util.context", "Context", createInterfaceShim("reactor.util.context.Context",
                Arrays.asList("get", "put", "putAll", "isEmpty")));
        addShim("reactor.util.context", "ContextView", createInterfaceShim("reactor.util.context.ContextView",
                Arrays.asList("get", "hasKey", "isEmpty")));

        // Apache Camel shims (org.apache.camel.*)
        addShim("org.apache.camel", "CamelContext", createInterfaceShim("org.apache.camel.CamelContext",
                Arrays.asList("createProducerTemplate", "createConsumerTemplate", "addRoutes", "start", "stop", "getRoute", "getRoutes")));
        addShim("org.apache.camel", "ProducerTemplate", createInterfaceShim("org.apache.camel.ProducerTemplate",
                Arrays.asList("sendBody", "send", "requestBody", "request", "sendBodyAndHeader", "asyncSend")));
        addShim("org.apache.camel", "ConsumerTemplate", createInterfaceShim("org.apache.camel.ConsumerTemplate",
                Arrays.asList("receiveBody", "receive", "receiveNoWait", "receiveBodyNoWait")));
        addShim("org.apache.camel", "Exchange", createInterfaceShim("org.apache.camel.Exchange",
                Arrays.asList("getIn", "getOut", "getProperty", "setProperty", "getPattern", "getExchangeId")));
        addShim("org.apache.camel", "Message", createInterfaceShim("org.apache.camel.Message",
                Arrays.asList("getBody", "setBody", "getHeader", "setHeader", "getHeaders", "setHeaders", "getBodyAs")));
        addShim("org.apache.camel", "RouteBuilder", createClassShim("org.apache.camel.RouteBuilder",
                Arrays.asList("configure", "from", "to")));
        addShim("org.apache.camel", "Processor", createInterfaceShim("org.apache.camel.Processor",
                Arrays.asList("process")));
        addShim("org.apache.camel", "Endpoint", createInterfaceShim("org.apache.camel.Endpoint",
                Arrays.asList("createProducer", "createConsumer", "createExchange")));
        addShim("org.apache.camel", "Component", createInterfaceShim("org.apache.camel.Component",
                Arrays.asList("createEndpoint")));
        addShim("org.apache.camel.builder", "RouteBuilder", createClassShim("org.apache.camel.builder.RouteBuilder",
                Arrays.asList("configure", "from", "to")));

        // MyBatis Plus Extension shims (com.baomidou.mybatisplus.extension.*)
        addShim("com.baomidou.mybatisplus.extension.service", "IService",
                createInterfaceShim("com.baomidou.mybatisplus.extension.service.IService",
                        Arrays.asList("save", "saveBatch", "removeById", "removeByIds", "updateById", "updateBatchById",
                                "getById", "listByIds", "list", "count", "page")));
        addShim("com.baomidou.mybatisplus.extension.service.impl", "ServiceImpl",
                createClassShim("com.baomidou.mybatisplus.extension.service.impl.ServiceImpl",
                        Arrays.asList("save", "saveBatch", "removeById", "removeByIds", "updateById", "updateBatchById",
                                "getById", "listByIds", "list", "count", "page", "getBaseMapper")));
        addShim("com.baomidou.mybatisplus.extension.activerecord", "Model",
                createClassShim("com.baomidou.mybatisplus.extension.activerecord.Model",
                        Arrays.asList("insert", "updateById", "deleteById", "selectById", "selectList", "selectPage")));
        addShim("com.baomidou.mybatisplus.extension.api", "IBaseService",
                createInterfaceShim("com.baomidou.mybatisplus.extension.api.IBaseService",
                        Arrays.asList("save", "removeById", "updateById", "getById", "list")));

        // Spring Cloud / Netflix OSS shims (org.springframework.cloud.*, com.netflix.*)
        addShim("org.springframework.cloud.openfeign", "FeignClient",
                createAnnotationShim("org.springframework.cloud.openfeign.FeignClient"));
        addShim("org.springframework.cloud.netflix.eureka", "EurekaClient",
                createInterfaceShim("org.springframework.cloud.netflix.eureka.EurekaClient",
                        Arrays.asList("getApplications", "getInstancesByVipAddress", "getNextServerFromEureka")));
        addShim("org.springframework.cloud.netflix.ribbon", "RibbonClient",
                createAnnotationShim("org.springframework.cloud.netflix.ribbon.RibbonClient"));
        addShim("org.springframework.cloud.config.client", "ConfigClientProperties",
                createClassShim("org.springframework.cloud.config.client.ConfigClientProperties",
                        Arrays.asList("getUri", "getName", "getProfile", "getLabel")));
        addShim("com.netflix.hystrix", "HystrixCommand",
                createClassShim("com.netflix.hystrix.HystrixCommand",
                        Arrays.asList("execute", "queue", "observe", "toObservable")));
        addShim("com.netflix.hystrix", "HystrixObservableCommand",
                createClassShim("com.netflix.hystrix.HystrixObservableCommand",
                        Arrays.asList("observe", "toObservable")));
        addShim("com.netflix.hystrix.contrib.javanica.annotation", "HystrixCommand",
                createAnnotationShim("com.netflix.hystrix.contrib.javanica.annotation.HystrixCommand"));
        addShim("org.springframework.cloud.gateway.filter", "GatewayFilter",
                createInterfaceShim("org.springframework.cloud.gateway.filter.GatewayFilter",
                        Arrays.asList("filter")));
        addShim("org.springframework.cloud.gateway.filter", "GatewayFilterChain",
                createInterfaceShim("org.springframework.cloud.gateway.filter.GatewayFilterChain",
                        Arrays.asList("filter")));

        // OAuth / HTTP Client shims
        addShim("me.zhyd.oauth", "AuthRequest",
                createInterfaceShim("me.zhyd.oauth.AuthRequest",
                        Arrays.asList("authorize", "login", "revoke")));
        addShim("me.zhyd.oauth.config", "AuthConfig",
                createClassShim("me.zhyd.oauth.config.AuthConfig",
                        Arrays.asList("getClientId", "getClientSecret", "getRedirectUri")));
        addShim("okhttp3", "OkHttpClient",
                createClassShim("okhttp3.OkHttpClient",
                        Arrays.asList("newCall", "newBuilder")));
        addShim("okhttp3", "Request",
                createClassShim("okhttp3.Request",
                        Arrays.asList("newBuilder", "url", "method", "body")));
        addShim("okhttp3", "Response",
                createClassShim("okhttp3.Response",
                        Arrays.asList("body", "code", "message", "isSuccessful")));
        addShim("okhttp3", "Call",
                createInterfaceShim("okhttp3.Call",
                        Arrays.asList("execute", "enqueue", "cancel")));
        addShim("org.apache.http.client", "HttpClient",
                createInterfaceShim("org.apache.http.client.HttpClient",
                        Arrays.asList("execute")));
        addShim("org.apache.http.client.methods", "HttpGet",
                createClassShim("org.apache.http.client.methods.HttpGet"));
        addShim("org.apache.http.client.methods", "HttpPost",
                createClassShim("org.apache.http.client.methods.HttpPost",
                        Arrays.asList("setEntity")));
        addShim("org.apache.http.impl.client", "CloseableHttpClient",
                createInterfaceShim("org.apache.http.impl.client.CloseableHttpClient"));
        addShim("org.apache.http.impl.client", "HttpClients",
                createClassShim("org.apache.http.impl.client.HttpClients",
                        Arrays.asList("createDefault", "createSystem")));

        // Job Scheduling Framework shims (tech.powerjob.*)
        addShim("tech.powerjob.worker", "PowerJobWorker",
                createClassShim("tech.powerjob.worker.PowerJobWorker",
                        Arrays.asList("init", "destroy")));
        addShim("tech.powerjob.worker.core", "Processor",
                createInterfaceShim("tech.powerjob.worker.core.Processor",
                        Arrays.asList("process")));
        addShim("tech.powerjob.worker.core", "TaskContext",
                createInterfaceShim("tech.powerjob.worker.core.TaskContext",
                        Arrays.asList("getJobId", "getInstanceId", "getJobParams", "getInstanceParams")));
        addShim("tech.powerjob.common", "PowerJob",
                createClassShim("tech.powerjob.common.PowerJob",
                        Arrays.asList("init", "run")));

        // Monitoring / Metrics Framework shims (cn.hippo4j.*)
        addShim("cn.hippo4j.core.executor", "DynamicThreadPoolExecutor",
                createClassShim("cn.hippo4j.core.executor.DynamicThreadPoolExecutor",
                        Arrays.asList("execute", "submit", "shutdown")));
        addShim("cn.hippo4j.core.executor", "ThreadPoolExecutor",
                createClassShim("cn.hippo4j.core.executor.ThreadPoolExecutor",
                        Arrays.asList("execute", "submit", "shutdown")));
        addShim("cn.hippo4j.core.config", "BootstrapProperties",
                createClassShim("cn.hippo4j.core.config.BootstrapProperties",
                        Arrays.asList("getServerAddr", "getNamespace", "getItemId")));
        addShim("cn.hippo4j.core.toolkit", "ThreadPoolExecutorBuilder",
                createClassShim("cn.hippo4j.core.toolkit.ThreadPoolExecutorBuilder",
                        Arrays.asList("build", "corePoolSize", "maximumPoolSize", "keepAliveTime")));
    }

    /**
     * Create a class shim with custom return types for specific methods.
     */
    private ShimDefinition createClassShimWithReturnTypes(String fqn, Map<String, String> methodReturnTypes) {
        return new ShimDefinition(fqn, ShimDefinition.Kind.CLASS, new ArrayList<>(methodReturnTypes.keySet()));
    }

    /**
     * Add a shim definition.
     */
    private void addShim(String packageName, String className, ShimDefinition shim) {
        String fqn = packageName + "." + className;
        shimDefinitions.put(fqn, shim);
    }

    /**
     * Create a class shim.
     */
    private ShimDefinition createClassShim(String fqn) {
        return createClassShim(fqn, Collections.emptyList());
    }

    /**
     * Create a class shim with methods.
     */
    private ShimDefinition createClassShim(String fqn, List<String> methodNames) {
        return new ShimDefinition(fqn, ShimDefinition.Kind.CLASS, methodNames);
    }

    /**
     * Create an interface shim.
     */
    private ShimDefinition createInterfaceShim(String fqn) {
        return createInterfaceShim(fqn, Collections.emptyList());
    }

    /**
     * Create an interface shim with methods.
     */
    private ShimDefinition createInterfaceShim(String fqn, List<String> methodNames) {
        return new ShimDefinition(fqn, ShimDefinition.Kind.INTERFACE, methodNames);
    }

    /**
     * Create an annotation shim.
     */
    private ShimDefinition createAnnotationShim(String fqn) {
        return new ShimDefinition(fqn, ShimDefinition.Kind.ANNOTATION, Collections.emptyList());
    }

    /**
     * Create an enum shim.
     */
    private ShimDefinition createEnumShim(String fqn) {
        return new ShimDefinition(fqn, ShimDefinition.Kind.ENUM, Collections.emptyList());
    }

    /**
     * Generate shim classes for all registered definitions.
     * Only generates shims that are not already present in the model.
     * @deprecated Use generateShimsForReferencedTypes instead for better control
     */
    @Deprecated
    public int generateShims() {
        return generateShimsForReferencedTypes(Collections.emptySet());
    }

    /**
     * Generate shim classes only for types that are referenced and missing.
     * If referencedTypes is empty, generates all shims (backward compatibility).
     */
    public int generateShimsForReferencedTypes(Set<String> referencedTypes) {
        int generated = 0;
        boolean generateAll = referencedTypes == null || referencedTypes.isEmpty();

        for (Map.Entry<String, ShimDefinition> entry : shimDefinitions.entrySet()) {
            String fqn = entry.getKey();
            ShimDefinition shim = entry.getValue();

            // If we have a set of referenced types, only generate shims for those
            if (!generateAll && !referencedTypes.contains(fqn)) {
                // Also check by simple name (in case FQN differs)
                String simpleName = fqn.substring(fqn.lastIndexOf('.') + 1);
                boolean matchesSimple = referencedTypes.stream()
                        .anyMatch(ref -> ref.endsWith("." + simpleName) || ref.equals(simpleName));
                if (!matchesSimple) {
                    continue; // Not referenced, skip
                }
            }

            // Check if type already exists in the model
            CtType<?> existing = factory.Type().get(fqn);
            if (existing != null) {
                continue; // Already exists, skip
            }

            // Generate the shim
            if (generateShim(shim)) {
                generated++;
            }
        }

        // Generate *Grpc classes for any referenced *Grpc types
        if (!generateAll && referencedTypes != null) {
            for (String refType : referencedTypes) {
                if (refType.endsWith("Grpc") && !refType.contains("StreamObserver")) {
                    // This is a *Grpc class reference
                    if (factory.Type().get(refType) == null) {
                        if (generateGrpcShim(refType)) {
                            generated++;
                        }
                    }
                }
            }

            // Handle shaded protobuf packages (e.g., org.apache.hbase.thirdparty.com.google.protobuf.*)
            // Map shaded protobuf types to standard protobuf shims
            for (String refType : referencedTypes) {
                if (refType.contains(".protobuf.") && !refType.startsWith("com.google.protobuf.")) {
                    // This is a shaded protobuf package
                    String className = refType.substring(refType.lastIndexOf('.') + 1);
                    String standardFqn = "com.google.protobuf." + className;

                    // Check if we have a shim definition for the standard protobuf type
                    ShimDefinition standardShim = shimDefinitions.get(standardFqn);
                    if (standardShim != null) {
                        // Generate shim in the shaded package
                        if (factory.Type().get(refType) == null) {
                            if (generateShadedProtobufShim(refType, standardShim)) {
                                generated++;
                            }
                        }
                    } else {
                        // Handle protobuf-specific types that don't have standard equivalents
                        // e.g., *OrBuilder, CodedOutputStream, etc.
                        if (className.endsWith("OrBuilder")) {
                            // Generate an interface shim for *OrBuilder types
                            if (factory.Type().get(refType) == null) {
                                ShimDefinition orBuilderShim = createInterfaceShim(refType,
                                        Arrays.asList("toByteArray", "getSerializedSize"));
                                if (generateShadedProtobufShim(refType, orBuilderShim)) {
                                    generated++;
                                }
                            }
                        } else if ("CodedOutputStream".equals(className)) {
                            // Generate a class shim for CodedOutputStream
                            if (factory.Type().get(refType) == null) {
                                ShimDefinition codedOutputStreamShim = createClassShim(refType,
                                        Arrays.asList("writeBytes", "writeString", "writeInt32"));
                                if (generateShadedProtobufShim(refType, codedOutputStreamShim)) {
                                    generated++;
                                }
                            }
                        }
                    }
                }
            }
        }

        return generated;
    }

    /**
     * Generate a shim for a shaded protobuf type by copying the structure from the standard protobuf shim.
     */
    private boolean generateShadedProtobufShim(String shadedFqn, ShimDefinition standardShim) {
        try {
            // Remove "unknown." prefix if present (types might be resolved as unknown.*)
            String actualFqn = shadedFqn;
            if (shadedFqn.startsWith("unknown.")) {
                actualFqn = shadedFqn.substring("unknown.".length());
            }

            int lastDot = actualFqn.lastIndexOf('.');
            String packageName = lastDot >= 0 ? actualFqn.substring(0, lastDot) : "";
            String className = lastDot >= 0 ? actualFqn.substring(lastDot + 1) : actualFqn;

            // Don't create packages in "unknown" - use the actual package
            if (packageName.startsWith("unknown.")) {
                packageName = packageName.substring("unknown.".length());
            }

            CtPackage pkg = factory.Package().getOrCreate(packageName);

            CtType<?> type;
            switch (standardShim.getKind()) {
                case CLASS:
                    type = factory.Class().create(pkg, className);
                    break;
                case INTERFACE:
                    type = factory.Interface().create(pkg, className);
                    break;
                case ANNOTATION:
                    type = factory.Annotation().create(pkg, className);
                    break;
                case ENUM:
                    type = factory.Enum().create(pkg, className);
                    break;
                default:
                    return false;
            }

            type.addModifier(ModifierKind.PUBLIC);

            // Special handling for GeneratedMessage and GeneratedMessageLite - make them generic
            if (("GeneratedMessage".equals(className) || "GeneratedMessageLite".equals(className))
                    && type instanceof CtClass) {
                CtClass<?> cls = (CtClass<?>) type;
                // Add type parameters <T, M>
                CtTypeParameter typeParam1 = factory.Core().createTypeParameter();
                typeParam1.setSimpleName("T");
                cls.addFormalCtTypeParameter(typeParam1);

                CtTypeParameter typeParam2 = factory.Core().createTypeParameter();
                typeParam2.setSimpleName("M");
                cls.addFormalCtTypeParameter(typeParam2);
            }

            // Add methods if specified
            if (!standardShim.getMethodNames().isEmpty()) {
                for (String methodName : standardShim.getMethodNames()) {
                    addShimMethod(type, methodName);
                }
            }

            return true;
        } catch (Throwable t) {
            System.err.println("[ShimGenerator] Failed to generate shaded protobuf shim for " + shadedFqn + ": " + t.getMessage());
            return false;
        }
    }

    /**
     * Generate a minimal *Grpc class shim.
     */
    private boolean generateGrpcShim(String grpcClassName) {
        try {
            int lastDot = grpcClassName.lastIndexOf('.');
            String packageName = lastDot >= 0 ? grpcClassName.substring(0, lastDot) : "";
            String className = lastDot >= 0 ? grpcClassName.substring(lastDot + 1) : grpcClassName;

            // Extract service name (e.g., MetadataGrpc -> Metadata)
            String serviceName = className.replace("Grpc", "");

            CtPackage pkg = factory.Package().getOrCreate(packageName);
            CtClass<?> grpcClass = factory.Class().create(pkg, className);
            grpcClass.addModifier(ModifierKind.PUBLIC);
            grpcClass.addModifier(ModifierKind.FINAL);

            // Add newStub method: public static ServiceNameStub newStub(Channel channel)
            String stubQn = packageName.isEmpty() ? (serviceName + "Stub") : (packageName + "." + serviceName + "Stub");
            CtTypeReference<?> stubType = factory.Type().createReference(stubQn);
            CtTypeReference<?> channelType = factory.Type().createReference("io.grpc.Channel");

            // Create method with simple body (return null for now - will be fixed by stubbing)
            CtMethod<?> newStubMethod = factory.Method().create(
                    grpcClass,
                    Set.of(ModifierKind.PUBLIC, ModifierKind.STATIC),
                    stubType,
                    "newStub",
                    makeShimParams(Arrays.asList(channelType)),
                    Collections.emptySet()
            );

            // Add simple body: return null; (will be properly stubbed later if needed)
            CtBlock<?> body = factory.Core().createBlock();
            CtReturn<?> ret = factory.Core().createReturn();
            ret.setReturnedExpression(factory.Code().createLiteral(null));
            body.addStatement(ret);
            newStubMethod.setBody(body);

            // Also create the Stub class if it doesn't exist
            if (factory.Type().get(stubQn) == null) {
                CtClass<?> stubClass = factory.Class().create(pkg, serviceName + "Stub");
                stubClass.addModifier(ModifierKind.PUBLIC);
                stubClass.addModifier(ModifierKind.STATIC);
                // Add constructor
                CtConstructor<?> ctor = factory.Constructor().create(
                        stubClass,
                        Set.of(ModifierKind.PUBLIC),
                        makeShimParams(Arrays.asList(channelType)),
                        Collections.emptySet()
                );
                ctor.setBody(factory.Core().createBlock());
            }

            return true;
        } catch (Exception e) {
            System.err.println("Failed to generate gRPC shim for " + grpcClassName + ": " + e.getMessage());
            return false;
        }
    }

    /**
     * Create parameters for shim methods.
     */
    private List<CtParameter<?>> makeShimParams(List<CtTypeReference<?>> paramTypes) {
        List<CtParameter<?>> params = new ArrayList<>();
        for (int i = 0; i < paramTypes.size(); i++) {
            CtParameter<?> param = factory.Core().createParameter();
            param.setType(paramTypes.get(i));
            param.setSimpleName("arg" + i);
            params.add(param);
        }
        return params;
    }

    /**
     * Generate a single shim class.
     */
    private boolean generateShim(ShimDefinition shim) {
        try {
            String fqn = shim.getFqn();
            int lastDot = fqn.lastIndexOf('.');
            String packageName = lastDot >= 0 ? fqn.substring(0, lastDot) : "";
            String className = lastDot >= 0 ? fqn.substring(lastDot + 1) : fqn;

            CtPackage pkg = factory.Package().getOrCreate(packageName);

            CtType<?> type;
            switch (shim.getKind()) {
                case CLASS:
                    type = factory.Class().create(pkg, className);
                    break;
                case INTERFACE:
                    type = factory.Interface().create(pkg, className);
                    break;
                case ANNOTATION:
                    type = factory.Annotation().create(pkg, className);
                    break;
                case ENUM:
                    type = factory.Enum().create(pkg, className);
                    break;
                default:
                    return false;
            }

            type.addModifier(ModifierKind.PUBLIC);

            // Special handling for GeneratedMessage and GeneratedMessageLite - make them generic
            if (("com.google.protobuf.GeneratedMessageLite".equals(fqn) ||
                    "com.google.protobuf.GeneratedMessage".equals(fqn)) && type instanceof CtClass) {
                CtClass<?> cls = (CtClass<?>) type;
                // Add type parameters <T, M>
                CtTypeParameter typeParam1 = factory.Core().createTypeParameter();
                typeParam1.setSimpleName("T");
                cls.addFormalCtTypeParameter(typeParam1);

                CtTypeParameter typeParam2 = factory.Core().createTypeParameter();
                typeParam2.setSimpleName("M");
                cls.addFormalCtTypeParameter(typeParam2);
            }

            // Special handling for reactive types - make them generic (Mono<T>, Flux<T>)
            if (("reactor.core.publisher.Mono".equals(fqn) ||
                    "reactor.core.publisher.Flux".equals(fqn)) && type instanceof CtClass) {
                CtClass<?> cls = (CtClass<?>) type;
                // Add type parameter <T>
                CtTypeParameter typeParam = factory.Core().createTypeParameter();
                typeParam.setSimpleName("T");
                cls.addFormalCtTypeParameter(typeParam);
            }

            // Special handling for generic shims - RedisTemplate<K, V>, BaseMapper<T>
            if ("org.springframework.data.redis.core.RedisTemplate".equals(fqn) && type instanceof CtClass) {
                CtClass<?> cls = (CtClass<?>) type;
                // Add type parameters <K, V>
                CtTypeParameter typeParamK = factory.Core().createTypeParameter();
                typeParamK.setSimpleName("K");
                cls.addFormalCtTypeParameter(typeParamK);
                CtTypeParameter typeParamV = factory.Core().createTypeParameter();
                typeParamV.setSimpleName("V");
                cls.addFormalCtTypeParameter(typeParamV);
            }
            if ("com.baomidou.mybatisplus.core.mapper.BaseMapper".equals(fqn) && type instanceof CtInterface) {
                CtInterface<?> iface = (CtInterface<?>) type;
                // Add type parameter <T>
                CtTypeParameter typeParam = factory.Core().createTypeParameter();
                typeParam.setSimpleName("T");
                iface.addFormalCtTypeParameter(typeParam);
            }

            // Special handling for ANTLR ParseTreeVisitor - make it generic ParseTreeVisitor<T>
            if ("org.antlr.v4.runtime.tree.ParseTreeVisitor".equals(fqn) && type instanceof CtInterface) {
                CtInterface<?> iface = (CtInterface<?>) type;
                // Add type parameter <T>
                CtTypeParameter typeParam = factory.Core().createTypeParameter();
                typeParam.setSimpleName("T");
                iface.addFormalCtTypeParameter(typeParam);
            }

            // Special handling for Guava Immutable collections - make them generic
            if ("com.google.common.collect.ImmutableList".equals(fqn) && type instanceof CtClass) {
                CtClass<?> cls = (CtClass<?>) type;
                // Add type parameter <T>
                CtTypeParameter typeParam = factory.Core().createTypeParameter();
                typeParam.setSimpleName("T");
                cls.addFormalCtTypeParameter(typeParam);
            }
            if ("com.google.common.collect.ImmutableSet".equals(fqn) && type instanceof CtClass) {
                CtClass<?> cls = (CtClass<?>) type;
                // Add type parameter <T>
                CtTypeParameter typeParam = factory.Core().createTypeParameter();
                typeParam.setSimpleName("T");
                cls.addFormalCtTypeParameter(typeParam);
            }
            if ("com.google.common.collect.ImmutableMap".equals(fqn) && type instanceof CtClass) {
                CtClass<?> cls = (CtClass<?>) type;
                // Add type parameters <K, V>
                CtTypeParameter typeParamK = factory.Core().createTypeParameter();
                typeParamK.setSimpleName("K");
                cls.addFormalCtTypeParameter(typeParamK);
                CtTypeParameter typeParamV = factory.Core().createTypeParameter();
                typeParamV.setSimpleName("V");
                cls.addFormalCtTypeParameter(typeParamV);
            }

            // Special handling for MyBatis Plus IService and ServiceImpl - make them generic
            if ("com.baomidou.mybatisplus.extension.service.IService".equals(fqn) && type instanceof CtInterface) {
                CtInterface<?> iface = (CtInterface<?>) type;
                // Add type parameter <T>
                CtTypeParameter typeParam = factory.Core().createTypeParameter();
                typeParam.setSimpleName("T");
                iface.addFormalCtTypeParameter(typeParam);
            }
            if ("com.baomidou.mybatisplus.extension.service.impl.ServiceImpl".equals(fqn) && type instanceof CtClass) {
                CtClass<?> cls = (CtClass<?>) type;
                // Add type parameters <M extends BaseMapper<T>, T>
                // For simplicity, we'll add T first, then M
                CtTypeParameter typeParamT = factory.Core().createTypeParameter();
                typeParamT.setSimpleName("T");
                cls.addFormalCtTypeParameter(typeParamT);
                CtTypeParameter typeParamM = factory.Core().createTypeParameter();
                typeParamM.setSimpleName("M");
                cls.addFormalCtTypeParameter(typeParamM);
            }
            if ("com.baomidou.mybatisplus.extension.activerecord.Model".equals(fqn) && type instanceof CtClass) {
                CtClass<?> cls = (CtClass<?>) type;
                // Add type parameter <T>
                CtTypeParameter typeParam = factory.Core().createTypeParameter();
                typeParam.setSimpleName("T");
                cls.addFormalCtTypeParameter(typeParam);
            }

            // Add methods if specified
            if (!shim.getMethodNames().isEmpty()) {
                for (String methodName : shim.getMethodNames()) {
                    addShimMethod(type, methodName);
                }
            }

            return true;
        } catch (Exception e) {
            System.err.println("Failed to generate shim for " + shim.getFqn() + ": " + e.getMessage());
            return false;
        }
    }

    /**
     * Add a simple method to a shim type.
     */
    private void addShimMethod(CtType<?> type, String methodName) {
        try {
            // IMPORTANT: Annotations should never have methods added to them
            // Annotation elements are different from methods and are handled separately
            if (type instanceof CtAnnotationType) {
                return; // Skip - annotations cannot have methods
            }

            // Check if method already exists in this type or parent classes
            if (type instanceof CtClass) {
                CtClass<?> cls = (CtClass<?>) type;
                // Check in current class
                if (cls.getMethod(methodName) != null) {
                    return; // Method already exists, skip
                }
                // Check in parent class (Object has toString, equals, hashCode)
                CtTypeReference<?> superclass = cls.getSuperclass();
                if (superclass != null) {
                    CtType<?> superType = superclass.getTypeDeclaration();
                    if (superType instanceof CtClass) {
                        CtClass<?> superClass = (CtClass<?>) superType;
                        if (superClass.getMethod(methodName) != null) {
                            // Method exists in parent - only add if we need to override with different signature
                            // For toString, equals, hashCode - we should NOT add them as they already exist
                            if ("toString".equals(methodName) || "equals".equals(methodName) || "hashCode".equals(methodName)) {
                                return; // Skip - already in Object
                            }
                        }
                    }
                }
            } else if (type instanceof CtInterface) {
                CtInterface<?> iface = (CtInterface<?>) type;
                if (iface.getMethod(methodName) != null) {
                    return; // Method already exists, skip
                }
            }

            // Determine return type based on method name
            CtTypeReference<?> returnType = inferReturnType(methodName, type);

            // Special handling for ANTLR ParseTreeVisitor - visit() methods return T
            String typeQn = type.getQualifiedName();
            if (typeQn != null && typeQn.equals("org.antlr.v4.runtime.tree.ParseTreeVisitor") &&
                    type instanceof CtInterface) {
                if ("visit".equals(methodName)) {
                    // visit() method returns the type parameter T
                    CtInterface<?> iface = (CtInterface<?>) type;
                    if (iface.getFormalCtTypeParameters().size() > 0) {
                        CtTypeParameterReference typeParamRef = factory.Core().createTypeParameterReference();
                        typeParamRef.setSimpleName("T");
                        returnType = typeParamRef;
                    }
                }
            }

            // Special handling for reactive types (Mono, Flux)
            if (typeQn != null && (typeQn.equals("reactor.core.publisher.Mono") ||
                    typeQn.equals("reactor.core.publisher.Flux"))) {
                if (type instanceof CtClass) {
                    CtClass<?> reactiveClass = (CtClass<?>) type;

                    // Static factory methods (just, error, empty, fromCallable) return Mono<T> or Flux<T>
                    if ("just".equals(methodName) || "error".equals(methodName) ||
                            "empty".equals(methodName) || "fromCallable".equals(methodName) ||
                            "fromIterable".equals(methodName) || "fromArray".equals(methodName)) {
                        // Return the generic type itself (Mono<T> or Flux<T>)
                        CtTypeReference<?> genericType = reactiveClass.getReference().clone();
                        if (genericType != null) {
                            // Ensure it has the type parameter T
                            if (genericType.getActualTypeArguments().isEmpty()) {
                                CtTypeParameterReference typeParamRef = factory.Core().createTypeParameterReference();
                                typeParamRef.setSimpleName("T");
                                genericType.addActualTypeArgument(typeParamRef);
                            }
                            returnType = genericType;
                        }
                    }
                    // Instance methods that return the same reactive type (map, flatMap, filter, etc.)
                    else if ("map".equals(methodName) || "flatMap".equals(methodName) ||
                            "filter".equals(methodName) || "doOnNext".equals(methodName) ||
                            "doOnError".equals(methodName) || "onErrorReturn".equals(methodName) ||
                            "onErrorResume".equals(methodName)) {
                        // Return the generic type itself (Mono<T> or Flux<T>)
                        CtTypeReference<?> genericType = reactiveClass.getReference().clone();
                        if (genericType != null) {
                            // Ensure it has the type parameter T
                            if (genericType.getActualTypeArguments().isEmpty()) {
                                CtTypeParameterReference typeParamRef = factory.Core().createTypeParameterReference();
                                typeParamRef.setSimpleName("T");
                                genericType.addActualTypeArgument(typeParamRef);
                            }
                            returnType = genericType;
                        }
                    }
                    // Blocking methods (block, blockFirst) return T
                    else if ("block".equals(methodName) || "blockFirst".equals(methodName)) {
                        // Return the type parameter T
                        CtTypeParameterReference typeParamRef = factory.Core().createTypeParameterReference();
                        typeParamRef.setSimpleName("T");
                        returnType = typeParamRef;
                    }
                    // collectList for Flux returns Mono<List<T>>
                    else if ("collectList".equals(methodName) && typeQn.equals("reactor.core.publisher.Flux")) {
                        // Return Mono<List<T>>
                        CtTypeReference<?> monoType = factory.Type().createReference("reactor.core.publisher.Mono");
                        CtTypeReference<?> listType = factory.Type().createReference("java.util.List");
                        CtTypeParameterReference typeParamRef = factory.Core().createTypeParameterReference();
                        typeParamRef.setSimpleName("T");
                        listType.addActualTypeArgument(typeParamRef);
                        monoType.addActualTypeArgument(listType);
                        returnType = monoType;
                    }
                }
            }

            // Special handling for Protocol Buffers methods (both standard and shaded packages)
            if (typeQn != null && (typeQn.contains("protobuf") || typeQn.contains("thirdparty"))) {
                if ("toByteArray".equals(methodName)) {
                    returnType = factory.Type().createArrayReference(factory.Type().BYTE_PRIMITIVE);
                } else if ("getSerializedSize".equals(methodName)) {
                    returnType = factory.Type().INTEGER_PRIMITIVE;
                } else if ("parseFrom".equals(methodName)) {
                    // parseFrom returns the type itself
                    returnType = type.getReference();
                }
            }

            // Special handling for SLF4J MDC methods
            if (typeQn != null && typeQn.equals("org.slf4j.MDC")) {
                if ("getCopyOfContextMap".equals(methodName)) {
                    // getCopyOfContextMap() returns Map<String, String>
                    CtTypeReference<?> mapType = factory.Type().createReference("java.util.Map");
                    mapType.addActualTypeArgument(factory.Type().createReference("java.lang.String"));
                    mapType.addActualTypeArgument(factory.Type().createReference("java.lang.String"));
                    returnType = mapType;
                } else if ("setContextMap".equals(methodName)) {
                    // setContextMap(Map<String, String> contextMap) returns void
                    returnType = factory.Type().VOID_PRIMITIVE;
                } else if ("clear".equals(methodName)) {
                    // clear() returns void
                    returnType = factory.Type().VOID_PRIMITIVE;
                }
            }

            // Special handling for Hadoop Configuration.get() - should return String, not boolean
            if (typeQn != null && typeQn.contains("hadoop.conf.Configuration")) {
                if ("get".equals(methodName)) {
                    // Configuration.get(String key) returns String
                    // Configuration.get(String key, String defaultValue) returns String
                    returnType = factory.Type().createReference("java.lang.String");
                }
            }

            // Determine parameter types based on method name
            List<CtTypeReference<?>> paramTypes = inferParameterTypes(methodName, typeQn);

            Set<ModifierKind> mods = new HashSet<>();
            mods.add(ModifierKind.PUBLIC);

            // Check if this is a utility class (like StringUtils, ObjectUtils) - methods should be static
            boolean isUtilityClass = isUtilityClass(type);
            if (isUtilityClass && type instanceof CtClass) {
                mods.add(ModifierKind.STATIC);
            }

            if (type instanceof CtInterface && !(type instanceof CtAnnotationType)) {
                mods.add(ModifierKind.ABSTRACT);
            }

            // Create parameters
            List<CtParameter<?>> params = new ArrayList<>();
            for (int i = 0; i < paramTypes.size(); i++) {
                CtParameter<?> param = factory.Core().createParameter();
                param.setType(paramTypes.get(i));
                param.setSimpleName("arg" + i);
                params.add(param);
            }

            // Create method without body first (for interfaces) or with empty body (for classes)
            CtMethod<?> method = factory.Method().create(
                    type,
                    mods,
                    returnType,
                    methodName,
                    params,
                    Collections.emptySet()
            );

            // For static factory methods on reactive types, make the method generic
            if (typeQn != null && (typeQn.equals("reactor.core.publisher.Mono") ||
                    typeQn.equals("reactor.core.publisher.Flux"))) {
                // Check if this is a static factory method (just, error, empty, etc.)
                boolean isStaticFactory = ("just".equals(methodName) || "error".equals(methodName) ||
                        "empty".equals(methodName) || "fromCallable".equals(methodName) ||
                        "fromIterable".equals(methodName) || "fromArray".equals(methodName));

                // Check if method is static (either from mods or from isUtilityClass check)
                boolean isStatic = mods.contains(ModifierKind.STATIC) ||
                        (isUtilityClass && type instanceof CtClass);

                if (isStatic && isStaticFactory) {
                    // Make the method generic: public static <T> Mono<T> just(T arg0)
                    CtTypeParameter methodTypeParam = factory.Core().createTypeParameter();
                    methodTypeParam.setSimpleName("T");
                    method.addFormalCtTypeParameter(methodTypeParam);

                    // Update return type to use the method's type parameter
                    // IMPORTANT: Create a new type reference without class-level type parameters
                    // Static methods cannot use class-level type parameters
                    CtTypeReference<?> baseTypeRef = factory.Type().createReference(type.getQualifiedName());
                    CtTypeParameterReference methodTypeParamRef = factory.Core().createTypeParameterReference();
                    methodTypeParamRef.setSimpleName("T");
                    baseTypeRef.addActualTypeArgument(methodTypeParamRef);
                    method.setType(baseTypeRef);

                    // Update parameter types to use T for just() method
                    if ("just".equals(methodName) && !params.isEmpty()) {
                        CtParameter<?> firstParam = params.get(0);
                        firstParam.setType(methodTypeParamRef);
                    }
                }
                // For instance methods that use Function<T, R>, add R as method-level type parameter
                else if (!isStatic && ("map".equals(methodName) || "flatMap".equals(methodName))) {
                    // Add R as method-level type parameter for Function<T, R>
                    CtTypeParameter methodTypeParamR = factory.Core().createTypeParameter();
                    methodTypeParamR.setSimpleName("R");
                    method.addFormalCtTypeParameter(methodTypeParamR);

                    // Update return type to use R: Mono<R> or Flux<R>
                    CtTypeReference<?> genericReturnType = type.getReference().clone();
                    CtTypeParameterReference methodTypeParamRefR = factory.Core().createTypeParameterReference();
                    methodTypeParamRefR.setSimpleName("R");
                    genericReturnType.addActualTypeArgument(methodTypeParamRefR);
                    method.setType(genericReturnType);
                }
            }

            // For classes, add a default body with return statement
            // IMPORTANT: Annotations cannot have method bodies - they can only have default values
            if (type instanceof CtClass && !(type instanceof CtAnnotationType)) {
                CtBlock<?> body = factory.Core().createBlock();
                if (!returnType.equals(factory.Type().VOID_PRIMITIVE)) {
                    CtReturn<?> ret = factory.Core().createReturn();
                    @SuppressWarnings({"unchecked", "rawtypes"})
                    CtExpression defaultValue = (CtExpression) createDefaultValueForType(returnType);
                    ret.setReturnedExpression(defaultValue);
                    body.addStatement(ret);
                }
                method.setBody(body);
            }
            // For annotations, methods should not have bodies - they're annotation elements
            // Annotation elements can have default values, but that's handled differently
        } catch (Exception e) {
            // Ignore method creation failures
        }
    }

    /**
     * Check if a type is a utility class (like StringUtils, ObjectUtils).
     * Utility classes typically have static methods.
     */
    private boolean isUtilityClass(CtType<?> type) {
        if (type == null) return false;
        String fqn = type.getQualifiedName();
        if (fqn == null) return false;
        // Common utility class patterns
        return fqn.contains("Utils") || fqn.contains("Helper") || fqn.contains("Util") ||
                fqn.contains("Factory") || // LoggerFactory, etc.
                fqn.equals("org.apache.commons.lang3.StringUtils") ||
                fqn.equals("org.apache.commons.lang3.ObjectUtils") ||
                fqn.equals("org.slf4j.LoggerFactory");
    }

    /**
     * Infer return type based on method name.
     */
    private CtTypeReference<?> inferReturnType(String methodName, CtType<?> ownerType) {
        // Special cases for common methods
        if ("toString".equals(methodName)) {
            return factory.Type().createReference("java.lang.String");
        }
        if ("equals".equals(methodName)) {
            return factory.Type().BOOLEAN_PRIMITIVE;
        }
        if ("hashCode".equals(methodName)) {
            return factory.Type().INTEGER_PRIMITIVE;
        }
        if ("isPresent".equals(methodName) || "isEmpty".equals(methodName) ||
                "isNotEmpty".equals(methodName) || "isBlank".equals(methodName) ||
                "isNotBlank".equals(methodName) || "isParallel".equals(methodName) ||
                "assertTrue".equals(methodName) || "assertFalse".equals(methodName)) {
            return factory.Type().BOOLEAN_PRIMITIVE;
        }
        if ("getLogger".equals(methodName)) {
            return factory.Type().createReference("org.slf4j.Logger");
        }
        if ("info".equals(methodName) || "debug".equals(methodName) ||
                "warn".equals(methodName) || "error".equals(methodName) ||
                "trace".equals(methodName)) {
            // SLF4J Logger methods return void
            return factory.Type().VOID_PRIMITIVE;
        }
        if ("trim".equals(methodName)) {
            // trim() should return String
            return factory.Type().createReference("java.lang.String");
        }
        if ("get".equals(methodName) && methodName.length() == 3) {
            // Generic get() method - return Object for shims
            return factory.Type().createReference("java.lang.Object");
        }
        // Default: return Object
        return factory.Type().createReference("java.lang.Object");
    }

    /**
     * Infer parameter types based on method name.
     */
    private List<CtTypeReference<?>> inferParameterTypes(String methodName, String typeQn) {
        List<CtTypeReference<?>> params = new ArrayList<>();

        // Special handling for reactive types (Mono, Flux)
        if (typeQn != null && (typeQn.equals("reactor.core.publisher.Mono") ||
                typeQn.equals("reactor.core.publisher.Flux"))) {
            if ("map".equals(methodName)) {
                // map takes Function<T, R>
                CtTypeReference<?> functionType = factory.Type().createReference("java.util.function.Function");
                CtTypeParameterReference typeParamT = factory.Core().createTypeParameterReference();
                typeParamT.setSimpleName("T");
                CtTypeParameterReference typeParamR = factory.Core().createTypeParameterReference();
                typeParamR.setSimpleName("R");
                functionType.addActualTypeArgument(typeParamT);
                functionType.addActualTypeArgument(typeParamR);
                params.add(functionType);
            } else if ("flatMap".equals(methodName)) {
                // flatMap takes Function<T, Mono<R>> or Function<T, Flux<R>>
                // The function returns a Publisher (Mono or Flux), not just R
                CtTypeReference<?> functionType = factory.Type().createReference("java.util.function.Function");
                CtTypeParameterReference typeParamT = factory.Core().createTypeParameterReference();
                typeParamT.setSimpleName("T");
                // The return type of the function is Mono<R> or Flux<R>
                CtTypeReference<?> publisherType = factory.Type().createReference(typeQn);
                CtTypeParameterReference typeParamR = factory.Core().createTypeParameterReference();
                typeParamR.setSimpleName("R");
                publisherType.addActualTypeArgument(typeParamR);
                functionType.addActualTypeArgument(typeParamT);
                functionType.addActualTypeArgument(publisherType);
                params.add(functionType);
            } else if ("filter".equals(methodName)) {
                // filter takes Predicate<T>
                CtTypeReference<?> predicateType = factory.Type().createReference("java.util.function.Predicate");
                CtTypeParameterReference typeParamT = factory.Core().createTypeParameterReference();
                typeParamT.setSimpleName("T");
                predicateType.addActualTypeArgument(typeParamT);
                params.add(predicateType);
            } else if ("just".equals(methodName)) {
                // just(T value) - takes one parameter of type T
                CtTypeParameterReference typeParamT = factory.Core().createTypeParameterReference();
                typeParamT.setSimpleName("T");
                params.add(typeParamT);
            } else if ("error".equals(methodName)) {
                // error(Throwable) - takes Throwable
                params.add(factory.Type().createReference("java.lang.Throwable"));
            } else if ("fromCallable".equals(methodName)) {
                // fromCallable(Callable<T>) - takes Callable
                params.add(factory.Type().createReference("java.util.concurrent.Callable"));
            } else if ("fromIterable".equals(methodName)) {
                // fromIterable(Iterable<T>) - takes Iterable
                params.add(factory.Type().createReference("java.lang.Iterable"));
            } else if ("fromArray".equals(methodName)) {
                // fromArray(T[]) - takes array
                CtTypeParameterReference typeParamT = factory.Core().createTypeParameterReference();
                typeParamT.setSimpleName("T");
                params.add(factory.Type().createArrayReference(typeParamT));
            }
        }

        // Special cases for common methods
        if ("equals".equals(methodName)) {
            params.add(factory.Type().createReference("java.lang.Object"));
        } else if ("getLogger".equals(methodName)) {
            params.add(factory.Type().createReference("java.lang.String"));
        } else if ("info".equals(methodName) || "debug".equals(methodName) ||
                "warn".equals(methodName) || "error".equals(methodName) ||
                "trace".equals(methodName)) {
            // SLF4J Logger methods - take String message
            params.add(factory.Type().createReference("java.lang.String"));
        } else if ("put".equals(methodName) || "get".equals(methodName) || "remove".equals(methodName)) {
            // MDC methods
            params.add(factory.Type().createReference("java.lang.String"));
        } else if ("setContextMap".equals(methodName)) {
            // MDC.setContextMap(Map<String, String> contextMap)
            CtTypeReference<?> mapType = factory.Type().createReference("java.util.Map");
            mapType.addActualTypeArgument(factory.Type().createReference("java.lang.String"));
            mapType.addActualTypeArgument(factory.Type().createReference("java.lang.String"));
            params.add(mapType);
        } else if ("trim".equals(methodName) || "isEmpty".equals(methodName) ||
                "isNotEmpty".equals(methodName) || "isBlank".equals(methodName) ||
                "isNotBlank".equals(methodName)) {
            // StringUtils methods - might take String parameter
            if ("trim".equals(methodName)) {
                params.add(factory.Type().createReference("java.lang.String"));
            }
        } else if ("assertEquals".equals(methodName) || "assertNotNull".equals(methodName) ||
                "assertTrue".equals(methodName) || "assertFalse".equals(methodName)) {
            // Assert methods - take Object parameters
            params.add(factory.Type().createReference("java.lang.Object"));
            if ("assertEquals".equals(methodName)) {
                params.add(factory.Type().createReference("java.lang.Object"));
            }
        } else if ("of".equals(methodName) || "copyOf".equals(methodName)) {
            // Immutable collection factory methods - take varargs
            // We'll create one parameter for simplicity (Object...)
            CtTypeReference<?> objectRef = factory.Type().createReference("java.lang.Object");
            params.add(factory.Type().createArrayReference(objectRef));
        } else if ("mock".equals(methodName) || "when".equals(methodName) || "any".equals(methodName)) {
            // Mockito methods
            params.add(factory.Type().createReference("java.lang.Class"));
        } else if ("verify".equals(methodName)) {
            // Mockito verify
            params.add(factory.Type().createReference("java.lang.Object"));
        }

        return params;
    }

    /**
     * Create a default value expression for a type.
     */
    private CtExpression<?> createDefaultValueForType(CtTypeReference<?> type) {
        if (type == null) return factory.Code().createLiteral(null);

        try {
            if (type.isPrimitive()) {
                String typeName = type.getSimpleName();
                if ("boolean".equals(typeName)) {
                    return factory.Code().createLiteral(false);
                } else if ("int".equals(typeName)) {
                    return factory.Code().createLiteral(0);
                } else if ("long".equals(typeName)) {
                    return factory.Code().createLiteral(0L);
                } else if ("short".equals(typeName)) {
                    return factory.Code().createLiteral((short) 0);
                } else if ("byte".equals(typeName)) {
                    return factory.Code().createLiteral((byte) 0);
                } else if ("char".equals(typeName)) {
                    return factory.Code().createLiteral('\0');
                } else if ("float".equals(typeName)) {
                    return factory.Code().createLiteral(0.0f);
                } else if ("double".equals(typeName)) {
                    return factory.Code().createLiteral(0.0);
                }
            } else if (type.getQualifiedName() != null && type.getQualifiedName().equals("java.lang.String")) {
                return factory.Code().createLiteral("");
            }
        } catch (Throwable ignored) {}

        return factory.Code().createLiteral(null);
    }

    /**
     * Check if a shim exists for the given FQN.
     */
    public boolean hasShim(String fqn) {
        return shimDefinitions.containsKey(fqn);
    }

    /**
     * Get all shim FQNs.
     */
    public Set<String> getAllShimFqns() {
        return new HashSet<>(shimDefinitions.keySet());
    }
}

