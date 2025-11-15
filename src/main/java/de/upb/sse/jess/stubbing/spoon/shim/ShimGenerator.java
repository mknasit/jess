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
        // Additional ANTLR shims
        addShim("org.antlr.v4.runtime", "CharStream", createInterfaceShim("org.antlr.v4.runtime.CharStream",
            Arrays.asList("getText", "getSourceName")));
        addShim("org.antlr.v4.runtime", "TokenStream", createInterfaceShim("org.antlr.v4.runtime.TokenStream",
            Arrays.asList("get", "consume", "LA", "mark", "release", "reset")));
        addShim("org.antlr.v4.runtime", "CommonTokenStream", createClassShim("org.antlr.v4.runtime.CommonTokenStream",
            Arrays.asList("get", "consume", "LA", "mark", "release", "reset")));
        addShim("org.antlr.v4.runtime", "Recognizer", createClassShim("org.antlr.v4.runtime.Recognizer",
            Arrays.asList("getTokenTypeMap", "getRuleIndexMap")));
        addShim("org.antlr.v4.runtime", "BaseErrorListener", createClassShim("org.antlr.v4.runtime.BaseErrorListener",
            Arrays.asList("syntaxError")));

        // SLF4J shims
        addShim("org.slf4j", "Marker", createInterfaceShim("org.slf4j.Marker")); // Needed for Logger overloads
        addShim("org.slf4j", "Logger", createInterfaceShim("org.slf4j.Logger",
                Arrays.asList("info", "debug", "warn", "error", "trace")));
        // Overloads will be added automatically after shim generation
        addShim("org.slf4j", "LoggerFactory", createClassShim("org.slf4j.LoggerFactory",
                Arrays.asList("getLogger")));
        // Overloads will be added automatically after shim generation
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
        // Mockito supporting types (needed for overloads)
        addShim("org.mockito.stubbing", "OngoingStubbing", createInterfaceShim("org.mockito.stubbing.OngoingStubbing",
            Arrays.asList("thenReturn", "thenThrow", "thenAnswer")));
        addShim("org.mockito.stubbing", "Answer", createInterfaceShim("org.mockito.stubbing.Answer",
            Arrays.asList("answer")));

        // Guava shims (common types)
        addShim("com.google.common.base", "Optional", createClassShim("com.google.common.base.Optional",
                Arrays.asList("of", "absent", "isPresent", "get", "or")));
        addShim("com.google.common.collect", "ImmutableList", createClassShim("com.google.common.collect.ImmutableList",
                Arrays.asList("of", "copyOf")));
        addShim("com.google.common.collect", "ImmutableSet", createClassShim("com.google.common.collect.ImmutableSet",
                Arrays.asList("of", "copyOf")));
        addShim("com.google.common.collect", "ImmutableMap", createClassShim("com.google.common.collect.ImmutableMap",
                Arrays.asList("of", "copyOf")));
        // Guava utility classes
        addShim("com.google.common.base", "Preconditions", createClassShim("com.google.common.base.Preconditions",
            Arrays.asList("checkNotNull", "checkArgument", "checkState", "checkElementIndex")));
        addShim("com.google.common.base", "MoreObjects", createClassShim("com.google.common.base.MoreObjects",
            Arrays.asList("toStringHelper", "firstNonNull")));
        addShim("com.google.common.collect", "Lists", createClassShim("com.google.common.collect.Lists",
            Arrays.asList("newArrayList", "newLinkedList", "asList")));
        addShim("com.google.common.collect", "Sets", createClassShim("com.google.common.collect.Sets",
            Arrays.asList("newHashSet", "newLinkedHashSet", "newTreeSet")));
        addShim("com.google.common.collect", "Maps", createClassShim("com.google.common.collect.Maps",
            Arrays.asList("newHashMap", "newLinkedHashMap", "newTreeMap", "newConcurrentMap")));

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
        
        // MyBatis Plus Extension sub-packages (missing from logs - 400+ errors)
        addShim("com.baomidou.mybatisplus.extension.service", "IService", createInterfaceShim("com.baomidou.mybatisplus.extension.service.IService",
                Arrays.asList("save", "saveBatch", "updateById", "updateBatchById", "removeById", "removeByIds",
                        "getById", "listByIds", "list", "page", "count")));
        addShim("com.baomidou.mybatisplus.extension.service.impl", "ServiceImpl", createClassShim("com.baomidou.mybatisplus.extension.service.impl.ServiceImpl",
                Arrays.asList("save", "saveBatch", "updateById", "updateBatchById", "removeById", "removeByIds",
                        "getById", "listByIds", "list", "page", "count", "getBaseMapper")));
        addShim("com.baomidou.mybatisplus.extension.activerecord", "Model", createClassShim("com.baomidou.mybatisplus.extension.activerecord.Model",
                Arrays.asList("save", "updateById", "deleteById", "selectById", "selectList", "selectPage")));
        addShim("com.baomidou.mybatisplus.extension.api", "IBaseService", createInterfaceShim("com.baomidou.mybatisplus.extension.api.IBaseService",
                Arrays.asList("save", "updateById", "removeById", "getById", "list")));
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

        // javax.servlet shims (javax.servlet.*) - 346 errors from logs (Jakarta exists but javax.servlet still used)
        addShim("javax.servlet", "Servlet", createInterfaceShim("javax.servlet.Servlet",
                Arrays.asList("init", "service", "destroy", "getServletConfig", "getServletInfo")));
        addShim("javax.servlet.http", "HttpServlet", createClassShim("javax.servlet.http.HttpServlet",
                Arrays.asList("doGet", "doPost", "doPut", "doDelete", "doHead", "doOptions", "doTrace")));
        addShim("javax.servlet.http", "HttpServletRequest", createInterfaceShim("javax.servlet.http.HttpServletRequest",
                Arrays.asList("getParameter", "getParameterValues", "getHeader", "getMethod", "getRequestURI",
                        "getQueryString", "getSession", "getCookies", "getAttribute", "setAttribute")));
        addShim("javax.servlet.http", "HttpServletResponse", createInterfaceShim("javax.servlet.http.HttpServletResponse",
                Arrays.asList("setStatus", "setHeader", "addHeader", "setContentType", "getWriter", "getOutputStream",
                        "sendRedirect", "sendError", "addCookie")));
        addShim("javax.servlet.http", "HttpSession", createInterfaceShim("javax.servlet.http.HttpSession",
                Arrays.asList("getAttribute", "setAttribute", "removeAttribute", "invalidate", "getId")));
        addShim("javax.servlet", "Filter", createInterfaceShim("javax.servlet.Filter",
                Arrays.asList("init", "doFilter", "destroy")));
        addShim("javax.servlet", "FilterChain", createInterfaceShim("javax.servlet.FilterChain",
                Arrays.asList("doFilter")));
        addShim("javax.servlet", "ServletContext", createInterfaceShim("javax.servlet.ServletContext",
                Arrays.asList("getAttribute", "setAttribute", "getInitParameter")));
        addShim("javax.servlet", "RequestDispatcher", createInterfaceShim("javax.servlet.RequestDispatcher",
                Arrays.asList("forward", "include")));
        addShim("javax.servlet", "ServletException", createClassShim("javax.servlet.ServletException"));
        addShim("javax.servlet", "ServletInputStream", createClassShim("javax.servlet.ServletInputStream",
                Arrays.asList("read", "readLine")));
        addShim("javax.servlet", "ServletOutputStream", createClassShim("javax.servlet.ServletOutputStream",
                Arrays.asList("write", "print", "println")));
        addShim("javax.servlet.http", "Cookie", createClassShim("javax.servlet.http.Cookie",
                Arrays.asList("getName", "getValue", "setValue", "getMaxAge", "setMaxAge")));

        // Spring Framework Core shims (org.springframework.*)
        // Spring Core - ApplicationContext, BeanFactory
        addShim("org.springframework.context", "ApplicationContext", createInterfaceShim("org.springframework.context.ApplicationContext",
                Arrays.asList("getBean", "getBeanNamesForType", "containsBean", "isSingleton")));
        addShim("org.springframework.context", "ConfigurableApplicationContext", createInterfaceShim("org.springframework.context.ConfigurableApplicationContext",
                Arrays.asList("refresh", "close", "registerShutdownHook")));
        addShim("org.springframework.beans.factory", "BeanFactory", createInterfaceShim("org.springframework.beans.factory.BeanFactory",
                Arrays.asList("getBean", "getBeanNamesForType", "containsBean", "isSingleton")));
        addShim("org.springframework.beans.factory", "ListableBeanFactory", createInterfaceShim("org.springframework.beans.factory.ListableBeanFactory",
                Arrays.asList("getBeanNamesForType", "getBeansOfType")));
        addShim("org.springframework.core.env", "Environment", createInterfaceShim("org.springframework.core.env.Environment",
                Arrays.asList("getProperty", "getRequiredProperty", "containsProperty")));
        addShim("org.springframework.core.env", "PropertySource", createClassShim("org.springframework.core.env.PropertySource"));
        addShim("org.springframework.core.env", "MutablePropertySources", createClassShim("org.springframework.core.env.MutablePropertySources",
                Arrays.asList("addFirst", "addLast", "addBefore", "addAfter")));
        addShim("org.springframework.beans", "BeanUtils", createClassShim("org.springframework.beans.BeanUtils",
                Arrays.asList("copyProperties", "instantiateClass")));
        addShim("org.springframework.core", "Ordered", createInterfaceShim("org.springframework.core.Ordered"));
        addShim("org.springframework.core.annotation", "Order", createAnnotationShim("org.springframework.core.annotation.Order"));
        
        // Spring Data JPA
        addShim("org.springframework.data.jpa.repository", "JpaRepository", createInterfaceShim("org.springframework.data.jpa.repository.JpaRepository",
                Arrays.asList("save", "findById", "findAll", "delete", "count")));
        addShim("org.springframework.data.repository", "CrudRepository", createInterfaceShim("org.springframework.data.repository.CrudRepository",
                Arrays.asList("save", "findById", "findAll", "delete", "count", "existsById")));
        addShim("org.springframework.data.repository", "PagingAndSortingRepository", createInterfaceShim("org.springframework.data.repository.PagingAndSortingRepository",
                Arrays.asList("findAll", "findAllById")));
        addShim("org.springframework.data.repository.query", "Param", createAnnotationShim("org.springframework.data.repository.query.Param"));
        addShim("org.springframework.data.jpa.repository", "Query", createAnnotationShim("org.springframework.data.jpa.repository.Query"));
        
        // Spring Security
        addShim("org.springframework.security.core", "SecurityContext", createInterfaceShim("org.springframework.security.core.SecurityContext",
                Arrays.asList("getAuthentication", "setAuthentication")));
        addShim("org.springframework.security.core", "Authentication", createInterfaceShim("org.springframework.security.core.Authentication",
                Arrays.asList("getPrincipal", "getAuthorities", "isAuthenticated", "getCredentials")));
        addShim("org.springframework.security.core.userdetails", "UserDetails", createInterfaceShim("org.springframework.security.core.userdetails.UserDetails",
                Arrays.asList("getUsername", "getPassword", "getAuthorities", "isAccountNonExpired", "isAccountNonLocked")));
        addShim("org.springframework.security.core.userdetails", "UserDetailsService", createInterfaceShim("org.springframework.security.core.userdetails.UserDetailsService",
                Arrays.asList("loadUserByUsername")));
        addShim("org.springframework.security.authentication", "AuthenticationManager", createInterfaceShim("org.springframework.security.authentication.AuthenticationManager",
                Arrays.asList("authenticate")));
        addShim("org.springframework.security.authentication", "UsernamePasswordAuthenticationToken", createClassShim("org.springframework.security.authentication.UsernamePasswordAuthenticationToken"));
        addShim("org.springframework.security.core.context", "SecurityContextHolder", createClassShim("org.springframework.security.core.context.SecurityContextHolder",
                Arrays.asList("getContext", "setContext", "clearContext")));
        
        // Spring UI/Model
        addShim("org.springframework.ui", "Model", createInterfaceShim("org.springframework.ui.Model",
                Arrays.asList("addAttribute", "addAllAttributes", "containsAttribute", "getAttribute")));
        addShim("org.springframework.ui", "ModelMap", createClassShim("org.springframework.ui.ModelMap",
                Arrays.asList("addAttribute", "put", "get")));
        
        // Spring Boot
        addShim("org.springframework.boot", "SpringApplication", createClassShim("org.springframework.boot.SpringApplication",
                Arrays.asList("run", "setDefaultProperties")));
        addShim("org.springframework.boot.autoconfigure", "SpringBootApplication", createAnnotationShim("org.springframework.boot.autoconfigure.SpringBootApplication"));
        addShim("org.springframework.boot.context.properties", "ConfigurationProperties", createAnnotationShim("org.springframework.boot.context.properties.ConfigurationProperties"));
        
        // Spring Context Events
        addShim("org.springframework.context.event", "ApplicationEvent", createClassShim("org.springframework.context.event.ApplicationEvent"));
        addShim("org.springframework.context.event", "ApplicationListener", createInterfaceShim("org.springframework.context.event.ApplicationListener",
                Arrays.asList("onApplicationEvent")));
        addShim("org.springframework.context", "ApplicationEventPublisher", createInterfaceShim("org.springframework.context.ApplicationEventPublisher",
                Arrays.asList("publishEvent")));
        
        // Spring Web Server
        addShim("org.springframework.web.server", "ServerWebExchange", createInterfaceShim("org.springframework.web.server.ServerWebExchange",
                Arrays.asList("getRequest", "getResponse", "getAttributes")));
        addShim("org.springframework.web.server", "WebFilter", createInterfaceShim("org.springframework.web.server.WebFilter",
                Arrays.asList("filter")));
        addShim("org.springframework.web.server", "WebFilterChain", createInterfaceShim("org.springframework.web.server.WebFilterChain",
                Arrays.asList("filter")));
        
        // Spring Framework Web annotations
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

        // Vavr functional interfaces (io.vavr.*)
        addShim("io.vavr", "Function1", createInterfaceShim("io.vavr.Function1",
                Arrays.asList("apply")));
        addShim("io.vavr", "Function2", createInterfaceShim("io.vavr.Function2",
                Arrays.asList("apply")));
        addShim("io.vavr", "Function3", createInterfaceShim("io.vavr.Function3",
                Arrays.asList("apply")));
        addShim("io.vavr", "Function4", createInterfaceShim("io.vavr.Function4",
                Arrays.asList("apply")));
        addShim("io.vavr", "Function5", createInterfaceShim("io.vavr.Function5",
                Arrays.asList("apply")));
        addShim("io.vavr", "CheckedFunction1", createInterfaceShim("io.vavr.CheckedFunction1",
                Arrays.asList("apply")));
        addShim("io.vavr", "CheckedFunction2", createInterfaceShim("io.vavr.CheckedFunction2",
                Arrays.asList("apply")));

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

        // ======================================================================
        // ADDITIONAL FRAMEWORK SHIMS - Based on Real Repository Analysis
        // ======================================================================

        // Netty shims (io.netty.*) - Very common in repositories
        addShim("io.netty.buffer", "ByteBuf", createClassShim("io.netty.buffer.ByteBuf",
            Arrays.asList("readableBytes", "readByte", "writeByte", "readInt", "writeInt", "readBytes", "writeBytes")));
        addShim("io.netty.channel", "Channel", createInterfaceShim("io.netty.channel.Channel",
            Arrays.asList("write", "flush", "close", "isActive", "isOpen")));
        addShim("io.netty.channel", "ChannelHandler", createInterfaceShim("io.netty.channel.ChannelHandler"));
        addShim("io.netty.channel", "ChannelHandlerContext", createInterfaceShim("io.netty.channel.ChannelHandlerContext",
            Arrays.asList("write", "flush", "fireChannelRead", "fireChannelReadComplete")));
        addShim("io.netty.channel", "ChannelInboundHandler", createInterfaceShim("io.netty.channel.ChannelInboundHandler",
            Arrays.asList("channelRead", "channelActive", "channelInactive", "exceptionCaught")));
        addShim("io.netty.channel", "ChannelOutboundHandler", createInterfaceShim("io.netty.channel.ChannelOutboundHandler",
            Arrays.asList("write", "flush", "close")));
        addShim("io.netty.channel", "ChannelPipeline", createInterfaceShim("io.netty.channel.ChannelPipeline",
            Arrays.asList("addFirst", "addLast", "remove", "fireChannelRead")));
        addShim("io.netty.channel", "EventLoopGroup", createInterfaceShim("io.netty.channel.EventLoopGroup",
            Arrays.asList("shutdownGracefully")));
        addShim("io.netty.bootstrap", "ServerBootstrap", createClassShim("io.netty.bootstrap.ServerBootstrap",
            Arrays.asList("group", "channel", "childHandler", "bind")));
        addShim("io.netty.bootstrap", "Bootstrap", createClassShim("io.netty.bootstrap.Bootstrap",
            Arrays.asList("group", "channel", "handler", "connect")));

        // Vert.x shims (io.vertx.*) - Common in reactive applications
        addShim("io.vertx.core", "Vertx", createClassShim("io.vertx.core.Vertx",
            Arrays.asList("createHttpServer", "createNetServer", "deployVerticle", "close")));
        addShim("io.vertx.core", "Verticle", createInterfaceShim("io.vertx.core.Verticle",
            Arrays.asList("start", "stop")));
        addShim("io.vertx.core.http", "HttpServer", createInterfaceShim("io.vertx.core.http.HttpServer",
            Arrays.asList("requestHandler", "listen", "close")));
        addShim("io.vertx.core.http", "HttpServerRequest", createInterfaceShim("io.vertx.core.http.HttpServerRequest",
            Arrays.asList("method", "uri", "path", "headers", "bodyHandler")));
        addShim("io.vertx.core.http", "HttpServerResponse", createInterfaceShim("io.vertx.core.http.HttpServerResponse",
            Arrays.asList("setStatusCode", "putHeader", "end", "write")));
        addShim("io.vertx.core", "Handler", createInterfaceShim("io.vertx.core.Handler",
            Arrays.asList("handle")));
        addShim("io.vertx.core", "Future", createInterfaceShim("io.vertx.core.Future",
            Arrays.asList("succeeded", "failed", "result", "cause", "onComplete", "onSuccess", "onFailure")));
        addShim("io.vertx.core", "Promise", createInterfaceShim("io.vertx.core.Promise",
            Arrays.asList("complete", "fail", "future")));

        // Jedis shims (redis.clients.jedis.*) - Common Redis client
        addShim("redis.clients.jedis", "Jedis", createClassShim("redis.clients.jedis.Jedis",
            Arrays.asList("get", "set", "del", "exists", "keys", "hget", "hset", "hdel", "close")));
        addShim("redis.clients.jedis", "JedisPool", createClassShim("redis.clients.jedis.JedisPool",
            Arrays.asList("getResource", "close")));
        addShim("redis.clients.jedis", "JedisPoolConfig", createClassShim("redis.clients.jedis.JedisPoolConfig"));
        addShim("redis.clients.jedis", "Transaction", createInterfaceShim("redis.clients.jedis.Transaction",
            Arrays.asList("get", "set", "exec", "discard")));
        addShim("redis.clients.jedis", "Pipeline", createInterfaceShim("redis.clients.jedis.Pipeline",
            Arrays.asList("get", "set", "sync", "close")));

        // MyBatis shims (org.apache.ibatis.*) - Common ORM framework
        addShim("org.apache.ibatis", "SqlSession", createInterfaceShim("org.apache.ibatis.SqlSession",
            Arrays.asList("selectOne", "selectList", "insert", "update", "delete", "commit", "rollback", "close")));
        addShim("org.apache.ibatis", "SqlSessionFactory", createInterfaceShim("org.apache.ibatis.SqlSessionFactory",
            Arrays.asList("openSession", "getConfiguration")));
        addShim("org.apache.ibatis.session", "SqlSessionFactoryBuilder", createClassShim("org.apache.ibatis.session.SqlSessionFactoryBuilder",
            Arrays.asList("build")));
        addShim("org.apache.ibatis.annotations", "Select", createAnnotationShim("org.apache.ibatis.annotations.Select"));
        addShim("org.apache.ibatis.annotations", "Insert", createAnnotationShim("org.apache.ibatis.annotations.Insert"));
        addShim("org.apache.ibatis.annotations", "Update", createAnnotationShim("org.apache.ibatis.annotations.Update"));
        addShim("org.apache.ibatis.annotations", "Delete", createAnnotationShim("org.apache.ibatis.annotations.Delete"));
        addShim("org.apache.ibatis.annotations", "Param", createAnnotationShim("org.apache.ibatis.annotations.Param"));
        addShim("org.apache.ibatis.annotations", "Mapper", createAnnotationShim("org.apache.ibatis.annotations.Mapper"));
        addShim("org.apache.ibatis.mapping", "MappedStatement", createClassShim("org.apache.ibatis.mapping.MappedStatement"));
        addShim("org.apache.ibatis.mapping", "SqlCommandType", createEnumShim("org.apache.ibatis.mapping.SqlCommandType"));

        // Alibaba Druid shims (com.alibaba.druid.*) - Database connection pool
        addShim("com.alibaba.druid.pool", "DruidDataSource", createClassShim("com.alibaba.druid.pool.DruidDataSource",
            Arrays.asList("getConnection", "close", "setUrl", "setUsername", "setPassword", "setDriverClassName")));
        addShim("com.alibaba.druid.pool", "DruidConnectionHolder", createClassShim("com.alibaba.druid.pool.DruidConnectionHolder"));
        addShim("com.alibaba.druid.filter", "Filter", createInterfaceShim("com.alibaba.druid.filter.Filter"));

        // Alibaba FastJSON shims (com.alibaba.fastjson.*) - JSON library
        addShim("com.alibaba.fastjson", "JSON", createClassShim("com.alibaba.fastjson.JSON",
            Arrays.asList("toJSONString", "parseObject", "parseArray")));
        addShim("com.alibaba.fastjson.annotation", "JSONField", createAnnotationShim("com.alibaba.fastjson.annotation.JSONField"));
        addShim("com.alibaba.fastjson.serializer", "SerializeFilter", createClassShim("com.alibaba.fastjson.serializer.SerializeFilter"));

        // Alibaba Nacos shims (com.alibaba.nacos.*) - Service discovery
        addShim("com.alibaba.nacos.api", "NacosFactory", createClassShim("com.alibaba.nacos.api.NacosFactory",
            Arrays.asList("createNamingService", "createConfigService")));
        addShim("com.alibaba.nacos.api.naming", "NamingService", createInterfaceShim("com.alibaba.nacos.api.naming.NamingService",
            Arrays.asList("registerInstance", "deregisterInstance", "getAllInstances", "selectInstances")));
        addShim("com.alibaba.nacos.api.config", "ConfigService", createInterfaceShim("com.alibaba.nacos.api.config.ConfigService",
            Arrays.asList("getConfig", "publishConfig", "removeConfig", "addListener")));

        // Alibaba Sentinel shims (com.alibaba.csp.sentinel.*) - Flow control
        addShim("com.alibaba.csp.sentinel", "SphU", createClassShim("com.alibaba.csp.sentinel.SphU",
            Arrays.asList("entry")));
        addShim("com.alibaba.csp.sentinel", "Entry", createInterfaceShim("com.alibaba.csp.sentinel.Entry",
            Arrays.asList("exit")));
        addShim("com.alibaba.csp.sentinel.annotation", "SentinelResource", createAnnotationShim("com.alibaba.csp.sentinel.annotation.SentinelResource"));

        // Apache Commons IO shims (org.apache.commons.io.*)
        addShim("org.apache.commons.io", "IOUtils", createClassShim("org.apache.commons.io.IOUtils",
            Arrays.asList("copy", "toString", "toByteArray", "closeQuietly")));
        addShim("org.apache.commons.io", "FileUtils", createClassShim("org.apache.commons.io.FileUtils",
            Arrays.asList("readFileToString", "writeStringToFile", "copyFile", "deleteQuietly")));

        // Apache Commons Codec shims (org.apache.commons.codec.*)
        addShim("org.apache.commons.codec.digest", "DigestUtils", createClassShim("org.apache.commons.codec.digest.DigestUtils",
            Arrays.asList("md5Hex", "sha256Hex", "sha1Hex")));
        addShim("org.apache.commons.codec.binary", "Base64", createClassShim("org.apache.commons.codec.binary.Base64",
            Arrays.asList("encodeBase64String", "decodeBase64")));

        // Apache Commons Collections shims (org.apache.commons.collections4.*)
        addShim("org.apache.commons.collections4", "CollectionUtils", createClassShim("org.apache.commons.collections4.CollectionUtils",
            Arrays.asList("isEmpty", "isNotEmpty", "addAll", "union", "intersection")));
        addShim("org.apache.commons.collections4", "MapUtils", createClassShim("org.apache.commons.collections4.MapUtils",
            Arrays.asList("isEmpty", "isNotEmpty", "getString", "getInteger")));

        // Jackson shims (com.fasterxml.jackson.*) - JSON library
        addShim("com.fasterxml.jackson.databind", "ObjectMapper", createClassShim("com.fasterxml.jackson.databind.ObjectMapper",
            Arrays.asList("readValue", "writeValueAsString", "writeValueAsBytes", "convertValue")));
        addShim("com.fasterxml.jackson.annotation", "JsonProperty", createAnnotationShim("com.fasterxml.jackson.annotation.JsonProperty"));
        addShim("com.fasterxml.jackson.annotation", "JsonIgnore", createAnnotationShim("com.fasterxml.jackson.annotation.JsonIgnore"));
        addShim("com.fasterxml.jackson.annotation", "JsonFormat", createAnnotationShim("com.fasterxml.jackson.annotation.JsonFormat"));

        // Gson shims (com.google.gson.*) - JSON library
        addShim("com.google.gson", "Gson", createClassShim("com.google.gson.Gson",
            Arrays.asList("toJson", "fromJson")));
        addShim("com.google.gson.annotations", "SerializedName", createAnnotationShim("com.google.gson.annotations.SerializedName"));
        addShim("com.google.gson.annotations", "Expose", createAnnotationShim("com.google.gson.annotations.Expose"));

        // Joda Time shims (org.joda.time.*) - Date/time library
        addShim("org.joda.time", "DateTime", createClassShim("org.joda.time.DateTime",
            Arrays.asList("now", "toString", "plusDays", "minusDays", "toDate", "getMillis")));
        addShim("org.joda.time.format", "DateTimeFormatter", createClassShim("org.joda.time.format.DateTimeFormatter",
            Arrays.asList("forPattern", "print", "parseDateTime")));

        // Apache Kafka shims (org.apache.kafka.*) - Messaging
        addShim("org.apache.kafka.clients.producer", "KafkaProducer", createClassShim("org.apache.kafka.clients.producer.KafkaProducer",
            Arrays.asList("send", "flush", "close")));
        addShim("org.apache.kafka.clients.consumer", "KafkaConsumer", createClassShim("org.apache.kafka.clients.consumer.KafkaConsumer",
            Arrays.asList("subscribe", "poll", "commitSync", "close")));
        addShim("org.apache.kafka.clients.producer", "ProducerRecord", createClassShim("org.apache.kafka.clients.producer.ProducerRecord",
            Arrays.asList("topic", "key", "value")));
        addShim("org.apache.kafka.clients.consumer", "ConsumerRecord", createClassShim("org.apache.kafka.clients.consumer.ConsumerRecord",
            Arrays.asList("topic", "key", "value", "offset", "partition")));

        // Apache Flink shims (org.apache.flink.*) - Stream processing
        addShim("org.apache.flink.streaming.api", "StreamExecutionEnvironment", createClassShim("org.apache.flink.streaming.api.StreamExecutionEnvironment",
            Arrays.asList("getExecutionEnvironment", "addSource", "execute", "setParallelism")));
        addShim("org.apache.flink.streaming.api.functions", "SourceFunction", createInterfaceShim("org.apache.flink.streaming.api.functions.SourceFunction",
            Arrays.asList("run", "cancel")));
        addShim("org.apache.flink.streaming.api.datastream", "DataStream", createClassShim("org.apache.flink.streaming.api.datastream.DataStream",
            Arrays.asList("map", "filter", "keyBy", "print", "addSink")));

        // Apache Camel shims (org.apache.camel.*) - Integration framework
        addShim("org.apache.camel", "CamelContext", createInterfaceShim("org.apache.camel.CamelContext",
            Arrays.asList("addRoutes", "start", "stop", "createProducerTemplate", "createConsumerTemplate")));
        addShim("org.apache.camel.builder", "RouteBuilder", createClassShim("org.apache.camel.builder.RouteBuilder",
            Arrays.asList("configure", "from", "to")));
        addShim("org.apache.camel", "Exchange", createInterfaceShim("org.apache.camel.Exchange",
            Arrays.asList("getIn", "getOut", "getProperty", "setProperty")));
        addShim("org.apache.camel", "Message", createInterfaceShim("org.apache.camel.Message",
            Arrays.asList("getBody", "setBody", "getHeader", "setHeader")));

        // Apache Shiro shims (org.apache.shiro.*) - Security framework
        addShim("org.apache.shiro", "SecurityUtils", createClassShim("org.apache.shiro.SecurityUtils",
            Arrays.asList("getSubject")));
        addShim("org.apache.shiro.subject", "Subject", createInterfaceShim("org.apache.shiro.subject.Subject",
            Arrays.asList("login", "logout", "isAuthenticated", "hasRole", "checkRole", "isPermitted", "checkPermission")));
        addShim("org.apache.shiro.authc", "AuthenticationToken", createInterfaceShim("org.apache.shiro.authc.AuthenticationToken",
            Arrays.asList("getPrincipal", "getCredentials")));
        addShim("org.apache.shiro.authc", "UsernamePasswordToken", createClassShim("org.apache.shiro.authc.UsernamePasswordToken"));

        // Apache RocketMQ shims (org.apache.rocketmq.*) - Messaging
        addShim("org.apache.rocketmq.client.producer", "DefaultMQProducer", createClassShim("org.apache.rocketmq.client.producer.DefaultMQProducer",
            Arrays.asList("start", "send", "shutdown")));
        addShim("org.apache.rocketmq.client.consumer", "DefaultMQPushConsumer", createClassShim("org.apache.rocketmq.client.consumer.DefaultMQPushConsumer",
            Arrays.asList("subscribe", "registerMessageListener", "start", "shutdown")));
        addShim("org.apache.rocketmq.common.message", "Message", createClassShim("org.apache.rocketmq.common.message.Message",
            Arrays.asList("getBody", "setBody", "getTopic", "setTopic")));

        // Apache Dubbo shims (org.apache.dubbo.*) - RPC framework
        addShim("org.apache.dubbo.config", "ServiceConfig", createClassShim("org.apache.dubbo.config.ServiceConfig",
            Arrays.asList("setInterface", "setRef", "export")));
        addShim("org.apache.dubbo.config", "ReferenceConfig", createClassShim("org.apache.dubbo.config.ReferenceConfig",
            Arrays.asList("setInterface", "get")));
        addShim("org.apache.dubbo.rpc", "RpcContext", createClassShim("org.apache.dubbo.rpc.RpcContext",
            Arrays.asList("getContext", "getRequest", "getResponse")));

        // Hutool shims (cn.hutool.*) - Common utilities (very popular in Chinese repos)
        addShim("cn.hutool.core.util", "StrUtil", createClassShim("cn.hutool.core.util.StrUtil",
            Arrays.asList("isEmpty", "isNotEmpty", "isBlank", "isNotBlank", "trim", "format")));
        addShim("cn.hutool.core.util", "DateUtil", createClassShim("cn.hutool.core.util.DateUtil",
            Arrays.asList("now", "format", "parse", "offsetDay", "offsetMonth")));
        addShim("cn.hutool.core.collection", "CollUtil", createClassShim("cn.hutool.core.collection.CollUtil",
            Arrays.asList("isEmpty", "isNotEmpty", "newArrayList", "newHashSet")));
        addShim("cn.hutool.http", "HttpUtil", createClassShim("cn.hutool.http.HttpUtil",
            Arrays.asList("get", "post", "downloadFile")));
        addShim("cn.hutool.json", "JSONUtil", createClassShim("cn.hutool.json.JSONUtil",
            Arrays.asList("toJsonStr", "parseObj", "parseArray")));

        // Seata shims (io.seata.*) - Distributed transaction
        addShim("io.seata.rm", "GlobalTransaction", createInterfaceShim("io.seata.rm.GlobalTransaction",
            Arrays.asList("begin", "commit", "rollback")));
        addShim("io.seata.spring.annotation", "GlobalTransactional", createAnnotationShim("io.seata.spring.annotation.GlobalTransactional"));

        // XXL-Job shims (com.xxl.job.*) - Job scheduling
        addShim("com.xxl.job.core.handler", "IJobHandler", createClassShim("com.xxl.job.core.handler.IJobHandler",
            Arrays.asList("execute")));
        addShim("com.xxl.job.core.handler.annotation", "XxlJob", createAnnotationShim("com.xxl.job.core.handler.annotation.XxlJob"));

        // PowerJob shims (tech.powerjob.*) - Job scheduling
        addShim("tech.powerjob.worker", "PowerJobWorker", createClassShim("tech.powerjob.worker.PowerJobWorker",
            Arrays.asList("init", "destroy")));
        addShim("tech.powerjob.worker.core", "Processor", createInterfaceShim("tech.powerjob.worker.core.Processor",
            Arrays.asList("process")));
        addShim("tech.powerjob.worker.core", "TaskContext", createInterfaceShim("tech.powerjob.worker.core.TaskContext",
            Arrays.asList("getJobId", "getInstanceId", "getJobParams", "getInstanceParams")));
        addShim("tech.powerjob.common", "PowerJob", createClassShim("tech.powerjob.common.PowerJob",
                Arrays.asList("init", "run")));
        
        // JPA shims (javax.persistence.*) - 333 errors from logs
        addShim("javax.persistence", "Entity", createAnnotationShim("javax.persistence.Entity"));
        addShim("javax.persistence", "Table", createAnnotationShim("javax.persistence.Table"));
        addShim("javax.persistence", "Id", createAnnotationShim("javax.persistence.Id"));
        addShim("javax.persistence", "GeneratedValue", createAnnotationShim("javax.persistence.GeneratedValue"));
        addShim("javax.persistence", "Column", createAnnotationShim("javax.persistence.Column"));
        addShim("javax.persistence", "OneToMany", createAnnotationShim("javax.persistence.OneToMany"));
        addShim("javax.persistence", "ManyToOne", createAnnotationShim("javax.persistence.ManyToOne"));
        addShim("javax.persistence", "ManyToMany", createAnnotationShim("javax.persistence.ManyToMany"));
        addShim("javax.persistence", "OneToOne", createAnnotationShim("javax.persistence.OneToOne"));
        addShim("javax.persistence", "JoinColumn", createAnnotationShim("javax.persistence.JoinColumn"));
        addShim("javax.persistence", "EntityManager", createInterfaceShim("javax.persistence.EntityManager",
                Arrays.asList("persist", "merge", "remove", "find", "createQuery", "getTransaction")));
        addShim("javax.persistence", "EntityManagerFactory", createInterfaceShim("javax.persistence.EntityManagerFactory",
                Arrays.asList("createEntityManager", "close")));
        addShim("javax.persistence", "Query", createInterfaceShim("javax.persistence.Query",
                Arrays.asList("getResultList", "getSingleResult", "executeUpdate", "setParameter")));
        addShim("javax.persistence", "TypedQuery", createInterfaceShim("javax.persistence.TypedQuery",
                Arrays.asList("getResultList", "getSingleResult", "setParameter")));
        addShim("javax.persistence", "EntityTransaction", createInterfaceShim("javax.persistence.EntityTransaction",
                Arrays.asList("begin", "commit", "rollback", "isActive")));
        
        // Jackson shims (com.fasterxml.jackson.databind.*) - 387 errors from logs
        addShim("com.fasterxml.jackson.databind", "ObjectMapper", createClassShim("com.fasterxml.jackson.databind.ObjectMapper",
                Arrays.asList("readValue", "writeValueAsString", "writeValueAsBytes", "convertValue", "readTree")));
        addShim("com.fasterxml.jackson.databind", "JsonNode", createClassShim("com.fasterxml.jackson.databind.JsonNode",
                Arrays.asList("get", "getTextValue", "asText", "asInt", "asLong", "asBoolean", "isNull", "isArray", "isObject")));
        addShim("com.fasterxml.jackson.databind.node", "ObjectNode", createClassShim("com.fasterxml.jackson.databind.node.ObjectNode",
                Arrays.asList("put", "putIfAbsent", "remove", "set", "get")));
        addShim("com.fasterxml.jackson.databind.node", "ArrayNode", createClassShim("com.fasterxml.jackson.databind.node.ArrayNode",
                Arrays.asList("add", "addAll", "insert", "remove", "get")));
        addShim("com.fasterxml.jackson.annotation", "JsonProperty", createAnnotationShim("com.fasterxml.jackson.annotation.JsonProperty"));
        addShim("com.fasterxml.jackson.annotation", "JsonIgnore", createAnnotationShim("com.fasterxml.jackson.annotation.JsonIgnore"));
        addShim("com.fasterxml.jackson.annotation", "JsonIgnoreProperties", createAnnotationShim("com.fasterxml.jackson.annotation.JsonIgnoreProperties"));
        addShim("com.fasterxml.jackson.annotation", "JsonFormat", createAnnotationShim("com.fasterxml.jackson.annotation.JsonFormat"));
        addShim("com.fasterxml.jackson.core", "JsonProcessingException", createClassShim("com.fasterxml.jackson.core.JsonProcessingException"));
        
        // Netty shims (io.netty.*) - 821 errors from logs
        addShim("io.netty.channel", "Channel", createInterfaceShim("io.netty.channel.Channel",
                Arrays.asList("write", "writeAndFlush", "flush", "close", "isActive", "isOpen")));
        addShim("io.netty.channel", "ChannelHandlerContext", createInterfaceShim("io.netty.channel.ChannelHandlerContext",
                Arrays.asList("write", "writeAndFlush", "flush", "fireChannelRead", "fireChannelReadComplete")));
        addShim("io.netty.channel", "ChannelInboundHandler", createInterfaceShim("io.netty.channel.ChannelInboundHandler",
                Arrays.asList("channelRead", "channelActive", "channelInactive", "exceptionCaught")));
        addShim("io.netty.channel", "ChannelOutboundHandler", createInterfaceShim("io.netty.channel.ChannelOutboundHandler",
                Arrays.asList("write", "flush", "close")));
        addShim("io.netty.channel", "ChannelPipeline", createInterfaceShim("io.netty.channel.ChannelPipeline",
                Arrays.asList("addFirst", "addLast", "addBefore", "addAfter", "remove", "get")));
        addShim("io.netty.channel", "EventLoopGroup", createInterfaceShim("io.netty.channel.EventLoopGroup",
                Arrays.asList("next", "shutdownGracefully")));
        addShim("io.netty.channel.nio", "NioEventLoopGroup", createClassShim("io.netty.channel.nio.NioEventLoopGroup"));
        addShim("io.netty.bootstrap", "ServerBootstrap", createClassShim("io.netty.bootstrap.ServerBootstrap",
                Arrays.asList("group", "channel", "childHandler", "bind")));
        addShim("io.netty.bootstrap", "Bootstrap", createClassShim("io.netty.bootstrap.Bootstrap",
                Arrays.asList("group", "channel", "handler", "connect")));
        addShim("io.netty.handler.codec.http", "FullHttpRequest", createInterfaceShim("io.netty.handler.codec.http.FullHttpRequest",
                Arrays.asList("method", "uri", "protocolVersion", "headers", "content")));
        addShim("io.netty.handler.codec.http", "FullHttpResponse", createInterfaceShim("io.netty.handler.codec.http.FullHttpResponse",
                Arrays.asList("status", "protocolVersion", "headers", "content")));
        addShim("io.netty.handler.codec.http", "HttpRequest", createInterfaceShim("io.netty.handler.codec.http.HttpRequest",
                Arrays.asList("method", "uri", "protocolVersion", "headers")));
        addShim("io.netty.handler.codec.http", "HttpResponse", createInterfaceShim("io.netty.handler.codec.http.HttpResponse",
                Arrays.asList("status", "protocolVersion", "headers")));
        addShim("io.netty.handler.codec.http", "HttpHeaders", createClassShim("io.netty.handler.codec.http.HttpHeaders",
                Arrays.asList("get", "set", "add", "contains")));
        addShim("io.netty.handler.codec.http", "DefaultFullHttpRequest", createClassShim("io.netty.handler.codec.http.DefaultFullHttpRequest"));
        addShim("io.netty.handler.codec.http", "DefaultFullHttpResponse", createClassShim("io.netty.handler.codec.http.DefaultFullHttpResponse"));
        
        // Complete Guava shims (com.google.common.*) - 842 errors from logs
        // Note: ImmutableList, ImmutableSet, ImmutableMap, Preconditions, MoreObjects, Lists, Sets, Maps already exist
        // Adding missing Guava sub-packages
        addShim("com.google.common.collect", "Multimap", createInterfaceShim("com.google.common.collect.Multimap",
                Arrays.asList("put", "get", "remove", "containsKey", "size")));
        addShim("com.google.common.collect", "ListMultimap", createInterfaceShim("com.google.common.collect.ListMultimap",
                Arrays.asList("put", "get", "remove", "containsKey")));
        addShim("com.google.common.collect", "SetMultimap", createInterfaceShim("com.google.common.collect.SetMultimap",
                Arrays.asList("put", "get", "remove", "containsKey")));
        addShim("com.google.common.collect", "Multimaps", createClassShim("com.google.common.collect.Multimaps",
                Arrays.asList("newListMultimap", "newSetMultimap", "asMap")));
        addShim("com.google.common.collect", "Range", createClassShim("com.google.common.collect.Range",
                Arrays.asList("closed", "open", "closedOpen", "openClosed", "all", "contains")));
        addShim("com.google.common.collect", "Table", createInterfaceShim("com.google.common.collect.Table",
                Arrays.asList("put", "get", "remove", "contains", "row", "column")));
        addShim("com.google.common.collect", "HashBasedTable", createClassShim("com.google.common.collect.HashBasedTable",
                Arrays.asList("create", "put", "get", "remove")));
        addShim("com.google.common.base", "Optional", createClassShim("com.google.common.base.Optional",
                Arrays.asList("of", "absent", "fromNullable", "isPresent", "get", "or", "orNull")));
        addShim("com.google.common.base", "Stopwatch", createClassShim("com.google.common.base.Stopwatch",
                Arrays.asList("createStarted", "start", "stop", "elapsed")));
        addShim("com.google.common.base", "Splitter", createClassShim("com.google.common.base.Splitter",
                Arrays.asList("on", "onPattern", "split", "splitToList")));
        addShim("com.google.common.base", "Joiner", createClassShim("com.google.common.base.Joiner",
                Arrays.asList("on", "join", "appendTo")));
        addShim("com.google.common.cache", "Cache", createInterfaceShim("com.google.common.cache.Cache",
                Arrays.asList("get", "put", "invalidate", "invalidateAll", "size")));
        addShim("com.google.common.cache", "CacheBuilder", createClassShim("com.google.common.cache.CacheBuilder",
                Arrays.asList("newBuilder", "maximumSize", "expireAfterWrite", "expireAfterAccess", "build")));
        addShim("com.google.common.cache", "LoadingCache", createInterfaceShim("com.google.common.cache.LoadingCache",
                Arrays.asList("get", "getUnchecked", "invalidate", "size")));
        addShim("com.google.common.util.concurrent", "ListenableFuture", createInterfaceShim("com.google.common.util.concurrent.ListenableFuture",
                Arrays.asList("addListener")));
        addShim("com.google.common.util.concurrent", "Futures", createClassShim("com.google.common.util.concurrent.Futures",
                Arrays.asList("successfulAsList", "allAsList", "transform", "transformAsync")));
        
        // Jedis shims (redis.clients.jedis.*) - 378 errors from logs
        addShim("redis.clients.jedis", "Jedis", createClassShim("redis.clients.jedis.Jedis",
                Arrays.asList("get", "set", "del", "exists", "keys", "hget", "hset", "hgetAll", "lpush", "rpush", "lpop", "rpop", "sadd", "smembers", "close")));
        addShim("redis.clients.jedis", "JedisPool", createClassShim("redis.clients.jedis.JedisPool",
                Arrays.asList("getResource", "close")));
        addShim("redis.clients.jedis", "JedisPoolConfig", createClassShim("redis.clients.jedis.JedisPoolConfig",
                Arrays.asList("setMaxTotal", "setMaxIdle", "setMinIdle", "setTestOnBorrow")));
        addShim("redis.clients.jedis", "BinaryJedis", createClassShim("redis.clients.jedis.BinaryJedis",
                Arrays.asList("get", "set", "del", "exists")));
        addShim("redis.clients.jedis.params", "SetParams", createClassShim("redis.clients.jedis.params.SetParams",
                Arrays.asList("ex", "px", "nx", "xx")));
        addShim("redis.clients.jedis.params", "GetExParams", createClassShim("redis.clients.jedis.params.GetExParams",
                Arrays.asList("ex", "px")));
        addShim("redis.clients.jedis", "Transaction", createInterfaceShim("redis.clients.jedis.Transaction",
                Arrays.asList("get", "set", "del", "exec", "discard")));
        addShim("redis.clients.jedis", "Pipeline", createInterfaceShim("redis.clients.jedis.Pipeline",
                Arrays.asList("get", "set", "del", "sync")));
        addShim("redis.clients.jedis", "ScanParams", createClassShim("redis.clients.jedis.ScanParams",
                Arrays.asList("match", "count")));
        addShim("redis.clients.jedis", "ScanResult", createClassShim("redis.clients.jedis.ScanResult",
                Arrays.asList("getResult", "getCursor")));
        
        // Reactor sub-packages (reactor.core.publisher.*) - 353 errors from logs (Mono/Flux exist but missing sub-packages)
        // Note: Mono and Flux already exist, but adding missing sub-packages
        addShim("reactor.core.publisher", "FluxSink", createInterfaceShim("reactor.core.publisher.FluxSink",
                Arrays.asList("next", "complete", "error")));
        addShim("reactor.core.publisher", "MonoSink", createInterfaceShim("reactor.core.publisher.MonoSink",
                Arrays.asList("success", "error", "empty")));
        addShim("reactor.core.publisher", "ConnectableFlux", createClassShim("reactor.core.publisher.ConnectableFlux",
                Arrays.asList("connect", "autoConnect")));
        addShim("reactor.core.scheduler", "Scheduler", createInterfaceShim("reactor.core.scheduler.Scheduler",
                Arrays.asList("schedule", "schedulePeriodically")));
        addShim("reactor.core.scheduler", "Schedulers", createClassShim("reactor.core.scheduler.Schedulers",
                Arrays.asList("immediate", "single", "parallel", "elastic", "boundedElastic")));
        addShim("reactor.util.context", "Context", createInterfaceShim("reactor.util.context.Context",
                Arrays.asList("get", "hasKey", "put", "putAll", "delete")));
        addShim("reactor.util.context", "ContextView", createInterfaceShim("reactor.util.context.ContextView",
                Arrays.asList("get", "hasKey")));
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
                // Log shim generation if verbose mode enabled
                if (Boolean.getBoolean("jess.verboseShims")) {
                    System.out.println("    Generated shim: " + fqn);
                }
            } else if (Boolean.getBoolean("jess.verboseShims")) {
                // Log why shim wasn't generated (already exists, etc.)
                if (factory.Type().get(fqn) != null) {
                    System.out.println("    Skipped shim (already exists): " + fqn);
                }
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
     * Returns true if shim was successfully generated, false otherwise.
     * Handles edge cases gracefully to prevent failures from cascading.
     */
    private boolean generateShim(ShimDefinition shim) {
        try {
            String fqn = shim.getFqn();
            if (fqn == null || fqn.isEmpty()) {
                if (Boolean.getBoolean("jess.verboseShims")) {
                    System.err.println("    Warning: Skipping shim with null/empty FQN");
                }
                return false;
            }
            
            int lastDot = fqn.lastIndexOf('.');
            String packageName = lastDot >= 0 ? fqn.substring(0, lastDot) : "";
            String className = lastDot >= 0 ? fqn.substring(lastDot + 1) : fqn;
            
            // Validate package and class names
            if (className == null || className.isEmpty()) {
                if (Boolean.getBoolean("jess.verboseShims")) {
                    System.err.println("    Warning: Skipping shim with invalid class name: " + fqn);
                }
                return false;
            }
            
            // Skip if class name is a Java keyword or invalid identifier
            if (isJavaKeyword(className) || !isValidJavaIdentifier(className)) {
                if (Boolean.getBoolean("jess.verboseShims")) {
                    System.err.println("    Warning: Skipping shim with invalid identifier: " + fqn);
                }
                return false;
            }

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

            // Special handling for Vavr Function interfaces - make them generic
            if (type instanceof CtInterface) {
                CtInterface<?> iface = (CtInterface<?>) type;
                if ("io.vavr.Function1".equals(fqn)) {
                    // Function1<T, R> - takes T, returns R
                    CtTypeParameter typeParamT = factory.Core().createTypeParameter();
                    typeParamT.setSimpleName("T");
                    iface.addFormalCtTypeParameter(typeParamT);
                    CtTypeParameter typeParamR = factory.Core().createTypeParameter();
                    typeParamR.setSimpleName("R");
                    iface.addFormalCtTypeParameter(typeParamR);
                } else if ("io.vavr.Function2".equals(fqn)) {
                    // Function2<T1, T2, R> - takes T1, T2, returns R
                    CtTypeParameter typeParamT1 = factory.Core().createTypeParameter();
                    typeParamT1.setSimpleName("T1");
                    iface.addFormalCtTypeParameter(typeParamT1);
                    CtTypeParameter typeParamT2 = factory.Core().createTypeParameter();
                    typeParamT2.setSimpleName("T2");
                    iface.addFormalCtTypeParameter(typeParamT2);
                    CtTypeParameter typeParamR = factory.Core().createTypeParameter();
                    typeParamR.setSimpleName("R");
                    iface.addFormalCtTypeParameter(typeParamR);
                } else if ("io.vavr.Function3".equals(fqn)) {
                    // Function3<T1, T2, T3, R>
                    CtTypeParameter typeParamT1 = factory.Core().createTypeParameter();
                    typeParamT1.setSimpleName("T1");
                    iface.addFormalCtTypeParameter(typeParamT1);
                    CtTypeParameter typeParamT2 = factory.Core().createTypeParameter();
                    typeParamT2.setSimpleName("T2");
                    iface.addFormalCtTypeParameter(typeParamT2);
                    CtTypeParameter typeParamT3 = factory.Core().createTypeParameter();
                    typeParamT3.setSimpleName("T3");
                    iface.addFormalCtTypeParameter(typeParamT3);
                    CtTypeParameter typeParamR = factory.Core().createTypeParameter();
                    typeParamR.setSimpleName("R");
                    iface.addFormalCtTypeParameter(typeParamR);
                } else if ("io.vavr.Function4".equals(fqn) || "io.vavr.Function5".equals(fqn) ||
                           "io.vavr.CheckedFunction1".equals(fqn) || "io.vavr.CheckedFunction2".equals(fqn)) {
                    // Add generic type parameters for other function types
                    CtTypeParameter typeParamT = factory.Core().createTypeParameter();
                    typeParamT.setSimpleName("T");
                    iface.addFormalCtTypeParameter(typeParamT);
                    CtTypeParameter typeParamR = factory.Core().createTypeParameter();
                    typeParamR.setSimpleName("R");
                    iface.addFormalCtTypeParameter(typeParamR);
                }
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

            // Add overloads for specific shims after initial generation
            addOverloadsForShim(type, fqn);

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
     * Add overloads for specific shim types that need multiple method signatures.
     * This is called after the initial shim is generated.
     */
    private void addOverloadsForShim(CtType<?> type, String fqn) {
        if (type == null || fqn == null) return;

        try {
            if ("org.slf4j.Logger".equals(fqn)) {
                addLoggerOverloads(type);
            } else if ("org.slf4j.LoggerFactory".equals(fqn)) {
                addLoggerFactoryOverloads(type);
            } else if ("org.apache.commons.lang3.StringUtils".equals(fqn)) {
                addStringUtilsOverloads(type);
            } else if ("org.mockito.Mockito".equals(fqn)) {
                addMockitoOverloads(type);
            } else if (fqn.startsWith("com.google.common.base.Preconditions")) {
                addPreconditionsMethods(type);
            } else if (fqn.startsWith("com.google.common.base.MoreObjects")) {
                addMoreObjectsMethods(type);
            } else if (fqn.startsWith("com.google.common.collect.Lists")) {
                addListsMethods(type);
            } else if (fqn.startsWith("com.google.common.collect.Sets")) {
                addSetsMethods(type);
            } else if (fqn.startsWith("com.google.common.collect.Maps")) {
                addMapsMethods(type);
            } else if (fqn.startsWith("org.antlr.v4.runtime.CharStream")) {
                addCharStreamMethods(type);
            } else if (fqn.startsWith("org.antlr.v4.runtime.TokenStream")) {
                addTokenStreamMethods(type);
            } else if (fqn.startsWith("org.antlr.v4.runtime.CommonTokenStream")) {
                addCommonTokenStreamMethods(type);
            }
        } catch (Exception e) {
            // Ignore overload addition failures - log only in verbose mode
            if (Boolean.getBoolean("jess.verboseShims")) {
                System.err.println("    Warning: Failed to add overloads for " + fqn + ": " + e.getMessage());
            }
        }
    }

    /**
     * Add a method to a shim type with a specific signature (for overloads).
     */
    private void addShimMethodWithSignature(CtType<?> type, String methodName,
            CtTypeReference<?> returnType, List<CtTypeReference<?>> paramTypes) {
        try {
            if (type instanceof CtAnnotationType) {
                return; // Skip annotations
            }

            // Check if method with exact signature already exists
            if (type instanceof CtClass) {
                CtClass<?> cls = (CtClass<?>) type;
                Collection<CtMethod<?>> existingMethods = cls.getMethods().stream()
                    .filter(m -> m.getSimpleName().equals(methodName))
                    .collect(java.util.stream.Collectors.toList());

                // Check if exact signature exists
                for (CtMethod<?> existing : existingMethods) {
                    if (existing.getParameters().size() == paramTypes.size()) {
                        boolean signatureMatches = true;
                        for (int i = 0; i < paramTypes.size(); i++) {
                            if (!existing.getParameters().get(i).getType().equals(paramTypes.get(i))) {
                                signatureMatches = false;
                                break;
                            }
                        }
                        if (signatureMatches) {
                            return; // Exact signature already exists
                        }
                    }
                }
            } else if (type instanceof CtInterface) {
                CtInterface<?> iface = (CtInterface<?>) type;
                Collection<CtMethod<?>> existingMethods = iface.getMethods().stream()
                    .filter(m -> m.getSimpleName().equals(methodName))
                    .collect(java.util.stream.Collectors.toList());

                for (CtMethod<?> existing : existingMethods) {
                    if (existing.getParameters().size() == paramTypes.size()) {
                        boolean signatureMatches = true;
                        for (int i = 0; i < paramTypes.size(); i++) {
                            if (!existing.getParameters().get(i).getType().equals(paramTypes.get(i))) {
                                signatureMatches = false;
                                break;
                            }
                        }
                        if (signatureMatches) {
                            return; // Exact signature already exists
                        }
                    }
                }
            }

            // Create parameters
            List<CtParameter<?>> params = new ArrayList<>();
            for (int i = 0; i < paramTypes.size(); i++) {
                CtParameter<?> param = factory.Core().createParameter();
                CtTypeReference<?> paramType = paramTypes.get(i);
                param.setType(paramType);
                // Ensure the type reference uses fully qualified name if it's a non-JDK type
                if (paramType != null && !paramType.isPrimitive()) {
                    String qn = paramType.getQualifiedName();
                    if (qn != null && !qn.startsWith("java.") && !qn.startsWith("javax.")
                        && !qn.startsWith("jakarta.") && !qn.startsWith("sun.")
                        && !qn.startsWith("jdk.")) {
                        // Ensure package is set from qualified name
                        if (paramType.getPackage() == null && qn.contains(".")) {
                            int lastDot = qn.lastIndexOf('.');
                            if (lastDot > 0) {
                                String pkgName = qn.substring(0, lastDot);
                                paramType.setPackage(factory.Package().createReference(pkgName));
                            }
                        }
                        // Force FQN printing for non-JDK types to avoid import/simple name issues
                        paramType.setSimplyQualified(true);
                        paramType.setImplicit(false);
                    }
                }
                param.setSimpleName("arg" + i);
                params.add(param);
            }

            Set<ModifierKind> mods = new HashSet<>();
            mods.add(ModifierKind.PUBLIC);

            boolean isUtilityClass = isUtilityClass(type);
            if (isUtilityClass && type instanceof CtClass) {
                mods.add(ModifierKind.STATIC);
            }

            if (type instanceof CtInterface && !(type instanceof CtAnnotationType)) {
                mods.add(ModifierKind.ABSTRACT);
            }

            // Create method
            CtMethod<?> method = factory.Method().create(
                type,
                mods,
                returnType,
                methodName,
                params,
                Collections.emptySet()
            );

            // Add body for classes
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
        } catch (Exception e) {
            // Ignore method creation failures - log only in verbose mode
            if (Boolean.getBoolean("jess.verboseShims")) {
                System.err.println("    Warning: Failed to add method " + methodName + " to " + type.getQualifiedName() + ": " + e.getMessage());
            }
        }
    }
    
    /**
     * Check if a string is a Java keyword.
     */
    private boolean isJavaKeyword(String str) {
        if (str == null) return false;
        // Common Java keywords that could cause issues
        String[] keywords = {
            "abstract", "assert", "boolean", "break", "byte", "case", "catch", "char", "class",
            "const", "continue", "default", "do", "double", "else", "enum", "extends", "final",
            "finally", "float", "for", "goto", "if", "implements", "import", "instanceof", "int",
            "interface", "long", "native", "new", "package", "private", "protected", "public",
            "return", "short", "static", "strictfp", "super", "switch", "synchronized", "this",
            "throw", "throws", "transient", "try", "void", "volatile", "while"
        };
        for (String keyword : keywords) {
            if (keyword.equals(str)) return true;
        }
        return false;
    }
    
    /**
     * Check if a string is a valid Java identifier.
     */
    private boolean isValidJavaIdentifier(String str) {
        if (str == null || str.isEmpty()) return false;
        // Must start with letter, underscore, or dollar sign
        char first = str.charAt(0);
        if (!Character.isJavaIdentifierStart(first)) return false;
        // Rest must be valid identifier part
        for (int i = 1; i < str.length(); i++) {
            if (!Character.isJavaIdentifierPart(str.charAt(i))) return false;
        }
        return true;
    }

    /**
     * Add overloads for SLF4J Logger interface.
     */
    private void addLoggerOverloads(CtType<?> type) {
        if (!(type instanceof CtInterface)) return;
        CtInterface<?> logger = (CtInterface<?>) type;

        CtTypeReference<?> stringType = factory.Type().createReference("java.lang.String");
        CtTypeReference<?> objectType = factory.Type().createReference("java.lang.Object");
        CtTypeReference<?> throwableType = factory.Type().createReference("java.lang.Throwable");
        CtTypeReference<?> booleanType = factory.Type().BOOLEAN_PRIMITIVE;
        CtTypeReference<?> voidType = factory.Type().VOID_PRIMITIVE;

        // Add isXxxEnabled() methods
        addShimMethodWithSignature(logger, "isTraceEnabled", booleanType, Collections.emptyList());
        addShimMethodWithSignature(logger, "isDebugEnabled", booleanType, Collections.emptyList());
        addShimMethodWithSignature(logger, "isInfoEnabled", booleanType, Collections.emptyList());
        addShimMethodWithSignature(logger, "isWarnEnabled", booleanType, Collections.emptyList());
        addShimMethodWithSignature(logger, "isErrorEnabled", booleanType, Collections.emptyList());

        // Add parameterized logging overloads: info(String, Object), info(String, Object, Object), etc.
        addShimMethodWithSignature(logger, "info", voidType, Arrays.asList(stringType, objectType));
        addShimMethodWithSignature(logger, "info", voidType, Arrays.asList(stringType, objectType, objectType));
        addShimMethodWithSignature(logger, "info", voidType, Arrays.asList(stringType, throwableType));

        // Same for debug, warn, error, trace
        for (String level : Arrays.asList("debug", "warn", "error", "trace")) {
            addShimMethodWithSignature(logger, level, voidType, Arrays.asList(stringType, objectType));
            addShimMethodWithSignature(logger, level, voidType, Arrays.asList(stringType, objectType, objectType));
            addShimMethodWithSignature(logger, level, voidType, Arrays.asList(stringType, throwableType));
        }

        // Ensure Marker shim exists before using it (it's needed for Logger overloads)
        String markerFqn = "org.slf4j.Marker";
        if (factory.Type().get(markerFqn) == null) {
            ShimDefinition markerShim = shimDefinitions.get(markerFqn);
            if (markerShim != null) {
                generateShim(markerShim);
            }
        }

        // Add Marker variants
        CtTypeReference<?> markerType = factory.Type().createReference(markerFqn);
        // Explicitly set the package to ensure it's preserved
        markerType.setPackage(factory.Package().createReference("org.slf4j"));
        markerType.setSimplyQualified(true);
        markerType.setImplicit(false);

        // info(Marker, String), info(Marker, String, Object), etc.
        addShimMethodWithSignature(logger, "info", voidType, Arrays.asList(markerType, stringType));
        addShimMethodWithSignature(logger, "info", voidType, Arrays.asList(markerType, stringType, objectType));
        addShimMethodWithSignature(logger, "info", voidType, Arrays.asList(markerType, stringType, throwableType));

        for (String level : Arrays.asList("debug", "warn", "error", "trace")) {
            addShimMethodWithSignature(logger, level, voidType, Arrays.asList(markerType, stringType));
            addShimMethodWithSignature(logger, level, voidType, Arrays.asList(markerType, stringType, objectType));
            addShimMethodWithSignature(logger, level, voidType, Arrays.asList(markerType, stringType, throwableType));
        }
    }

    /**
     * Add overloads for LoggerFactory.
     */
    private void addLoggerFactoryOverloads(CtType<?> type) {
        if (!(type instanceof CtClass)) return;
        CtClass<?> loggerFactory = (CtClass<?>) type;

        // Add getLogger(Class<?>) variant
        CtTypeReference<?> classType = factory.Type().createReference("java.lang.Class");
        CtTypeReference<?> loggerType = factory.Type().createReference("org.slf4j.Logger");
        addShimMethodWithSignature(loggerFactory, "getLogger", loggerType, Arrays.asList(classType));
    }

    /**
     * Add overloads for Commons Lang3 StringUtils.
     */
    private void addStringUtilsOverloads(CtType<?> type) {
        if (!(type instanceof CtClass)) return;
        CtClass<?> stringUtils = (CtClass<?>) type;

        CtTypeReference<?> stringType = factory.Type().createReference("java.lang.String");
        CtTypeReference<?> charType = factory.Type().CHARACTER_PRIMITIVE;
        CtTypeReference<?> objectType = factory.Type().createReference("java.lang.Object");
        CtTypeReference<?> booleanType = factory.Type().BOOLEAN_PRIMITIVE;
        CtTypeReference<?> intType = factory.Type().INTEGER_PRIMITIVE;

        // startsWithIgnoreCase(String, String)
        addShimMethodWithSignature(stringUtils, "startsWithIgnoreCase", booleanType,
            Arrays.asList(stringType, stringType));
        // endsWithIgnoreCase(String, String)
        addShimMethodWithSignature(stringUtils, "endsWithIgnoreCase", booleanType,
            Arrays.asList(stringType, stringType));
        // equalsIgnoreCase(String, String)
        addShimMethodWithSignature(stringUtils, "equalsIgnoreCase", booleanType,
            Arrays.asList(stringType, stringType));
        // join(Object[], String)
        addShimMethodWithSignature(stringUtils, "join", stringType,
            Arrays.asList(factory.Type().createArrayReference(objectType), stringType));
        // join(Iterable<?>, String)
        addShimMethodWithSignature(stringUtils, "join", stringType,
            Arrays.asList(factory.Type().createReference("java.lang.Iterable"), stringType));
        // split(String, String)
        addShimMethodWithSignature(stringUtils, "split", factory.Type().createArrayReference(stringType),
            Arrays.asList(stringType, stringType));
        // replace(String, String, String)
        addShimMethodWithSignature(stringUtils, "replace", stringType,
            Arrays.asList(stringType, stringType, stringType));
        // substring(String, int)
        addShimMethodWithSignature(stringUtils, "substring", stringType,
            Arrays.asList(stringType, intType));
        // substring(String, int, int)
        addShimMethodWithSignature(stringUtils, "substring", stringType,
            Arrays.asList(stringType, intType, intType));
    }

    /**
     * Add overloads for Mockito.
     */
    private void addMockitoOverloads(CtType<?> type) {
        if (!(type instanceof CtClass)) return;
        CtClass<?> mockito = (CtClass<?>) type;

        CtTypeReference<?> objectType = factory.Type().createReference("java.lang.Object");
        CtTypeReference<?> classType = factory.Type().createReference("java.lang.Class");
        CtTypeReference<?> ongoingStubbingType = factory.Type().createReference("org.mockito.stubbing.OngoingStubbing");
        CtTypeReference<?> answerType = factory.Type().createReference("org.mockito.stubbing.Answer");
        CtTypeReference<?> throwableType = factory.Type().createReference("java.lang.Throwable");
        CtTypeReference<?> voidType = factory.Type().VOID_PRIMITIVE;

        // spy(Object)
        addShimMethodWithSignature(mockito, "spy", objectType, Arrays.asList(objectType));
        // spy(Class<T>)
        addShimMethodWithSignature(mockito, "spy", objectType, Arrays.asList(classType));

        // doReturn(Object)
        addShimMethodWithSignature(mockito, "doReturn", ongoingStubbingType, Arrays.asList(objectType));
        // doThrow(Class<? extends Throwable>)
        addShimMethodWithSignature(mockito, "doThrow", ongoingStubbingType, Arrays.asList(classType));
        // doThrow(Throwable)
        addShimMethodWithSignature(mockito, "doThrow", ongoingStubbingType, Arrays.asList(throwableType));
        // doAnswer(Answer<?>)
        addShimMethodWithSignature(mockito, "doAnswer", ongoingStubbingType, Arrays.asList(answerType));
        // reset(Object...)
        addShimMethodWithSignature(mockito, "reset", voidType,
            Arrays.asList(factory.Type().createArrayReference(objectType)));
    }

    /**
     * Add methods for Guava Preconditions.
     */
    private void addPreconditionsMethods(CtType<?> type) {
        if (!(type instanceof CtClass)) return;
        CtClass<?> preconditions = (CtClass<?>) type;

        CtTypeReference<?> objectType = factory.Type().createReference("java.lang.Object");
        CtTypeReference<?> stringType = factory.Type().createReference("java.lang.String");
        CtTypeReference<?> booleanType = factory.Type().BOOLEAN_PRIMITIVE;
        CtTypeReference<?> voidType = factory.Type().VOID_PRIMITIVE;

        // checkNotNull(T)
        CtTypeParameter methodTypeParam = factory.Core().createTypeParameter();
        methodTypeParam.setSimpleName("T");
        // For simplicity, we'll use Object for now
        addShimMethodWithSignature(preconditions, "checkNotNull", objectType, Arrays.asList(objectType));
        // checkNotNull(T, String)
        addShimMethodWithSignature(preconditions, "checkNotNull", objectType, Arrays.asList(objectType, stringType));
        // checkArgument(boolean)
        addShimMethodWithSignature(preconditions, "checkArgument", voidType, Arrays.asList(booleanType));
        // checkArgument(boolean, String)
        addShimMethodWithSignature(preconditions, "checkArgument", voidType, Arrays.asList(booleanType, stringType));
        // checkState(boolean)
        addShimMethodWithSignature(preconditions, "checkState", voidType, Arrays.asList(booleanType));
        // checkState(boolean, String)
        addShimMethodWithSignature(preconditions, "checkState", voidType, Arrays.asList(booleanType, stringType));
    }

    /**
     * Add methods for Guava MoreObjects.
     */
    private void addMoreObjectsMethods(CtType<?> type) {
        if (!(type instanceof CtClass)) return;
        CtClass<?> moreObjects = (CtClass<?>) type;

        CtTypeReference<?> objectType = factory.Type().createReference("java.lang.Object");
        CtTypeReference<?> stringType = factory.Type().createReference("java.lang.String");

        // toStringHelper(Object)
        CtTypeReference<?> toStringHelperType = factory.Type().createReference("com.google.common.base.MoreObjects.ToStringHelper");
        addShimMethodWithSignature(moreObjects, "toStringHelper", toStringHelperType, Arrays.asList(objectType));
        // toStringHelper(String)
        addShimMethodWithSignature(moreObjects, "toStringHelper", toStringHelperType, Arrays.asList(stringType));
        // firstNonNull(T, T)
        addShimMethodWithSignature(moreObjects, "firstNonNull", objectType, Arrays.asList(objectType, objectType));
    }

    /**
     * Add methods for Guava Lists.
     */
    private void addListsMethods(CtType<?> type) {
        if (!(type instanceof CtClass)) return;
        CtClass<?> lists = (CtClass<?>) type;

        CtTypeReference<?> listType = factory.Type().createReference("java.util.List");
        CtTypeReference<?> arrayListType = factory.Type().createReference("java.util.ArrayList");
        CtTypeReference<?> linkedListType = factory.Type().createReference("java.util.LinkedList");

        // newArrayList()
        addShimMethodWithSignature(lists, "newArrayList", arrayListType, Collections.emptyList());
        // newLinkedList()
        addShimMethodWithSignature(lists, "newLinkedList", linkedListType, Collections.emptyList());
        // asList(T...)
        CtTypeReference<?> objectType = factory.Type().createReference("java.lang.Object");
        addShimMethodWithSignature(lists, "asList", listType,
            Arrays.asList(factory.Type().createArrayReference(objectType)));
    }

    /**
     * Add methods for Guava Sets.
     */
    private void addSetsMethods(CtType<?> type) {
        if (!(type instanceof CtClass)) return;
        CtClass<?> sets = (CtClass<?>) type;

        CtTypeReference<?> hashSetType = factory.Type().createReference("java.util.HashSet");
        CtTypeReference<?> linkedHashSetType = factory.Type().createReference("java.util.LinkedHashSet");
        CtTypeReference<?> treeSetType = factory.Type().createReference("java.util.TreeSet");

        // newHashSet()
        addShimMethodWithSignature(sets, "newHashSet", hashSetType, Collections.emptyList());
        // newLinkedHashSet()
        addShimMethodWithSignature(sets, "newLinkedHashSet", linkedHashSetType, Collections.emptyList());
        // newTreeSet()
        addShimMethodWithSignature(sets, "newTreeSet", treeSetType, Collections.emptyList());
    }

    /**
     * Add methods for Guava Maps.
     */
    private void addMapsMethods(CtType<?> type) {
        if (!(type instanceof CtClass)) return;
        CtClass<?> maps = (CtClass<?>) type;

        CtTypeReference<?> hashMapType = factory.Type().createReference("java.util.HashMap");
        CtTypeReference<?> linkedHashMapType = factory.Type().createReference("java.util.LinkedHashMap");
        CtTypeReference<?> treeMapType = factory.Type().createReference("java.util.TreeMap");
        CtTypeReference<?> concurrentMapType = factory.Type().createReference("java.util.concurrent.ConcurrentMap");

        // newHashMap()
        addShimMethodWithSignature(maps, "newHashMap", hashMapType, Collections.emptyList());
        // newLinkedHashMap()
        addShimMethodWithSignature(maps, "newLinkedHashMap", linkedHashMapType, Collections.emptyList());
        // newTreeMap()
        addShimMethodWithSignature(maps, "newTreeMap", treeMapType, Collections.emptyList());
        // newConcurrentMap()
        addShimMethodWithSignature(maps, "newConcurrentMap", concurrentMapType, Collections.emptyList());
    }

    /**
     * Add methods for ANTLR CharStream.
     */
    private void addCharStreamMethods(CtType<?> type) {
        if (!(type instanceof CtInterface)) return;
        CtInterface<?> charStream = (CtInterface<?>) type;

        CtTypeReference<?> stringType = factory.Type().createReference("java.lang.String");
        CtTypeReference<?> intType = factory.Type().INTEGER_PRIMITIVE;

        // getText()
        addShimMethodWithSignature(charStream, "getText", stringType, Collections.emptyList());
        // getSourceName()
        addShimMethodWithSignature(charStream, "getSourceName", stringType, Collections.emptyList());
    }

    /**
     * Add methods for ANTLR TokenStream.
     */
    private void addTokenStreamMethods(CtType<?> type) {
        if (!(type instanceof CtInterface)) return;
        CtInterface<?> tokenStream = (CtInterface<?>) type;

        CtTypeReference<?> tokenType = factory.Type().createReference("org.antlr.v4.runtime.Token");
        CtTypeReference<?> intType = factory.Type().INTEGER_PRIMITIVE;
        CtTypeReference<?> voidType = factory.Type().VOID_PRIMITIVE;

        // get(int)
        addShimMethodWithSignature(tokenStream, "get", tokenType, Arrays.asList(intType));
        // consume()
        addShimMethodWithSignature(tokenStream, "consume", voidType, Collections.emptyList());
        // LA(int)
        addShimMethodWithSignature(tokenStream, "LA", intType, Arrays.asList(intType));
        // mark()
        addShimMethodWithSignature(tokenStream, "mark", intType, Collections.emptyList());
        // release(int)
        addShimMethodWithSignature(tokenStream, "release", voidType, Arrays.asList(intType));
        // reset()
        addShimMethodWithSignature(tokenStream, "reset", voidType, Collections.emptyList());
    }

    /**
     * Add methods for ANTLR CommonTokenStream.
     */
    private void addCommonTokenStreamMethods(CtType<?> type) {
        // CommonTokenStream extends TokenStream, so it inherits those methods
        // Add any specific methods if needed
        addTokenStreamMethods(type);
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

