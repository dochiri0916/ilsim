package com.example.risksample;

import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.TimeUnit;
import org.aopalliance.intercept.MethodInterceptor;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;

/** Pauses after actual DB reads; all repository calls still reach MySQL. */
@TestConfiguration(proxyBeanMethods = false)
class RaceWindowConfiguration {
    @Bean
    static RaceGate raceGate() { return new RaceGate(); }

    static final class RaceGate implements BeanPostProcessor {
        private volatile CyclicBarrier barrier;
        private volatile String businessKey;

        void open(String key) {
            businessKey = key;
            barrier = new CyclicBarrier(2);
        }

        void close() {
            barrier = null;
            businessKey = null;
        }

        @Override
        public Object postProcessAfterInitialization(Object bean, String beanName) {
            if (!(bean instanceof UnsafeRepository)) return bean;
            ProxyFactory factory = new ProxyFactory(bean);
            factory.addAdvice((MethodInterceptor) invocation -> {
                Object result = invocation.proceed();
                CyclicBarrier active = barrier;
                if (active != null && invocation.getMethod().getName().equals("existsByBusinessKey")
                        && invocation.getArguments().length == 1
                        && businessKey.equals(invocation.getArguments()[0])
                        && Boolean.FALSE.equals(result)) {
                    // Both real reads must return absent before either caller can insert.
                    active.await(10, TimeUnit.SECONDS);
                }
                return result;
            });
            return factory.getProxy();
        }
    }
}
