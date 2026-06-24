package com.shazam;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.EnableAspectJAutoProxy;
import org.springframework.context.annotation.PropertySource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.connection.jedis.JedisConnectionFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.lang.NonNull;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.io.IOException;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;

import java.util.EnumMap;
import java.util.Map;

import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import com.shazam.interceptor.RateLimitInterceptor;
import com.shazam.model.RouteConfigs;
import com.shazam.model.types.SchedulerTypes;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

@Configuration
@EnableWebMvc
@ComponentScan(basePackages = "com.shazam")
@PropertySource("classpath:application.properties")
@EnableAspectJAutoProxy
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

    @Bean
    public MeterRegistry meterRegistry() {
        return new SimpleMeterRegistry();
    }

    @Value("${redis.host:localhost}")
    private String redisHost;

    @Value("${redis.port:6379}")
    private int redisPort;

    @Bean
    public RedisConnectionFactory redisConnectionFactory() {
        return new LettuceConnectionFactory(redisHost, redisPort);
    }

    @Bean
    public StringRedisTemplate stringRedisTemplate(RedisConnectionFactory connectionFactory) {
        return new StringRedisTemplate(connectionFactory);
    }

    /** One compiled Lua script per algorithm, loaded from src/main/resources/lua_scripts. */
    @Bean
    public Map<SchedulerTypes, RedisScript<Long>> rateLimitScripts() {
        Map<SchedulerTypes, RedisScript<Long>> scripts = new EnumMap<>(SchedulerTypes.class);
        scripts.put(SchedulerTypes.fixed_window, loadScript("lua_scripts/fixed_window.lua"));
        scripts.put(SchedulerTypes.token_bucket, loadScript("lua_scripts/token_bucket.lua"));
        scripts.put(SchedulerTypes.leaking_bucket, loadScript("lua_scripts/leaking_bucket.lua"));
        scripts.put(SchedulerTypes.sliding_window_counter, loadScript("lua_scripts/sliding_window_counter.lua"));
        scripts.put(SchedulerTypes.sliding_window_log, loadScript("lua_scripts/sliding_window_log.lua"));
        return scripts;
    }

    private RedisScript<Long> loadScript(String path) {
        return RedisScript.of(new ClassPathResource(path), Long.class);
    }

    @Override
    public void addInterceptors(@NonNull InterceptorRegistry registry) {
        registry.addInterceptor(rateLimitInterceptorBean)
                .addPathPatterns("/**")
                .excludePathPatterns("/gateway/**");
    }
}
