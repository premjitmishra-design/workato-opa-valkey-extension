package com.workato.onprem.valkey;

import io.valkey.Jedis;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.context.SmartLifecycle;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Test harness for the OPA Valkey extension.
 *
 * <p>The real Workato Agent hosts {@link ValkeyExtension} inside its own Spring
 * {@code WebApplicationContext} and injects {@code Environment} from {@code conf/config.yml}.
 * Here we reproduce that wiring standalone with a minimal {@link AnnotationConfigApplicationContext}
 * (so {@code @Inject private Environment env} resolves exactly as it would in the Agent) and drive
 * the controller through {@link MockMvc}, so requests/responses are exercised exactly as they would
 * be over the extension's real HTTP endpoint - against a REAL local Valkey instance, not a mock.
 *
 * <p>Requires Valkey running locally - see README.md ("brew services start valkey") - and the
 * sample dataset seeded by scripts/seed-sample-data.sh. Host/port are overridable via the
 * {@code valkeyHost} / {@code valkeyPort} system properties (see build.gradle's {@code test} block).
 */
class ValkeyExtensionTest {

    @Configuration
    static class TestConfig {
        @Bean
        ValkeyExtension valkeyExtension() {
            return new ValkeyExtension();
        }
    }

    private static AnnotationConfigApplicationContext context;
    private static MockMvc mockMvc;

    @BeforeAll
    static void setUp() {
        context = new AnnotationConfigApplicationContext(TestConfig.class);
        ValkeyExtension controller = context.getBean(ValkeyExtension.class);
        mockMvc = MockMvcBuilders.standaloneSetup(controller).build();

        // Make sure the fixed sample dataset (see scripts/seed-sample-data.sh) is present,
        // independent of test execution order / a freshly flushed instance.
        String host = System.getProperty("valkeyHost", "127.0.0.1");
        int port = Integer.parseInt(System.getProperty("valkeyPort", "6379"));
        try (Jedis jedis = new Jedis(host, port)) {
            jedis.set("user:1001:name", "Ada Lovelace");
            jedis.set("user:1001:email", "ada@example.com");
            jedis.hset("user:1002", "name", "Grace Hopper");
            jedis.hset("user:1002", "email", "grace@example.com");
            jedis.hset("user:1002", "role", "Rear Admiral");
            jedis.del("recent:logins");
            jedis.rpush("recent:logins", "user:1001", "user:1002", "user:1003");
            jedis.sadd("active:users", "user:1001", "user:1002");
            jedis.zadd("leaderboard", 100, "user:1001");
            jedis.zadd("leaderboard", 87, "user:1002");
            jedis.zadd("leaderboard", 42, "user:1003");
        }
    }

    @AfterAll
    static void tearDown() {
        if (context != null) {
            context.close();
        }
    }

    @Test
    void healthReportsUp() throws Exception {
        mockMvc.perform(get("/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"))
                .andExpect(jsonPath("$.pong").value("PONG"));
    }

    @Test
    void getReturnsStringValue() throws Exception {
        mockMvc.perform(post("/get")
                        .contentType("application/json")
                        .content("{\"key\":\"user:1001:name\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.type").value("string"))
                .andExpect(jsonPath("$.found").value(true))
                .andExpect(jsonPath("$.value").value("Ada Lovelace"));
    }

    @Test
    void getReturnsHashValue() throws Exception {
        mockMvc.perform(post("/get")
                        .contentType("application/json")
                        .content("{\"key\":\"user:1002\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.type").value("hash"))
                .andExpect(jsonPath("$.value.name").value("Grace Hopper"))
                .andExpect(jsonPath("$.value.role").value("Rear Admiral"));
    }

    @Test
    void getReturnsListValue() throws Exception {
        mockMvc.perform(post("/get")
                        .contentType("application/json")
                        .content("{\"key\":\"recent:logins\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.type").value("list"))
                .andExpect(jsonPath("$.value[0]").value("user:1001"))
                .andExpect(jsonPath("$.value.length()").value(3));
    }

    @Test
    void getReturnsSetValue() throws Exception {
        mockMvc.perform(post("/get")
                        .contentType("application/json")
                        .content("{\"key\":\"active:users\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.type").value("set"))
                .andExpect(jsonPath("$.value.length()").value(2));
    }

    @Test
    void getReturnsSortedSetValueOrderedByScore() throws Exception {
        mockMvc.perform(post("/get")
                        .contentType("application/json")
                        .content("{\"key\":\"leaderboard\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.type").value("zset"))
                .andExpect(jsonPath("$.value[0].member").value("user:1003"))
                .andExpect(jsonPath("$.value[0].score").value(42.0))
                .andExpect(jsonPath("$.value[2].member").value("user:1001"))
                .andExpect(jsonPath("$.value[2].score").value(100.0));
    }

    @Test
    void getReturnsNotFoundMarkerForMissingKey() throws Exception {
        mockMvc.perform(post("/get")
                        .contentType("application/json")
                        .content("{\"key\":\"does:not:exist\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.type").value("none"))
                .andExpect(jsonPath("$.found").value(false))
                .andExpect(jsonPath("$.value").doesNotExist());
    }

    @Test
    void getRejectsMissingKeyParameter() throws Exception {
        mockMvc.perform(post("/get")
                        .contentType("application/json")
                        .content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("`key` is required"));
    }

    @Test
    void keysMatchesGlobPattern() throws Exception {
        mockMvc.perform(get("/keys").param("pattern", "user:1001:*"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.count").value(2));
    }

    @Test
    void setCreatesRetrievableKey() throws Exception {
        mockMvc.perform(post("/set")
                        .contentType("application/json")
                        .content("{\"key\":\"cache:greeting\",\"value\":\"hello\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.key").value("cache:greeting"))
                .andExpect(jsonPath("$.set").value(true))
                .andExpect(jsonPath("$.ttlSeconds").doesNotExist());

        mockMvc.perform(post("/get")
                        .contentType("application/json")
                        .content("{\"key\":\"cache:greeting\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.type").value("string"))
                .andExpect(jsonPath("$.value").value("hello"));
    }

    @Test
    void setWithTtlSecondsAppliesExpiry() throws Exception {
        mockMvc.perform(post("/set")
                        .contentType("application/json")
                        .content("{\"key\":\"cache:session\",\"value\":\"abc123\",\"ttlSeconds\":60}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ttlSeconds").value(60));

        try (Jedis jedis = new Jedis(
                System.getProperty("valkeyHost", "127.0.0.1"),
                Integer.parseInt(System.getProperty("valkeyPort", "6379")))) {
            long ttl = jedis.ttl("cache:session");
            assertTrue(ttl > 0 && ttl <= 60, "expected a positive TTL <= 60, got " + ttl);
        }
    }

    @Test
    void setRejectsMissingValue() throws Exception {
        mockMvc.perform(post("/set")
                        .contentType("application/json")
                        .content("{\"key\":\"cache:greeting\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("`value` is required"));
    }

    @Test
    void setRejectsNonPositiveTtlSeconds() throws Exception {
        mockMvc.perform(post("/set")
                        .contentType("application/json")
                        .content("{\"key\":\"cache:greeting\",\"value\":\"hello\",\"ttlSeconds\":0}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("`ttlSeconds` must be a positive integer"));
    }

    @Test
    void setBatchCreatesMultipleKeys() throws Exception {
        mockMvc.perform(post("/set-batch")
                        .contentType("application/json")
                        .content("{\"entries\":["
                                + "{\"key\":\"cache:batch:1\",\"value\":\"one\"},"
                                + "{\"key\":\"cache:batch:2\",\"value\":\"two\",\"ttlSeconds\":60}"
                                + "]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.count").value(2))
                .andExpect(jsonPath("$.keys[0]").value("cache:batch:1"))
                .andExpect(jsonPath("$.keys[1]").value("cache:batch:2"))
                .andExpect(jsonPath("$.set").value(true));

        mockMvc.perform(post("/get")
                        .contentType("application/json")
                        .content("{\"key\":\"cache:batch:1\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.value").value("one"));

        try (Jedis jedis = new Jedis(
                System.getProperty("valkeyHost", "127.0.0.1"),
                Integer.parseInt(System.getProperty("valkeyPort", "6379")))) {
            assertTrue(jedis.ttl("cache:batch:2") > 0, "expected cache:batch:2 to carry a TTL");
        }
    }

    @Test
    void setBatchRejectsEmptyEntries() throws Exception {
        mockMvc.perform(post("/set-batch")
                        .contentType("application/json")
                        .content("{\"entries\":[]}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("`entries` must be a non-empty array"));
    }

    @Test
    void setBatchRejectsEntryMissingValue() throws Exception {
        mockMvc.perform(post("/set-batch")
                        .contentType("application/json")
                        .content("{\"entries\":[{\"key\":\"cache:batch:bad\"}]}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("entry `cache:batch:bad` requires a `value`"));
    }

    @Test
    void searchReturnsMatchingEntriesWithValues() throws Exception {
        mockMvc.perform(get("/search").param("pattern", "user:1001:*"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.count").value(2))
                .andExpect(jsonPath("$.entries[?(@.key == 'user:1001:name')].value").value("Ada Lovelace"))
                .andExpect(jsonPath("$.entries[?(@.key == 'user:1001:email')].value").value("ada@example.com"));
    }

    @Test
    void searchDefaultsToEveryKeyWhenPatternOmitted() throws Exception {
        mockMvc.perform(get("/search"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.pattern").value("*"))
                .andExpect(jsonPath("$.entries").isArray());
    }

    /**
     * Exercises the {@link SmartLifecycle} {@code start()}/{@code stop()} hooks directly, on its
     * own short-lived context so it doesn't disturb the shared {@code mockMvc} used above.
     * {@code start()} must have already run automatically on context refresh, opening a usable
     * pool with no lazy first-request init, and {@code stop()} must close it (triggered
     * automatically on context close, since {@code isAutoStartup()} is {@code true}) so a call
     * made after shutdown degrades to a clean "DOWN" response instead of leaking the pool or
     * throwing.
     */
    @Test
    void lifecycleHooksOpenAndClosePoolOnStartAndStop() throws Exception {
        AnnotationConfigApplicationContext localContext = new AnnotationConfigApplicationContext(TestConfig.class);
        try {
            ValkeyExtension controller = localContext.getBean(ValkeyExtension.class);
            MockMvc localMockMvc = MockMvcBuilders.standaloneSetup(controller).build();

            assertTrue(controller.isRunning());
            localMockMvc.perform(get("/health"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.status").value("UP"));

            localContext.close(); // triggers stop() via the SmartLifecycle callback

            assertFalse(controller.isRunning());
            localMockMvc.perform(get("/health"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.status").value("DOWN"));
        } finally {
            if (localContext.isActive()) {
                localContext.close();
            }
        }
    }
}
