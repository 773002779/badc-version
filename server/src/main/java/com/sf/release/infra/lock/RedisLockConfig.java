package com.sf.release.infra.lock;

import org.redisson.Redisson;
import org.redisson.api.RedissonClient;
import org.redisson.config.Config;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Creates the single-server Redisson client for {@link RedisDistributedLock}. The whole
 * configuration is conditional on {@code release.redis.enabled = true}, so the application
 * boots with no Redis server at all (the default in {@code application.yml} and in tests).
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "release.redis.enabled", havingValue = "true")
public class RedisLockConfig {

    @Bean(destroyMethod = "shutdown")
    public RedissonClient redissonClient(
            @Value("${release.redis.host:127.0.0.1}") String host,
            @Value("${release.redis.port:6379}") int port,
            @Value("${release.redis.password:}") String password) {
        Config config = new Config();
        config.useSingleServer()
            .setAddress("redis://" + host + ":" + port)
            .setPassword(password == null || password.isBlank() ? null : password);
        return Redisson.create(config);
    }
}