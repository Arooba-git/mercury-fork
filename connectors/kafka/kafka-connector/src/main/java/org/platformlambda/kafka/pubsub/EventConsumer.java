/*

    Copyright 2018-2021 Accenture Technology

    Licensed under the Apache License, Version 2.0 (the "License");
    you may not use this file except in compliance with the License.
    You may obtain a copy of the License at

        http://www.apache.org/licenses/LICENSE-2.0

    Unless required by applicable law or agreed to in writing, software
    distributed under the License is distributed on an "AS IS" BASIS,
    WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
    See the License for the specific language governing permissions and
    limitations under the License.

 */

package org.platformlambda.kafka.pubsub;

import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.errors.WakeupException;
import org.apache.kafka.common.header.Header;
import org.apache.kafka.common.serialization.ByteArrayDeserializer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.platformlambda.core.models.EventEnvelope;
import org.platformlambda.core.system.Platform;
import org.platformlambda.core.system.PostOffice;
import org.platformlambda.core.util.Utility;
import org.platformlambda.core.websocket.common.MultipartPayload;
import org.platformlambda.kafka.InitialLoad;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.time.Duration;
import java.util.*;

public class EventConsumer extends Thread {
    private static final Logger log = LoggerFactory.getLogger(EventConsumer.class);

    private static final String TYPE = InitialLoad.TYPE;
    private static final String INIT = InitialLoad.INIT;
    private static final String TOKEN = InitialLoad.TOKEN;
    private static final long INITIALIZE = InitialLoad.INITIALIZE;
    private static final String MONITOR = "monitor";
    private static final String TO_MONITOR = "@"+MONITOR;
    private final String INIT_TOKEN = UUID.randomUUID().toString();
    private final String topic;
    private final int partition;
    private final KafkaConsumer<String, byte[]> consumer;
    private InitialLoad initialLoad;
    private boolean normal = true;
    private int skipped = 0;
    private long offset = -1;

