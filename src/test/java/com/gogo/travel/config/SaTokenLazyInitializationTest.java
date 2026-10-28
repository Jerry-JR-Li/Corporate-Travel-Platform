package com.gogo.travel.config;

import cn.dev33.satoken.SaManager;
import cn.dev33.satoken.config.SaTokenConfig;
import cn.dev33.satoken.context.SaTokenContext;
import cn.dev33.satoken.context.SaTokenContextDefaultImpl;
import cn.dev33.satoken.spring.SaBeanInject;
import cn.dev33.satoken.spring.SaTokenContextForSpringInJakartaServlet;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Lazy;

import static org.junit.jupiter.api.Assertions.assertInstanceOf;

class SaTokenLazyInitializationTest {

    @Test
    void registersServletContextBeforeTheFirstRequest() {
        SaTokenContext originalContext = SaManager.getSaTokenContext();
        SaManager.setSaTokenContext(new SaTokenContextDefaultImpl());

        try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext(
                SaTokenConfigure.class,
                LazySaTokenBeans.class)) {
            assertInstanceOf(
                    SaTokenContextForSpringInJakartaServlet.class,
                    SaManager.getSaTokenContext());
        } finally {
            SaManager.setSaTokenContext(originalContext);
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class LazySaTokenBeans {

        @Bean
        @Lazy
        SaBeanInject saBeanInject() {
            SaBeanInject bean = new SaBeanInject(null, new SaTokenConfig());
            bean.setSaTokenContext(new SaTokenContextForSpringInJakartaServlet());
            return bean;
        }
    }
}
