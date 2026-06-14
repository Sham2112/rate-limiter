package com.shazam;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.PropertySource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.lang.NonNull;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.io.IOException;

import org.springframework.beans.factory.annotation.Autowired;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import com.shazam.interceptor.RateLimitInterceptor;
import com.shazam.model.RouteConfigs;

@Configuration
@EnableWebMvc
@ComponentScan(basePackages = "com.shazam")
@PropertySource("classpath:application.properties")
public class AppConfig implements WebMvcConfigurer {

    @Autowired
    public RateLimitInterceptor rateLimitInterceptorBean;

    @Bean
    public RouteConfigs routeConfigs(){
        ObjectMapper mapper = new ObjectMapper(new YAMLFactory());
        mapper.findAndRegisterModules();
        try {
            RouteConfigs configs = mapper.readValue(new ClassPathResource("input.yaml").getInputStream(), RouteConfigs.class);
            if (configs == null || configs.getRoutes() == null || configs.getRoutes().isEmpty()) {
                throw new IllegalStateException("input.yaml contained no routes");
            }
            return configs;
        } catch (IOException e) {
            throw new IllegalStateException("Failed to load route config from input.yaml", e);
        }
    }

    @Override
    public void addInterceptors(@NonNull InterceptorRegistry registry) {
        registry.addInterceptor(rateLimitInterceptorBean)
                .addPathPatterns("/**");
    }
}
