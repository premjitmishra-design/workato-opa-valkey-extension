package com.workato.onprem.valkey;

import io.valkey.Jedis;
import io.valkey.JedisPool;
import io.valkey.JedisPoolConfig;
import io.valkey.params.ScanParams;
import io.valkey.resps.ScanResult;
import io.valkey.resps.Tuple;
import jakarta.inject.Inject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;
import org.springframework.core.env.Environment;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.bind.annotation.ResponseStatus;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Workato On-Prem Agent (OPA) extension that retrieves data from a local Valkey instance.
 * Follows the pattern documented in workato/opa-extensions: a plain
 * Spring REST controller, configuration pulled from the Agent's {@code conf/config.yml} via the
 * injected {@link Environment}, mounted by the Agent at {@code /ext/<endpointName>/<resource>}.
 *
 * <p>The reference SDK does not define its own lifecycle interface - an extension is just a
 * Spring {@code @Controller} bean living in the Agent's own application context, so the Agent
 * starts/stops it the way Spring starts/stops any bean. This controller opts into that via
 * {@link SmartLifecycle}: {@link #start()} and {@link #stop()} are invoked automatically by the
 * Agent's Spring context on refresh/close, so the Valkey connection pool is opened once when the
 * Agent loads this jar and cleanly closed when the Agent shuts down or reloads {@code ext/*.jar}
 * - instead of being lazily created on first request and never released.
 *
 * Example config.yml entry (see conf/config.yml in this project):
 *
 * <pre>
 * extensions:
 *   valkey:
 *     controllerClass: com.workato.onprem.valkey.ValkeyExtension
 *     valkeyHost: 127.0.0.1
 *     valkeyPort: 6379
 * </pre>
 */
@Controller
public class ValkeyExtension implements SmartLifecycle {

    private static final Logger LOG = LoggerFactory.getLogger(ValkeyExtension.class);

    @Inject
    private Environment env;

    private volatile JedisPool pool;

    /**
     * Lifecycle start hook. Invoked once by the Agent's Spring context right after this bean is
     * constructed and {@code env} is injected (Agent startup, or hot-reload of this ext/*.jar).
     * Opens the connection pool eagerly so a bad {@code valkeyHost}/{@code valkeyPort} fails
     * fast here, in the Agent's startup log, rather than silently on the first client request.
     */
    @Override
    public void start() {
        String host = env.getProperty("valkeyHost", "127.0.0.1");
        int port = Integer.parseInt(env.getProperty("valkeyPort", "6379"));
        LOG.info("start() - opening JedisPool to {}:{}", host, port);
        pool = new JedisPool(new JedisPoolConfig(), host, port);
    }

    /**
     * Lifecycle stop hook. Invoked once by the Agent's Spring context when this bean is torn
     * down (Agent shutdown, or ext/*.jar reload). Closes the pool so its pooled connections and
     * background idle-eviction thread don't leak past the extension's lifetime.
     */
    @Override
    public void stop() {
        LOG.info("stop() - closing JedisPool");
        JedisPool local = pool;
        pool = null;
        if (local != null) {
            local.close();
        }
    }

    /** Reports "running" only while a connection pool is actually open (i.e. between start() and stop()). */
    @Override
    public boolean isRunning() {
        boolean running = pool != null;
        LOG.info("isRunning() -> {}", running);
        return running;
    }

    /** Start automatically on Agent/context startup, same as the {@code @PostConstruct} it replaces. */
    @Override
    public boolean isAutoStartup() {
        LOG.info("isAutoStartup() -> true");
        return true;
    }

    private JedisPool pool() {
        JedisPool local = pool;
        if (local == null) {
            throw new IllegalStateException("ValkeyExtension.start() has not run yet - no connection pool");
        }
        return local;
    }

    /**
     * GET /ext/valkey/health - connectivity check against the configured Valkey instance.
     */
    @RequestMapping(value = "/health", method = RequestMethod.GET)
    public @ResponseBody Map<String, Object> health() {
        LOG.info("GET /health");
        Map<String, Object> result = new LinkedHashMap<>();
        try (Jedis jedis = pool().getResource()) {
            String pong = jedis.ping();
            result.put("status", "PONG".equals(pong) ? "UP" : "DOWN");
            result.put("pong", pong);
        } catch (Exception e) {
            result.put("status", "DOWN");
            result.put("error", e.getMessage());
        }
        return result;
    }

    /**
     * POST /ext/valkey/get - retrieve a single key, auto-detecting its data type.
     * Request body: {"key": "user:1001:name"}
     */
    @RequestMapping(value = "/get", method = RequestMethod.POST)
    public @ResponseBody Map<String, Object> get(@RequestBody Map<String, Object> body) {
        String key = body == null ? null : (String) body.get("key");
        LOG.info("POST /get key={}", key);
        if (key == null || key.isEmpty()) {
            throw new IllegalArgumentException("`key` is required");
        }
        try (Jedis jedis = pool().getResource()) {
            return readKey(jedis, key);
        }
    }

    /**
     * GET /ext/valkey/keys?pattern=user:* - list keys matching a glob pattern
     * (uses SCAN rather than KEYS so it is safe against a large keyspace).
     */
    @RequestMapping(value = "/keys", method = RequestMethod.GET)
    public @ResponseBody Map<String, Object> keys(@RequestParam(value = "pattern", defaultValue = "*") String pattern) {
        LOG.info("GET /keys pattern={}", pattern);
        try (Jedis jedis = pool().getResource()) {
            Set<String> matched = new java.util.LinkedHashSet<>();
            ScanParams params = new ScanParams().match(pattern).count(1000);
            String cursor = ScanParams.SCAN_POINTER_START;
            do {
                ScanResult<String> scanResult = jedis.scan(cursor, params);
                matched.addAll(scanResult.getResult());
                cursor = scanResult.getCursor();
            } while (!ScanParams.SCAN_POINTER_START.equals(cursor));

            Map<String, Object> result = new LinkedHashMap<>();
            result.put("pattern", pattern);
            result.put("keys", matched);
            result.put("count", matched.size());
            return result;
        }
    }

    private Map<String, Object> readKey(Jedis jedis, String key) {
        String type = jedis.type(key); // "none" | "string" | "hash" | "list" | "set" | "zset" (Valkey data type)
        Object value;
        switch (type) {
            case "string":
                value = jedis.get(key);
                break;
            case "hash":
                value = jedis.hgetAll(key);
                break;
            case "list":
                value = jedis.lrange(key, 0, -1);
                break;
            case "set":
                value = jedis.smembers(key);
                break;
            case "zset":
                // Map Tuple -> plain {member, score} entries for predictable JSON serialization
                // (avoids depending on the client library's internal Tuple bean shape).
                List<Map<String, Object>> members = new java.util.ArrayList<>();
                for (Tuple t : jedis.zrangeWithScores(key, 0, -1)) {
                    Map<String, Object> entry = new LinkedHashMap<>();
                    entry.put("member", t.getElement());
                    entry.put("score", t.getScore());
                    members.add(entry);
                }
                value = members;
                break;
            case "none":
            default:
                value = null;
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("key", key);
        result.put("type", type);
        result.put("found", !"none".equals(type));
        result.put("value", value);
        return result;
    }

    /** Turns a bad request (e.g. missing `key`) into a clean 400 instead of a generic 500. */
    @ExceptionHandler(IllegalArgumentException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public @ResponseBody Map<String, Object> handleBadRequest(IllegalArgumentException e) {
        LOG.info("bad request: {}", e.getMessage());
        return Collections.singletonMap("error", e.getMessage());
    }
}
