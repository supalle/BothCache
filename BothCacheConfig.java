
import org.springframework.boot.autoconfigure.cache.CacheProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.cache.caffeine.CaffeineCache;
import org.springframework.cache.caffeine.CaffeineCacheManager;
import org.springframework.cache.interceptor.AbstractCacheResolver;
import org.springframework.cache.interceptor.CacheOperationInvocationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.cache.RedisCache;
import org.springframework.data.redis.cache.RedisCacheManager;
import org.springframework.util.StringUtils;

import java.util.Collection;
import java.util.concurrent.Callable;

/**
 * @author supalle
 * @date 2021年7月13日
 */
@Configuration
@EnableConfigurationProperties(CacheProperties.class)
public class BothCacheConfig {

    @Bean("bothCacheResolver")
    public BothCacheResolver bothCacheResolver(RedisCacheManager redisCacheManager, CacheProperties cacheProperties) {
        return new BothCacheResolver(redisCacheManager, cacheProperties);
    }

    public static class BothCacheResolver extends AbstractCacheResolver {

        public BothCacheResolver(RedisCacheManager redisCacheManager, CacheProperties cacheProperties) {
            super(new BothCacheManager(redisCacheManager, createCaffeineCacheManager(cacheProperties)));
        }

        @Override
        protected Collection<String> getCacheNames(CacheOperationInvocationContext<?> context) {
            return context.getOperation().getCacheNames();
        }

        private static CaffeineCacheManager createCaffeineCacheManager(CacheProperties cacheProperties) {
            CaffeineCacheManager caffeineCacheManager = new CaffeineCacheManager();
            if (cacheProperties != null && cacheProperties.getCaffeine() != null && StringUtils.hasText(cacheProperties.getCaffeine().getSpec())) {
                caffeineCacheManager.setCacheSpecification(cacheProperties.getCaffeine().getSpec());
            }
            return caffeineCacheManager;
        }

    }

    public static class BothCacheManager implements CacheManager {

        private final RedisCacheManager redisCacheManager;
        private final CaffeineCacheManager caffeineCacheManager;

        public BothCacheManager(RedisCacheManager redisCacheManager, CaffeineCacheManager caffeineCacheManager) {
            this.redisCacheManager = redisCacheManager;
            this.caffeineCacheManager = caffeineCacheManager;
        }

        @Override
        public Cache getCache(String name) {
            RedisCache redisCache = (RedisCache) redisCacheManager.getCache(name);
            CaffeineCache caffeineCache = (CaffeineCache) caffeineCacheManager.getCache(name);
            return new BothCache(caffeineCache, redisCache);
        }

        @Override
        public Collection<String> getCacheNames() {
            return redisCacheManager.getCacheNames();
        }
    }

    public static class BothCache implements Cache {

        private final CaffeineCache caffeineCache;
        private final RedisCache redisCache;

        public BothCache(CaffeineCache caffeineCache, RedisCache redisCache) {
            this.caffeineCache = caffeineCache;
            this.redisCache = redisCache;
        }

        @Override
        public String getName() {
            return redisCache.getName();
        }

        @Override
        public Object getNativeCache() {
            return redisCache.getNativeCache();
        }

        @Override
        public ValueWrapper get(Object key) {
            ValueWrapper valueWrapper = caffeineCache.get(key);
            if (valueWrapper == null) {
                synchronized (redisCache) {
                    valueWrapper = caffeineCache.get(key);
                    if (valueWrapper == null) {
                        valueWrapper = redisCache.get(key);
                        if (valueWrapper != null) {
                            caffeineCache.put(key, valueWrapper.get());
                        }
                    }
                }
            }
            return valueWrapper;
        }

        @Override
        public <T> T get(Object key, Class<T> type) {
            T value = caffeineCache.get(key, type);
            if (value == null) {
                synchronized (redisCache) {
                    value = caffeineCache.get(key, type);
                    if (value == null) {
                        value = redisCache.get(key, type);
                        if (value != null) {
                            caffeineCache.put(key, value);
                        }
                    }
                }
            }
            return value;
        }

        @Override
        public <T> T get(Object key, Callable<T> valueLoader) {
            return caffeineCache.get(key, () -> redisCache.get(key, valueLoader));
        }

        @Override
        public void put(Object key, Object value) {
            redisCache.put(key, value);
        }

        @Override
        public ValueWrapper putIfAbsent(Object key, Object value) {
            caffeineCache.putIfAbsent(key, value);
            return redisCache.putIfAbsent(key, value);
        }

        @Override
        public void evict(Object key) {
            caffeineCache.evict(key);
            redisCache.evict(key);
        }

        @Override
        public void clear() {
            caffeineCache.clear();
            redisCache.clear();
        }
    }

}
