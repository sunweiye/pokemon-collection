#!/bin/sh

set -eu

echo "[dev] Initial Java compilation"
mvn clean compile -DskipTests

echo "[dev] Starting Spring Boot"
mvn spring-boot:run &
app_pid=$!

snapshot_java_sources() {
    find src/main/java src/main/resources -type f -exec sha256sum {} + | sort
}

watch_java() {
    last_snapshot=$(snapshot_java_sources)
    echo "[watcher] Polling Java sources and resources every 1 second"

    while :; do
        sleep 1
        current_snapshot=$(snapshot_java_sources)

        if [ "$current_snapshot" != "$last_snapshot" ]; then
            echo "[watcher] Java source or resource change detected"
            if mvn compile -DskipTests; then
                echo "[watcher] Compilation completed"
            else
                echo "[watcher] Compilation failed; waiting for the next Java change"
            fi
            last_snapshot=$current_snapshot
        fi
    done
}

watch_java &
watcher_pid=$!

shutdown() {
    kill "$watcher_pid" 2>/dev/null || true
    kill "$app_pid" 2>/dev/null || true
    wait "$watcher_pid" 2>/dev/null || true
    wait "$app_pid" 2>/dev/null || true
}

trap shutdown INT TERM EXIT
wait "$app_pid"
