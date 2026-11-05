# Load test (quickstart Q9)

```bash
docker compose -f deploy/local/compose.yaml up -d
(cd backend && ./mvnw spring-boot:run -Dspring-boot.run.profiles=local)
# demo-client is seeded with 1000/min; raise it for load tests:
psql postgresql://pnh:pnh@localhost:5432/pnh -c "UPDATE pnh_clients SET rate_limit_per_min = 100000 WHERE client_id='demo-client'"
k6 run deploy/local/k6/send-load.js -e RATE=500 -e DURATION=5m
```

The script fails (non-zero exit) when a threshold is missed:

| Threshold | Requirement |
|-----------|-------------|
| `http_req_duration{name:send}` p95 < 1000 ms | SC-001 acknowledgement latency |
| `http_req_failed` rate < 0.1 % | SC-005 sustained 500 req/s without errors |
| `dropped_iterations` < 1 % of the rate | the arrival rate was actually sustained |

## Partition balance (SC-014)

The mix is 80 % EMAIL / 15 % SMS / 5 % PUSH over 10 000 distinct `user_id`s. Results are keyed by `user_id`, so
per-partition load must stay even regardless of the channel skew:

```bash
docker exec pnh-local-kafka-1 /opt/kafka/bin/kafka-run-class.sh kafka.tools.GetOffsetShell \
  --bootstrap-server kafka:29092 --topic pnh.notification.result
```

Every partition's end offset should be within 2× of the mean. Keying by channel would put 80 % on one partition.

For the ingest path, publish the same mix with `kcat` (signature check is off in the `local` profile) and inspect
lag with `kafka-consumer-groups.sh --describe --group pnh`.
