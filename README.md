# opa-valkey-extension

A Workato **On-Prem Agent (OPA) extension** that retrieves data from a local Valkey instance.
Built as a Gradle project, following the conventions in
[workato/opa-extensions](https://github.com/workato/opa-extensions/tree/master) (the reference
SDK), with a Valkey backend and a JUnit-based test harness.

## Stack

| Component | Choice | Why |
|---|---|---|
| Server | [Valkey](https://valkey.io) 9.1 via Homebrew | Linux Foundation fork of Redis, RESP2/RESP3-protocol compatible; drop-in for the OSS Redis this pattern is normally built against. |
| Client library | [`io.valkey:valkey-java`](https://github.com/valkey-io/valkey-java) 5.5.0 | Valkey's own maintained fork of Jedis - identical API (`Jedis`, `JedisPool`, `JedisPoolConfig`), package `io.valkey.*`. |
| Extension framework | Spring MVC controller (`@Controller` + `@RequestMapping`), per the opa-extensions reference | This is exactly how the Workato Agent loads and dispatches to `ext/*.jar` extensions. |
| Build | Gradle 9.7.1 (wrapper committed) | Matches the reference SDK's `./gradlew jar` build command. |
| JDK | 17 (`openjdk@17` via Homebrew) | Required by the reference SDK's `build.gradle`. |

## Project layout

```
build.gradle                                   # see "Packaging strategy" below
settings.gradle
gradlew / gradlew.bat / gradle/wrapper/         # committed wrapper, Gradle 9.7.1
conf/config.yml                                 # sample Agent config.yml snippet
scripts/seed-sample-data.sh                     # seeds default sample data into Valkey
src/main/java/com/workato/onprem/valkey/
  ValkeyExtension.java                          # the extension controller
src/test/java/com/workato/onprem/valkey/
  ValkeyExtensionTest.java                      # test harness (MockMvc + real Valkey)
connector/valkey_extension_connector.rb          # cloud-side Workato Connector SDK companion
```

## 1. Install & start Valkey

```bash
brew install valkey
brew services start valkey       # runs on 127.0.0.1:6379, restarts on login
valkey-cli ping                  # => PONG
```

(Valkey conflicts with the `redis` formula in Homebrew - both install `redis-*` binaries -
so only one can be installed at a time. This project only needs one.)

## 2. Seed the default sample dataset

```bash
./scripts/seed-sample-data.sh
```

This seeds one key of each core Valkey data type so `ValkeyExtension` has real data to retrieve:

| Key | Type | Value |
|---|---|---|
| `user:1001:name` | string | `Ada Lovelace` |
| `user:1001:email` | string | `ada@example.com` |
| `user:1002` | hash | `{name, email, role}` |
| `recent:logins` | list | `[user:1001, user:1002, user:1003]` |
| `active:users` | set | `{user:1001, user:1002}` |
| `leaderboard` | sorted set | `user:1001=100, user:1002=87, user:1003=42` |

## 3. Build the extension jar and run the test harness

```bash
./gradlew build     # compiles, runs ValkeyExtensionTest against the local Valkey, packages the jar
```

`ValkeyExtensionTest` wires `ValkeyExtension` into a minimal Spring `AnnotationConfigApplicationContext`
(reproducing how the Agent injects `Environment` from `config.yml`) and drives it through `MockMvc`,
against the **real** Valkey instance from steps 1-2 - not a mock - covering every data type, the
`/health` check, the `/keys` glob search, and the bad-request path.

Output artifact: `build/libs/opa-valkey-extension-0.1.0.jar`.

## Packaging strategy (the point of comparing against opa-extensions)

The reference repo's `build.gradle` declares Spring, Jackson, servlet-api, commons-*, etc. as
`implementation` dependencies, and ships via a **plain** `./gradlew jar` (no shading). That works
for the reference sample because every one of those dependencies is already present on the
Workato Agent's own runtime classpath when it loads `ext/*.jar` - a thin jar is correct there.

This project adds exactly one dependency the Agent does *not* provide: the Valkey client. So the
packaging here is deliberately split:

- **`compileOnly`** - Spring, Jackson, servlet-api, jakarta.inject, slf4j-api. Compiled against,
  never bundled, since the Agent supplies these at runtime; shading a second copy in risks a
  classpath/version conflict with whatever the Agent ships.
- **`implementation` + shaded** - `io.valkey:valkey-java` (and its transitive deps: commons-pool2,
  gson, org.json). The Agent has no Valkey client on its classpath, so this **must** travel inside
  the jar or the extension fails at load time with `ClassNotFoundException`.

The [Shadow plugin](https://gradleup.com/shadow/) (`com.gradleup.shadow`) does the shading, and
`jar.finalizedBy(shadowJar)` makes sure that even running the reference SDK's exact documented
command, `./gradlew jar`, still leaves the correct deployable (fat) jar at
`build/libs/opa-valkey-extension-0.1.0.jar` - `compileOnly` deps stay out, `implementation` deps
get bundled in.

Verify what actually ended up in the jar:

```bash
jar tf build/libs/opa-valkey-extension-0.1.0.jar | grep -c io/valkey        # > 0 (bundled)
jar tf build/libs/opa-valkey-extension-0.1.0.jar | grep -E 'springframework|fasterxml|jakarta/servlet'  # empty (provided by Agent)
```

## 4. Deploy to a Workato Agent (reference)

1. Create an `ext` directory under the Agent install (e.g. `/opt/workato-agent/ext`).
2. Copy `build/libs/opa-valkey-extension-0.1.0.jar` into it.
3. Merge the snippet from [`conf/config.yml`](conf/config.yml) into the Agent's own `conf/config.yml`,
   adjusting `server.classpath` to point at your `ext` directory and `valkeyHost`/`valkeyPort` to
   your Valkey instance.
4. Restart the Agent. It will expose:
   - `GET  /ext/valkey/health` - connectivity check
   - `POST /ext/valkey/get` `{"key": "user:1001:name"}` - retrieve any key, type auto-detected
   - `POST /ext/valkey/set` `{"key": "user:1001:name", "value": "Ada Lovelace", "ttlSeconds": 60}` - create/overwrite a key, optional TTL
   - `POST /ext/valkey/set-batch` `{"entries": [{"key": "...", "value": "...", "ttlSeconds": 60}, ...]}` - create/overwrite many keys in one call
   - `GET  /ext/valkey/keys?pattern=user:*` - list matching key names
   - `GET  /ext/valkey/search?pattern=user:*` - search matching keys and return each one's value
   - `POST /ext/valkey/semantic-search` `{"index": "idx:docs", "vectorField": "embedding", "vector": [...], "topK": 5}` - vector/KNN similarity search against a [Valkey Search](https://valkey.io/topics/search/) index (requires the valkey-search module and a pre-existing `FT.CREATE`'d vector index)

## Endpoints

| Method | Path | Body / Params | Returns |
|---|---|---|---|
| GET | `/health` | - | `{"status": "UP", "pong": "PONG"}` |
| POST | `/get` | `{"key": "<key>"}` | `{"key", "type", "found", "value"}` (value shape depends on type: string, hash → object, list → array, set → array, zset → `[{member, score}, ...]`) |
| POST | `/set` | `{"key": "<key>", "value": "<value>", "ttlSeconds": <optional>}` | `{"key", "set": true, "ttlSeconds"}` (`ttlSeconds` present only when supplied) |
| POST | `/set-batch` | `{"entries": [{"key", "value", "ttlSeconds": <optional>}, ...]}` | `{"keys": [...], "count", "set": true}` (one Valkey pipeline round trip for the whole batch) |
| GET | `/keys` | `?pattern=<glob>` (default `*`) | `{"pattern", "keys": [...], "count"}` |
| GET | `/search` | `?pattern=<glob>` (default `*`) | `{"pattern", "entries": [{"key", "type", "found", "value"}, ...], "count"}` |
| POST | `/semantic-search` | `{"index", "vectorField", "vector": [...], "topK": <optional, default 10>, "returnFields": [...]}` | `{"index", "matches": [{"key", "score", "fields"}, ...], "count"}` (`score` is vector distance - lower is closer) |

A missing/blank `key` on `POST /get`/`POST /set`, a missing `value` on `POST /set`, a
non-positive `ttlSeconds`, or an empty/malformed `entries` array on `POST /set-batch` all return
`400 {"error": "..."}` rather than a generic 500, via the controller's `@ExceptionHandler`.

### Semantic (vector) search

`POST /semantic-search` runs a K-nearest-neighbors query against an index created by the
[Valkey Search](https://valkey.io/topics/search/) module (`valkey-search`, bundled with Valkey
8.1+) - the piece that gives Valkey semantic-search capability, analogous to Redis's
RediSearch/vector sets. It queries an *existing* index only; this extension does not create one.
Before calling it, create the index and populate the vector field yourself (e.g. via `FT.CREATE`
and `HSET`), then query with a client-side embedding:

```json
{
  "index": "idx:docs",
  "vectorField": "embedding",
  "vector": [0.012, -0.34, 0.98, ...],
  "topK": 5,
  "returnFields": ["title", "text"]
}
```

`vector` is packed client-side into the little-endian FLOAT32 blob the module expects, so the
indexed vector field's `TYPE` must be `FLOAT32` (the default). If the Valkey server doesn't have
`valkey-search` loaded, or `index` doesn't exist, Valkey returns an error which this endpoint
surfaces as a `400`.

## Lifecycle (start/stop)

The [opa-extensions](https://github.com/workato/opa-extensions) reference SDK doesn't define its
own lifecycle interface - an extension is just a Spring `@Controller` bean living inside the
Agent's own application context, so the Agent starts/stops it exactly like any other Spring bean.
`ValkeyExtension` opts into that lifecycle by implementing Spring's `SmartLifecycle`, which the
Agent's context invokes automatically on refresh/close - no explicit wiring needed beyond
registering the bean:

- **`start()`** - runs once, right after the Agent constructs this bean and injects `Environment`
  (Agent startup, or a hot-reload of `ext/*.jar`). Opens the `JedisPool` (used by `/get`, `/set`,
  `/keys`, `/search`) and a `JedisPooled` search client (used by `/semantic-search`, since Valkey
  Search commands live on `UnifiedJedis` rather than the plain `Jedis` connection) eagerly, so a
  bad `valkeyHost`/`valkeyPort` fails fast in the Agent's startup log instead of silently on the
  first request.
- **`stop()`** - runs once, when the Agent tears this bean down (shutdown or `ext/*.jar` reload).
  Closes both clients so their pooled connections and idle-eviction threads don't leak past the
  extension's lifetime.
- **`isRunning()`** - reports `true` only while both clients are open (between `start()` and `stop()`).
- **`isAutoStartup()`** - `true`, so `start()` fires automatically on context refresh, same as the
  `@PostConstruct` hook this replaced.

Both `start()`/`stop()` are exercised by
`ValkeyExtensionTest#lifecycleHooksOpenAndClosePoolOnStartAndStop`, which opens/closes a
short-lived Spring context and checks `/health` reports `UP` immediately after `start()` and
cleanly degrades to `DOWN` (rather than throwing) after `stop()`.

## Cloud-side Workato connector

[`connector/valkey_extension_connector.rb`](connector/valkey_extension_connector.rb) is the
Workato Connector SDK companion to this extension - a connector to build recipes against once
`ValkeyExtension` is deployed to an Agent. It's a local Ruby SDK source file (for the open-source
[`workato-connector-sdk`](https://github.com/workato/workato-connector-sdk) gem, or to paste into
the Workato Connector SDK editor) - it has not been created/saved in any Workato workspace.

- **Connection**: `secure_tunnel: true` gives the connection a **Connection type** dropdown in
  the Workato UI, so setting one up means picking the on-prem group whose Agent has this
  extension deployed, plus an `extension_name` field (defaults to `valkey`, matching
  `conf/config.yml`) that resolves the mount path. `test` hits `/health`.
- **Actions**: `health_check` (`GET /health`), `get_key` (`POST /get`), `set_key`
  (`POST /set`), `set_keys` (`POST /set-batch`), `list_keys` (`GET /keys?pattern=...`),
  `search_keys` (`GET /search?pattern=...`) - one per `ValkeyExtension` endpoint.

Local test loop, once `gem install workato-connector-sdk`:

```bash
workato exec actions.health_check.execute \
  --connector=connector/valkey_extension_connector.rb \
  --settings=settings.yaml   # untracked; { valkey_extension: { extension_name: valkey } }
```
