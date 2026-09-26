#!/usr/bin/env bash
# Submits a burst of jobs to the compose stack and shows how the workers shared them.
# Usage: docker compose up -d --build --scale worker=3 && ./scripts/load-demo.sh [jobs]
set -euo pipefail

API=${API:-http://localhost:8080}
JOBS=${1:-600}

until [ "$(curl -s -o /dev/null -w '%{http_code}' "$API/actuator/health")" = "200" ]; do sleep 2; done

echo "Submitting $JOBS email.send jobs (100 ms simulated latency each)..."
start=$(date +%s)
for i in $(seq 1 "$JOBS"); do
  curl -s -o /dev/null -X POST "$API/api/jobs" -H 'Content-Type: application/json' \
    -d "{\"type\":\"email.send\",\"payload\":{\"to\":\"user$i@example.com\",\"latencyMs\":100}}" &
  (( i % 50 == 0 )) && wait
done
wait

echo "Waiting for the queue to drain..."
until [ "$(curl -s "$API/api/queue/stats" | grep -o '"QUEUED":[0-9]*' | cut -d: -f2)" = "0" ] \
   && [ "$(curl -s "$API/api/queue/stats" | grep -o '"RUNNING":[0-9]*' | cut -d: -f2)" = "0" ]; do
  sleep 1
done
echo "Drained in $(( $(date +%s) - start ))s. Stats: $(curl -s "$API/api/queue/stats")"

echo "Jobs completed per worker:"
docker compose exec -T postgres psql -U postgres -d jobs_db -At -F ' | ' -c \
  "select completed_by, count(*) from jobs where status = 'SUCCEEDED' group by completed_by order by 2 desc"
echo "Jobs executed more than once: $(docker compose exec -T postgres psql -U postgres -d jobs_db -At -c \
  "select count(*) from jobs where status = 'SUCCEEDED' and attempts > 1")"
