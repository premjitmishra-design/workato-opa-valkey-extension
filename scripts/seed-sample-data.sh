#!/usr/bin/env bash
# Seeds a small default dataset into the local Valkey instance, covering each of Valkey's
# core data types (string, hash, list, set, sorted set) so ValkeyExtension has something
# real to retrieve. Safe to re-run - it flushes the current DB first.
set -euo pipefail

CLI="${VALKEY_CLI:-valkey-cli}"
HOST="${VALKEY_HOST:-127.0.0.1}"
PORT="${VALKEY_PORT:-6379}"

run() { "$CLI" -h "$HOST" -p "$PORT" "$@"; }

run FLUSHDB

# string
run SET user:1001:name "Ada Lovelace"
run SET user:1001:email "ada@example.com"

# hash
run HSET user:1002 name "Grace Hopper" email "grace@example.com" role "Rear Admiral"

# list
run RPUSH recent:logins user:1001 user:1002 user:1003

# set
run SADD active:users user:1001 user:1002

# sorted set
run ZADD leaderboard 100 user:1001 87 user:1002 42 user:1003

echo "Seeded sample data into valkey at ${HOST}:${PORT}:"
run KEYS '*'
