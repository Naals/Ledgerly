#!/usr/bin/env python3
"""Verify packaged payment-service lag metrics using disposable Kafka/PostgreSQL containers."""

import argparse
import math
import os
from pathlib import Path
import re
import socket
import subprocess
import sys
import tempfile
import time
import urllib.error
import urllib.request
import uuid


ROOT = Path(__file__).resolve().parents[1]


def run(*args, timeout=60, input=None):
    return subprocess.run(args, input=input, text=True, capture_output=True,
                          timeout=timeout, check=True).stdout.strip()


def free_port():
    with socket.socket() as sock:
        sock.bind(("127.0.0.1", 0))
        return sock.getsockname()[1]


def eventually(description, check, timeout=90):
    deadline = time.monotonic() + timeout
    last_error = None
    while time.monotonic() < deadline:
        try:
            if check():
                print(f"PASS: {description}", flush=True)
                return
        except (OSError, ValueError, subprocess.SubprocessError) as error:
            last_error = error
        time.sleep(0.5)
    raise RuntimeError(f"Timed out: {description}; last error: {last_error}")


def metric(body, name, **labels):
    for line in body.splitlines():
        match = re.fullmatch(r"([a-zA-Z_:][a-zA-Z0-9_:]*)(?:\{(.*)\})?\s+(\S+)", line)
        if match and match[1] == name:
            actual = dict(re.findall(r'(\w+)="([^"\\]*)"', match[2] or ""))
            if all(actual.get(key) == value for key, value in labels.items()):
                return float(match[3])
    raise ValueError(f"Missing metric {name} {labels}")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--jar", type=Path, default=ROOT / "payment-service/target/payment-service-0.1.0-SNAPSHOT.jar")
    args = parser.parse_args()
    if not args.jar.is_file():
        parser.error("Build the payment-service jar first: mvn -pl payment-service -am package -DskipTests")

    suffix = uuid.uuid4().hex[:12]
    kafka = f"ledgerly-lag-kafka-{suffix}"
    postgres = f"ledgerly-lag-postgres-{suffix}"
    group = f"lag-smoke-{suffix}"
    topics = [f"lag-smoke-transactions-{suffix}", f"lag-smoke-holds-{suffix}"]
    kafka_port, app_port = free_port(), free_port()
    created = []
    app = None
    paused = False
    log_path = None
    cleanup_errors = []

    def cleanup(*command):
        try:
            run(*command, timeout=30)
        except (OSError, subprocess.SubprocessError) as error:
            cleanup_errors.append(f"{' '.join(command)}: {error}")

    def kafka_cli(command, *options, input=None):
        return run("docker", "exec", "-i", kafka, f"/opt/kafka/bin/{command}.sh",
                   "--bootstrap-server", "localhost:19092", *options, input=input, timeout=30)

    def scrape():
        if app.poll() is not None:
            raise RuntimeError(f"payment-service exited with code {app.returncode}")
        with urllib.request.urlopen(f"http://127.0.0.1:{app_port}/actuator/prometheus", timeout=3) as response:
            return response.read().decode()

    def has_lag(expected):
        body = scrape()
        return metric(body, "payments_kafka_lag_sample_success", group=group) == 1 and all(
            metric(body, "payments_kafka_consumer_lag", group=group, topic=topic) == expected
            for topic in topics)

    try:
        run("docker", "info", "--format", "{{.ServerVersion}}", timeout=15)
        created.append(postgres)
        run("docker", "run", "-d", "--name", postgres, "-p", "127.0.0.1::5432",
            "-e", "POSTGRES_DB=payments", "-e", "POSTGRES_USER=payments",
            "-e", "POSTGRES_PASSWORD=lag-smoke", "postgres:16-alpine", timeout=180)
        pg_port = run("docker", "port", postgres, "5432/tcp").rsplit(":", 1)[1]
        env = {
            "KAFKA_NODE_ID": "1", "KAFKA_PROCESS_ROLES": "broker,controller",
            "KAFKA_LISTENERS": "INTERNAL://:19092,EXTERNAL://:9092,CONTROLLER://:9093",
            "KAFKA_ADVERTISED_LISTENERS": f"INTERNAL://localhost:19092,EXTERNAL://localhost:{kafka_port}",
            "KAFKA_LISTENER_SECURITY_PROTOCOL_MAP": "INTERNAL:PLAINTEXT,EXTERNAL:PLAINTEXT,CONTROLLER:PLAINTEXT",
            "KAFKA_INTER_BROKER_LISTENER_NAME": "INTERNAL",
            "KAFKA_CONTROLLER_LISTENER_NAMES": "CONTROLLER",
            "KAFKA_CONTROLLER_QUORUM_VOTERS": "1@localhost:9093",
            "KAFKA_OFFSETS_TOPIC_REPLICATION_FACTOR": "1",
            "KAFKA_TRANSACTION_STATE_LOG_REPLICATION_FACTOR": "1",
            "KAFKA_TRANSACTION_STATE_LOG_MIN_ISR": "1",
            "KAFKA_GROUP_INITIAL_REBALANCE_DELAY_MS": "0",
        }
        env_args = [arg for key, value in env.items() for arg in ("-e", f"{key}={value}")]
        created.append(kafka)
        run("docker", "run", "-d", "--name", kafka, "-p", f"127.0.0.1:{kafka_port}:9092",
            *env_args, "apache/kafka:3.9.1", timeout=180)
        eventually("PostgreSQL ready", lambda: run("docker", "exec", postgres, "pg_isready", "-U", "payments", timeout=5))
        eventually("Kafka ready", lambda: kafka_cli("kafka-broker-api-versions"))
        for topic in topics:
            kafka_cli("kafka-topics", "--create", "--topic", topic, "--partitions", "3", "--replication-factor", "1")

        with tempfile.NamedTemporaryFile(prefix="ledgerly-lag-smoke-", suffix=".log", delete=False) as log:
            log_path = Path(log.name)
            # Exclude inherited Spring profiles/configuration and JVM option injection.
            app_env = {key: value for key, value in os.environ.items()
                       if key in {"PATH", "JAVA_HOME", "HOME", "LANG", "LC_ALL", "TMPDIR"}}
            app = subprocess.Popen([
                "java", "-jar", str(args.jar.resolve()), "--server.address=127.0.0.1", f"--server.port={app_port}",
                "--spring.config.location=classpath:/application.yaml",
                f"--spring.datasource.url=jdbc:postgresql://localhost:{pg_port}/payments",
                "--spring.datasource.username=payments", "--spring.datasource.password=lag-smoke",
                f"--spring.kafka.bootstrap-servers=localhost:{kafka_port}", f"--spring.kafka.consumer.group-id={group}",
                "--spring.kafka.listener.auto-startup=false", "--payments.kafka.enabled=true",
                f"--payments.kafka.ledger-topic={topics[0]}", f"--payments.kafka.hold-topic={topics[1]}",
                "--payments.kafka.lag.enabled=true", "--payments.kafka.lag.poll-interval-ms=500",
                "--payments.kafka.lag.timeout=3s", "--payments.kafka.lag.max-staleness=10s",
                "--payments.retry.enabled=false", "--payments.reconciliation.enabled=false",
                "--payments.outbox.relay.enabled=false", "--payments.outbox.cleanup.enabled=false",
            ], env=app_env, stdout=log, stderr=subprocess.STDOUT)

        eventually("fresh empty-topic sample with stopped listeners", lambda: has_lag(0))
        for topic in topics:
            kafka_cli("kafka-console-producer", "--topic", topic,
                      input="".join(f'{{"smokeRecord":{i}}}\n' for i in range(6)))
        eventually("backlog is six offsets per topic", lambda: has_lag(6))
        body = scrape()
        assert all(metric(body, "payments_kafka_consumer_uncommitted_partitions", group=group, topic=topic) == 3
                   for topic in topics), "Expected all partitions to have no committed offset"

        for topic in topics:
            kafka_cli("kafka-console-consumer", "--topic", topic, "--group", group,
                      "--from-beginning", "--max-messages", "6", "--timeout-ms", "10000",
                      "--consumer-property", "enable.auto.commit=true",
                      "--consumer-property", "auto.commit.interval.ms=100")
        eventually("consumer commits drain both topic backlogs", lambda: has_lag(0))

        paused = True
        run("docker", "pause", kafka)

        def unavailable():
            body = scrape()
            return (metric(body, "payments_kafka_lag_sample_success", group=group) == 0
                    and metric(body, "payments_kafka_lag_sample_failures_total", group=group) >= 1
                    and all(math.isnan(metric(body, "payments_kafka_consumer_lag", group=group, topic=topic))
                            for topic in topics))

        eventually("broker outage reports unknown lag while HTTP scrapes remain responsive", unavailable, timeout=30)
        run("docker", "unpause", kafka)
        paused = False
        eventually("sampler recovers after broker resumes", lambda: has_lag(0))
        print("Consumer lag smoke passed.", flush=True)
    except BaseException:
        if log_path:
            print(f"payment-service log: {log_path}", flush=True)
            print("\n".join(log_path.read_text(errors="replace").splitlines()[-60:]), flush=True)
        raise
    finally:
        if paused:
            cleanup("docker", "unpause", kafka)
        if app is not None:
            try:
                app.terminate()
                try:
                    app.wait(timeout=15)
                except subprocess.TimeoutExpired:
                    app.kill()
                    app.wait(timeout=5)
            except (OSError, subprocess.SubprocessError) as error:
                cleanup_errors.append(f"Stop payment-service: {error}")
        for container in reversed(created):
            cleanup("docker", "rm", "-f", "-v", container)
        if log_path:
            print(f"Application log retained at {log_path}", flush=True)
        if cleanup_errors:
            print("Cleanup errors:\n" + "\n".join(cleanup_errors), file=sys.stderr)
            if sys.exc_info()[0] is None:
                raise RuntimeError("Smoke checks passed but cleanup was incomplete")


if __name__ == "__main__":
    main()
