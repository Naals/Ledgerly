package com.project.paymentservice.events;

import java.nio.charset.StandardCharsets;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.header.Header;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.KafkaHeaders;
import org.springframework.stereotype.Component;

/**
 * Watches every dead-letter topic and counts what lands there. It does not try to fix anything: a dead-lettered event
 * needs a person to look at it, and this makes sure someone is told. On its first run it counts what is already there,
 * once, so existing dead letters are not missed.
 */
@Component
@ConditionalOnProperty(name = "payments.kafka.enabled", havingValue = "true", matchIfMissing = true)
class DeadLetterMonitor {

    private static final Logger log = LoggerFactory.getLogger(DeadLetterMonitor.class);

    private final EventMetrics metrics;

    DeadLetterMonitor(EventMetrics metrics) {
        this.metrics = metrics;
    }

    // a pattern subscription only notices new topics when the metadata refreshes: do that every 30 s, not every 5 min
    @KafkaListener(topicPattern = ".*\\.DLT",
            groupId = "${spring.kafka.consumer.group-id}-dead-letter-monitor",
            properties = "metadata.max.age.ms=30000")
    void onDeadLetter(ConsumerRecord<String, String> record) {
        String sourceTopic = header(record, KafkaHeaders.DLT_ORIGINAL_TOPIC);
        log.error("An event was dead-lettered: source topic={} reason={} key={} topic={} offset={}",
                sourceTopic, header(record, KafkaHeaders.DLT_EXCEPTION_MESSAGE), record.key(), record.topic(),
                record.offset());
        metrics.deadLettered(sourceTopic != null ? sourceTopic : record.topic());
    }

    private static String header(ConsumerRecord<String, String> record, String name) {
        Header header = record.headers().lastHeader(name);
        return header == null ? null : new String(header.value(), StandardCharsets.UTF_8);
    }
}