    public EventConsumer(Properties base, String topic, int partition, String... parameters) {
        this.topic = topic;
        this.partition = partition;
        Properties prop = new Properties();
        prop.putAll(base);
        // create unique values for client ID and group ID
        if (parameters.length == 2 || parameters.length == 3) {
            prop.put(ConsumerConfig.CLIENT_ID_CONFIG, parameters[0]);
            prop.put(ConsumerConfig.GROUP_ID_CONFIG, parameters[1]);
            /*
             * If offset is not given, the consumer will read from the latest when it is started for the first time.
             * Subsequent restart of the consumer will resume read from the current offset.
             */
            if (parameters.length == 3) {
                Utility util = Utility.getInstance();
                offset = util.str2int(parameters[2]);
            }
        } else {
            throw new IllegalArgumentException("Unable to start consumer for " + topic +
                                                " - parameters must be clientId, groupId and an optional offset");
        }
        prop.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        prop.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, ByteArrayDeserializer.class);
        this.consumer = new KafkaConsumer<>(prop);
    }

    private long getEarliest(TopicPartition tp) {
        Map<TopicPartition, Long> data = consumer.beginningOffsets(Collections.singletonList(tp));
        return data.get(tp);
    }

    private long getLatest(TopicPartition tp) {
        Map<TopicPartition, Long> data = consumer.endOffsets(Collections.singletonList(tp));
        return data.get(tp);
    }

    @Override
    public void run() {
        if (offset == INITIALIZE) {
            initialLoad = new InitialLoad(topic, partition, INIT_TOKEN);
            initialLoad.start();
        }
        boolean reset = true;
        String origin = Platform.getInstance().getOrigin();
        Utility util = Utility.getInstance();
        PostOffice po = PostOffice.getInstance();
        if (partition < 0) {
            consumer.subscribe(Collections.singletonList(topic));
            log.info("Subscribed {}", topic);
        } else {
            consumer.assign(Collections.singletonList(new TopicPartition(topic, partition)));
            log.info("Subscribed {}, partition-{}", topic, partition);
        }
        try {
            while (normal) {
                long interval = reset? 15 : 60;
                ConsumerRecords<String, byte[]> records = consumer.poll(Duration.ofSeconds(interval));
                if (reset) {
                    Set<TopicPartition> p = consumer.assignment();
                    if (p.isEmpty()) {
                        // wait until a partition is assigned
                        continue;
                    }
                    reset = false;
                    boolean seek = false;
                    for (TopicPartition tp : p) {
                        long earliest = getEarliest(tp);
                        long latest = getLatest(tp);
                        if (offset < 0) {
                            log.info("Reading from {}, partition-{}, current offset {}",
                                    topic, tp.partition(), latest);
                        } else if (offset < earliest) {
                            seek = true;
                            consumer.seek(tp, earliest);
                            log.info("Setting offset of {}, partition-{} to earliest {} instead of {}",
                                    topic, tp.partition(), earliest, offset);
                        } else if (offset < latest) {
                            seek = true;
                            consumer.seek(tp, offset);
                            log.info("Setting offset of {}, partition-{} to {}, original {}-{}",
                                    topic, tp.partition(), offset, earliest, latest);
                         } else if (offset > latest) {
                            log.warn("Offset for {}, partition-{} unchanged because {} is out of range {}-{}",
                                    topic, tp.partition(), offset, earliest, latest);
                        }
                    }
                    if (seek) {
                        continue;
                    }
                }
                for (ConsumerRecord<String, byte[]> record : records) {
                    String recipient = null;
                    for (Header h: record.headers()) {
                        if (EventProducer.RECIPIENT.equals(h.key())) {
                            String r = util.getUTF(h.value());
                            if (!r.contains(MONITOR)) {
                                recipient = r;
                            }
                            break;
                        }
                    }
                    if (recipient != null && !recipient.equals(origin)) {
                        // this is an error case when two consumers listen to the same partition
                        log.error("Skipping record {} because it belongs to {}", record.offset(), recipient);
                        continue;
                    }
                    byte[] data = record.value();
                    EventEnvelope message = new EventEnvelope();
                    try {
                        message.load(data);
                        message.setEndOfRoute();
                    } catch (Exception e) {
                        log.error("Unable to decode incoming event for {} - {}", topic, e.getMessage());
                        continue;
                    }
                    if (offset == INITIALIZE) {
                        Map<String, String> headers = message.getHeaders();
                        if (INIT.equals(message.getBody()) && INIT.equals(headers.get(TYPE)) &&
                                INIT_TOKEN.equals(headers.get(TOKEN))) {
                            initialLoad.close();
                            initialLoad = null;
                            offset = -1;
                            if (skipped > 0) {
                                log.info("Skipped {} outdated event{}", skipped, skipped == 1? "" : "s");
                            }
                        } else {
                            skipped++;
                            continue;
                        }
                    }
                    try {
                        String to = message.getTo();
                        if (to != null) {
                            // remove special routing qualifier for presence monitor events
                            if (to.contains(TO_MONITOR)) {
                                message.setTo(to.substring(0, to.indexOf(TO_MONITOR)));
                            }
                            po.send(message);
                        } else {
                            MultipartPayload.getInstance().incoming(message);
                        }
                    } catch (IOException e) {
                        log.error("Unable to process incoming event for {} - {}", topic, e.getMessage());
                    }
                }
            }
        } catch (Exception e) {
            if (e instanceof WakeupException) {
                log.info("Stopping listener for {}", topic);
            } else {
                /*
                 * We will let the cloud restarts the application instance automatically.
                 * There is nothing we can do.
                 */
                log.error("Unrecoverable event stream error for {} - {} {}", topic, e.getClass(), e.getMessage());
                System.exit(10);
            }
        } finally {
            consumer.close();
            if (partition < 0) {
                log.info("Unsubscribed {}", topic);
            } else {
                log.info("Unsubscribed {}, partition {}", topic, partition);
            }
            if (offset == INITIALIZE && initialLoad != null) {
                initialLoad.close();
            }
        }
    }

    public void shutdown() {
        if (normal) {
            normal = false;
            consumer.wakeup();
        }
    }

}