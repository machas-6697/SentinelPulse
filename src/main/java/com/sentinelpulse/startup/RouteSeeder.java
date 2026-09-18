package com.sentinelpulse.startup;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sentinelpulse.model.RouteDefinition;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Component;

import java.io.InputStream;
import java.util.List;

/**
 * RouteSeeder — ApplicationRunner that seeds route definitions into Redis on startup.
 *
 * Reads routes.json from the classpath (src/main/resources/routes.json) and writes
 * each route as a Redis Hash immediately after the Spring context is fully initialized.
 *
 * Redis key format per route:
 *   Key:    route:{path}           (e.g., route:/api/v1/orders)
 *   Type:   Hash
 *   Fields:
 *     target_url → downstream service base URL
 *     methods    → comma-separated allowed HTTP methods (e.g., "GET,POST,PUT,DELETE")
 *
 * Design rationale:
 *   - Using ApplicationRunner (not @PostConstruct) ensures Redis connection
 *     is fully established before seeding begins.
 *   - Routes are OVERWRITTEN on each startup — this is intentional and
 *     keeps Redis always in sync with the routes.json file on disk.
 *   - No TTL is set on route keys — they are permanent configuration data.
 *
 * Also seeds a default API key for initial testing if none exists yet:
 *   Key:   apikeys:sentinel-dev-key-001
 *   Value: dev-client-001
 *   (Can be deleted and replaced with production keys via RedisInsight)
 */
@Component
public class RouteSeeder implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(RouteSeeder.class);
    private static final String ROUTES_FILE = "routes.json";

    private final RedisTemplate<String, String> redisTemplate;
    private final ObjectMapper objectMapper;
    private final com.sentinelpulse.service.RouteService routeService;

    public RouteSeeder(RedisTemplate<String, String> redisTemplate,
                       ObjectMapper objectMapper,
                       com.sentinelpulse.service.RouteService routeService) {
        this.redisTemplate = redisTemplate;
        this.objectMapper = objectMapper;
        this.routeService = routeService;
    }

    @Override
    public void run(ApplicationArguments args) {
        log.info("[SEEDER] Starting Redis seeding from {}", ROUTES_FILE);
        seedRoutes();
        routeService.invalidateCache();
        seedDefaultApiKey();
        log.info("[SEEDER] Redis seeding complete");
    }

    /**
     * Reads routes.json from classpath and writes each route to Redis as a Hash.
     */
    private void seedRoutes() {
        try {
            ClassPathResource resource = new ClassPathResource(ROUTES_FILE);
            InputStream inputStream = resource.getInputStream();

            List<RouteDefinition> routes = objectMapper.readValue(
                    inputStream,
                    new TypeReference<List<RouteDefinition>>() {}
            );

            int seeded = 0;
            for (RouteDefinition route : routes) {
                String redisKey = "route:" + route.getPath();
                String methodsCsv = String.join(",", route.getMethods());

                redisTemplate.opsForHash().put(redisKey, "target_url", route.getTargetUrl());
                redisTemplate.opsForHash().put(redisKey, "methods",    methodsCsv);

                log.info("[SEEDER] Route seeded → {} → {} [{}]",
                        route.getPath(), route.getTargetUrl(), methodsCsv);
                seeded++;
            }

            log.info("[SEEDER] {} route(s) successfully seeded into Redis", seeded);

        } catch (Exception e) {
            log.error("[SEEDER] Failed to seed routes from {}: {}", ROUTES_FILE, e.getMessage(), e);
            // Non-fatal: app continues to start. Existing Redis routes (from a previous run) remain intact.
        }
    }

    /**
     * Seeds a default development API key into Redis if it does not already exist.
     *
     * Key:   apikeys:sentinel-dev-key-001
     * Value: dev-client-001
     *
     * Use this key in Postman as the X-API-Key header value:
     *   X-API-Key: sentinel-dev-key-001
     *
     * To add additional keys, use RedisInsight or redis-cli:
     *   SET apikeys:your-key-here your-client-id
     */
    private void seedDefaultApiKey() {
        String redisKey   = "apikeys:sentinel-dev-key-001";
        String clientId   = "dev-client-001";

        Boolean absent = redisTemplate.opsForValue().setIfAbsent(redisKey, clientId);
        if (Boolean.TRUE.equals(absent)) {
            log.info("[SEEDER] Default API key seeded → key='sentinel-dev-key-001', client_id='{}'", clientId);
        } else {
            log.debug("[SEEDER] Default API key already exists in Redis — skipped");
        }
    }
}
