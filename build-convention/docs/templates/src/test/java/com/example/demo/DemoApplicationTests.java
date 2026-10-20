package com.example.demo;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
class DemoApplicationTests {

    @Autowired
    private ApplicationContext applicationContext;

    @Test
    @DisplayName("애플리케이션 컨텍스트가 뜬다")
    void contextLoads() {
        final Class<DemoApplication> applicationType = DemoApplication.class;

        final DemoApplication application = applicationContext.getBean(applicationType);

        assertThat(application).isNotNull();
    }

}
