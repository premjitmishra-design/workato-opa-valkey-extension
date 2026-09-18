package com.workato.onprem.valkey;

import io.valkey.Jedis;
import io.valkey.JedisPool;
import io.valkey.JedisPoolConfig;
import io.valkey.JedisPooled;
import io.valkey.Pipeline;
import io.valkey.params.ScanParams;
import io.valkey.resps.ScanResult;
import io.valkey.resps.Tuple;
import io.valkey.search.Document;
import io.valkey.search.Query;
import io.valkey.search.RediSearchUtil;
import io.valkey.search.SearchResult;
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

    // Separate pooled client for Valkey Search (https://valkey.io/topics/search/) commands.
    // The plain Jedis/JedisPool pair above only implements the core data-type commands used by
    // get/set/keys/search; FT.SEARCH and friends live on UnifiedJedis (JedisPooled extends it),
    // so semantic-search gets its own client rather than forcing every /get,/set caller onto it.
    private volatile JedisPooled searchClient;

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
        searchClient = new JedisPooled(host, port);
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
        JedisPooled localSearchClient = searchClient;
        searchClient = null;
        if (localSearchClient != null) {
            localSearchClient.close();
        }
    }

    /** Reports "running" only while a connection pool is actually open (i.e. between start() and stop()). */
    @Override
    public boolean isRunning() {
        boolean running = pool != null && searchClient != null;
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

    private JedisPooled searchClient() {
        JedisPooled local = searchClient;
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
     * POST /ext/valkey/set - create or overwrite a single cache entry.
     * Request body: {"key": "user:1001:name", "value": "Ada Lovelace", "ttlSeconds": 60}
     * {@code ttlSeconds} is optional; omit it (or send 0/blank) for no expiry.
     */
    @RequestMapping(value = "/set", method = RequestMethod.POST)
    public @ResponseBody Map<String, Object> set(@RequestBody Map<String, Object> body) {
        String key = body == null ? null : (String) body.get("key");
        Object rawValue = body == null ? null : body.get("value");
        LOG.info("POST /set key={}", key);
        if (key == null || key.isEmpty()) {
            throw new IllegalArgumentException("`key` is required");
        }
        if (rawValue == null) {
            throw new IllegalArgumentException("`value` is required");
        }
        Integer ttlSeconds = parseTtlSeconds(body.get("ttlSeconds"));

        try (Jedis jedis = pool().getResource()) {
            String value = String.valueOf(rawValue);
            if (ttlSeconds != null) {
                jedis.setex(key, ttlSeconds, value);
            } else {
                jedis.set(key, value);
            }
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("key", key);
        result.put("set", true);
        if (ttlSeconds != null) {
            result.put("ttlSeconds", ttlSeconds);
        }
        return result;
    }

    /**
     * POST /ext/valkey/set-batch - create or overwrite multiple cache entries in one call.
     * Request body: {"entries": [{"key": "...", "value": "...", "ttlSeconds": 60}, ...]}
     * All entries are validated up front (so a bad entry fails the whole request before anything
     * is written), then sent to Valkey in a single pipeline - one network round trip instead of
     * one per entry. Note a pipeline is not a transaction: if Valkey itself errors partway
     * through, earlier entries in the batch may already be written.
     */
    @RequestMapping(value = "/set-batch", method = RequestMethod.POST)
    public @ResponseBody Map<String, Object> setBatch(@RequestBody Map<String, Object> body) {
        Object rawEntries = body == null ? null : body.get("entries");
        LOG.info("POST /set-batch entries={}", rawEntries instanceof List ? ((List<?>) rawEntries).size() : rawEntries);
        if (!(rawEntries instanceof List) || ((List<?>) rawEntries).isEmpty()) {
            throw new IllegalArgumentException("`entries` must be a non-empty array");
        }

        List<Map.Entry<String, String>> toWrite = new java.util.ArrayList<>();
        List<Integer> ttlSecondsByEntry = new java.util.ArrayList<>();
        for (Object rawEntry : (List<?>) rawEntries) {
            if (!(rawEntry instanceof Map)) {
                throw new IllegalArgumentException("each entry in `entries` must be an object");
            }
            Map<?, ?> entry = (Map<?, ?>) rawEntry;
            Object rawKey = entry.get("key");
            Object rawValue = entry.get("value");
            if (!(rawKey instanceof String) || ((String) rawKey).isEmpty()) {
                throw new IllegalArgumentException("each entry requires a non-empty `key`");
            }
            if (rawValue == null) {
                throw new IllegalArgumentException("entry `" + rawKey + "` requires a `value`");
            }
            toWrite.add(Map.entry((String) rawKey, String.valueOf(rawValue)));
            ttlSecondsByEntry.add(parseTtlSeconds(entry.get("ttlSeconds")));
        }

        try (Jedis jedis = pool().getResource()) {
            Pipeline pipeline = jedis.pipelined();
            for (int i = 0; i < toWrite.size(); i++) {
                Map.Entry<String, String> kv = toWrite.get(i);
                Integer ttlSeconds = ttlSecondsByEntry.get(i);
                if (ttlSeconds != null) {
                    pipeline.setex(kv.getKey(), ttlSeconds, kv.getValue());
                } else {
                    pipeline.set(kv.getKey(), kv.getValue());
                }
            }
            pipeline.sync();
        }

        List<String> keys = new java.util.ArrayList<>();
        for (Map.Entry<String, String> kv : toWrite) {
            keys.add(kv.getKey());
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("keys", keys);
        result.put("count", keys.size());
        result.put("set", true);
        return result;
    }

    /** Parses the optional `ttlSeconds` request field, rejecting anything not a positive integer. */
    private Integer parseTtlSeconds(Object rawTtl) {
        if (rawTtl == null || "".equals(rawTtl)) {
            return null;
        }
        int ttlSeconds;
        try {
            ttlSeconds = rawTtl instanceof Number
                    ? ((Number) rawTtl).intValue()
                    : Integer.parseInt(String.valueOf(rawTtl).trim());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("`ttlSeconds` must be a positive integer");
        }
        if (ttlSeconds <= 0) {
            throw new IllegalArgumentException("`ttlSeconds` must be a positive integer");
        }
        return ttlSeconds;
    }

    /**
     * GET /ext/valkey/keys?pattern=user:* - list keys matching a glob pattern
     * (uses SCAN rather than KEYS so it is safe against a large keyspace).
     */
    @RequestMapping(value = "/keys", method = RequestMethod.GET)
    public @ResponseBody Map<String, Object> keys(@RequestParam(value = "pattern", defaultValue = "*") String pattern) {
        LOG.info("GET /keys pattern={}", pattern);
        try (Jedis jedis = pool().getResource()) {
            Set<String> matched = scanKeys(jedis, pattern);
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("pattern", pattern);
            result.put("keys", matched);
            result.put("count", matched.size());
            return result;
        }
    }

    /**
     * GET /ext/valkey/search?pattern=user:* - search keys matching a glob pattern and return
     * every matching entry's value (auto-detected type, same shape as /get), not just key names.
     * Uses the same cursor-driven SCAN as /keys so it stays safe against a large keyspace.
     */
    @RequestMapping(value = "/search", method = RequestMethod.GET)
    public @ResponseBody Map<String, Object> search(@RequestParam(value = "pattern", defaultValue = "*") String pattern) {
        LOG.info("GET /search pattern={}", pattern);
        try (Jedis jedis = pool().getResource()) {
            Set<String> matched = scanKeys(jedis, pattern);
            List<Map<String, Object>> entries = new java.util.ArrayList<>();
            for (String key : matched) {
                entries.add(readKey(jedis, key));
            }
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("pattern", pattern);
            result.put("entries", entries);
            result.put("count", entries.size());
            return result;
        }
    }

    /** Cursor-driven SCAN over the keyspace, matching {@code pattern} - shared by /keys and /search. */
    private Set<String> scanKeys(Jedis jedis, String pattern) {
        Set<String> matched = new java.util.LinkedHashSet<>();
        ScanParams params = new ScanParams().match(pattern).count(1000);
        String cursor = ScanParams.SCAN_POINTER_START;
        do {
            ScanResult<String> scanResult = jedis.scan(cursor, params);
            matched.addAll(scanResult.getResult());
            cursor = scanResult.getCursor();
        } while (!ScanParams.SCAN_POINTER_START.equals(cursor));
        return matched;
    }

    /**
     * POST /ext/valkey/semantic-search - vector similarity (KNN) search against a Valkey Search
     * (https://valkey.io/topics/search/) index, for semantic-search use cases where {@code vector}
     * is a query embedding and the closest-matching documents are returned by distance.
     * Request body: {"index": "idx:docs", "vectorField": "embedding", "vector": [0.01, -0.23, ...],
     * "topK": 5, "returnFields": ["title", "text"]}
     * {@code topK} defaults to 10; {@code returnFields} is optional (omit for every stored field).
     * Requires the target Valkey server to have the valkey-search module loaded and an index
     * already created over {@code vectorField} (e.g. via {@code FT.CREATE}) - this endpoint only
     * queries an existing index, it does not create one.
     */
    @RequestMapping(value = "/semantic-search", method = RequestMethod.POST)
    public @ResponseBody Map<String, Object> semanticSearch(@RequestBody Map<String, Object> body) {
        String index = body == null ? null : (String) body.get("index");
        String vectorField = body == null ? null : (String) body.get("vectorField");
        Object rawVector = body == null ? null : body.get("vector");
        LOG.info("POST /semantic-search index={} vectorField={}", index, vectorField);

        if (index == null || index.isEmpty()) {
            throw new IllegalArgumentException("`index` is required");
        }
        if (vectorField == null || vectorField.isEmpty()) {
            throw new IllegalArgumentException("`vectorField` is required");
        }
        if (!(rawVector instanceof List) || ((List<?>) rawVector).isEmpty()) {
            throw new IllegalArgumentException("`vector` must be a non-empty array of numbers");
        }
        int topK = parseTopK(body.get("topK"));
        byte[] queryVector = toFloat32Blob((List<?>) rawVector);

        // AS-aliased KNN score field, sorted ascending (smaller distance = closer match); dialect 2
        // is required for the `$param` KNN query syntax below (dialect 1 rejects it).
        String scoreField = "__vector_score";
        Query query = new Query("*=>[KNN " + topK + " @" + vectorField + " $BLOB AS " + scoreField + "]")
                .addParam("BLOB", queryVector)
                .setSortBy(scoreField, true)
                .dialect(2)
                .limit(0, topK);

        Object rawReturnFields = body.get("returnFields");
        if (rawReturnFields instanceof List && !((List<?>) rawReturnFields).isEmpty()) {
            List<String> fields = new java.util.ArrayList<>();
            for (Object field : (List<?>) rawReturnFields) {
                fields.add(String.valueOf(field));
            }
            fields.add(scoreField);
            query.returnFields(fields.toArray(new String[0]));
        }

        SearchResult searchResult = searchClient().ftSearch(index, query);

        List<Map<String, Object>> matches = new java.util.ArrayList<>();
        for (Document doc : searchResult.getDocuments()) {
            Map<String, Object> match = new LinkedHashMap<>();
            match.put("key", doc.getId());
            match.put("score", doc.hasProperty(scoreField) ? doc.get(scoreField) : doc.getScore());
            Map<String, Object> fields = new LinkedHashMap<>();
            for (Map.Entry<String, Object> field : doc.getProperties()) {
                if (!scoreField.equals(field.getKey())) {
                    fields.put(field.getKey(), field.getValue());
                }
            }
            match.put("fields", fields);
            matches.add(match);
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("index", index);
        result.put("matches", matches);
        result.put("count", matches.size());
        return result;
    }

    /** Parses the optional `topK` request field, defaulting to 10; rejects anything not a positive integer. */
    private int parseTopK(Object rawTopK) {
        if (rawTopK == null || "".equals(rawTopK)) {
            return 10;
        }
        int topK;
        try {
            topK = rawTopK instanceof Number
                    ? ((Number) rawTopK).intValue()
                    : Integer.parseInt(String.valueOf(rawTopK).trim());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("`topK` must be a positive integer");
        }
        if (topK <= 0) {
            throw new IllegalArgumentException("`topK` must be a positive integer");
        }
        return topK;
    }

    /**
     * Packs a query embedding into the little-endian FLOAT32 blob Valkey Search expects for a
     * vector field's KNN parameter (default vector {@code TYPE} is FLOAT32 - see
     * {@code VectorField}/{@code addFlatVectorField}), via the client library's own
     * {@link RediSearchUtil#toByteArray(float[])}.
     */
    private byte[] toFloat32Blob(List<?> vector) {
        float[] components = new float[vector.size()];
        for (int i = 0; i < components.length; i++) {
            Object component = vector.get(i);
            if (!(component instanceof Number)) {
                throw new IllegalArgumentException("`vector` must contain only numbers");
            }
            components[i] = ((Number) component).floatValue();
        }
        return RediSearchUtil.toByteArray(components);
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
