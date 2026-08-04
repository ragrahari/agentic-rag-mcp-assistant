package com.rupeshagrahari.mcpcardserver;

import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;

@SpringBootApplication
public class CardServerApplication {

    public static void main(String[] args) {
        SpringApplication.run(CardServerApplication.class, args);
    }

    @Bean
    public ToolCallbackProvider cardTools(CardBalanceTools cardBalanceTools) {
        return MethodToolCallbackProvider.builder().toolObjects(cardBalanceTools).build();
    }

}
