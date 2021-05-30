package org.platformlambda.activemq.services;

import org.apache.activemq.artemis.api.core.client.*;
import org.apache.activemq.artemis.api.core.management.ManagementHelper;
import org.apache.activemq.artemis.api.core.management.ResourceNames;
import org.platformlambda.activemq.ArtemisConnector;
import org.platformlambda.cloud.ConnectorConfig;
import org.platformlambda.core.models.LambdaFunction;
import org.platformlambda.core.util.Utility;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Properties;

public class TopicManager implements LambdaFunction {
    private static final Logger log = LoggerFactory.getLogger(TopicManager.class);

    private static final String TYPE = "type";
    private static final String PARTITIONS = "partitions";
    private static final String TOPIC = "topic";
    private static final String CREATE = "create";
    private static final String DELETE = "delete";
    private static final String LIST = "list";
    private static final String EXISTS = "exists";
    private static final String USER_ID = "admin.id";
    private static final String USER_PWD = "admin.password";
    private static final String ACTIVEMQ_MANAGEMENT = "activemq.management";
    private static final String[] ACTIVEMQ_RESERVED = {"DLQ", "ExpiryQueue"};
    private static final String ACTIVEMQ_PREFIX = "activemq.";
    private static final String TOPIC_SIGNATURE = "routingTypes={MULTICAST}";
    private static final String MULTICAST = "MULTICAST";
    private static final String ADDRESS = "Address";
    private static ClientSession session;
    private final boolean topicSubstitution;
    private final Map<String, String> preAllocatedTopics;

    public TopicManager() throws Exception {
        topicSubstitution = ConnectorConfig.topicSubstitutionEnabled();
        preAllocatedTopics = ConnectorConfig.getTopicSubstitution();
        if (!topicSubstitution && session == null) {
            Properties properties = ArtemisConnector.getClusterProperties();
            String cluster = properties.getProperty(ArtemisConnector.BROKER_URL, "tcp://127.0.0.1:61616");
            String userId = properties.getProperty(USER_ID, "");
            String password = properties.getProperty(USER_PWD, "");
            ServerLocator locator = ActiveMQClient.createServerLocator(cluster);
            ClientSessionFactory factory2 = locator.createSessionFactory();
            session = factory2.createSession(userId, password,
                    false, true, true, false, 1);
            session.start();
        }
    }

    @Override
    public Object handleEvent(Map<String, String> headers, Object body, int instance) throws Exception {
        if (headers.containsKey(TYPE)) {
            if (LIST.equals(headers.get(TYPE))) {
                return listTopics();
            }
            if (EXISTS.equals(headers.get(TYPE)) && headers.containsKey(TOPIC)) {
                String origin = headers.get(TOPIC);
                return topicExists(origin);
            }
            if (PARTITIONS.equals(headers.get(TYPE)) && headers.containsKey(TOPIC)) {
                String origin = headers.get(TOPIC);
                return topicPartitions(origin);
            }
            // if origin is not specified, it will create the dedicated topic for a new application that is starting up
            if (CREATE.equals(headers.get(TYPE)) && headers.containsKey(TOPIC)) {
                if (headers.containsKey(PARTITIONS)) {
                    int partitions = Math.max(1, Utility.getInstance().str2int(headers.get(PARTITIONS)));
                    createTopic(headers.get(TOPIC), partitions);
                } else {
                    createTopic(headers.get(TOPIC));
                }
                return true;
            }
            // delete topic when an application instance expires
            if (DELETE.equals(headers.get(TYPE)) && headers.containsKey(TOPIC)) {
                String origin = headers.get(TOPIC);
                if (topicExists(origin)) {
                    deleteTopic(origin);
                }
                return true;
            }
        }
        return false;
    }

    private boolean topicExists(String topic) throws Exception {
        if (topicSubstitution) {
            return preAllocatedTopics.get(topic) != null;
        }
        try (ClientRequestor requestor = new ClientRequestor(session, ACTIVEMQ_MANAGEMENT)) {
            ClientMessage m = session.createMessage(false);
            ManagementHelper.putOperationInvocation(m, ResourceNames.BROKER,
                    "getAddressInfo", topic);
            ClientMessage reply = requestor.request(m);
            Object o = ManagementHelper.getResult(reply);
            return o instanceof String && ((String) o).startsWith(ADDRESS);
        }
    }
    
    private int topicPartitions(String topic) throws Exception {
        if (topicSubstitution) {
            int n = 0;
            while (preAllocatedTopics.containsKey(topic+"."+n)) {
                n++;
            }
            return n;
        }
        String firstPartition = topic + ".0";
        if (topicExists(firstPartition)) {
            Utility util = Utility.getInstance();
            List<String> segments = util.split(topic, ".");
            try (ClientRequestor requestor = new ClientRequestor(session, ACTIVEMQ_MANAGEMENT)) {
                ClientMessage m = session.createMessage(false);
                ManagementHelper.putOperationInvocation(m, ResourceNames.BROKER,
                        "listAddresses", "|");
                ClientMessage reply = requestor.request(m);
                Object o = ManagementHelper.getResult(reply);
                if (o instanceof String) {
                    int n = 0;
                    List<String> topicList = util.split((String) o, "|");
                    for (String t : topicList) {
                        if (t.startsWith(topic + ".")) {
                            List<String> parts = util.split(t, ".");
                            if (parts.size() == segments.size() + 1) {
                                if (util.isDigits(parts.get(parts.size() - 1))) {
                                    n++;
                                }
                            }
                        }
                    }
                    return n == 0 ? -1 : n;
                }
            }
        }
        return -1;
    }

    private void createTopic(String topic) throws Exception {
        if (topicSubstitution) {
            if (preAllocatedTopics.get(topic) == null) {
                throw new IllegalArgumentException("Missing topic substitution for "+topic);
            }
            return;
        }
        if (!topicExists(topic)) {
            try (ClientRequestor requestor = new ClientRequestor(session, ACTIVEMQ_MANAGEMENT)) {
                ClientMessage m = session.createMessage(false);
                ManagementHelper.putOperationInvocation(m, ResourceNames.BROKER,
                        "createAddress", topic, MULTICAST);
                ClientMessage reply = requestor.request(m);
                Object o = ManagementHelper.getResult(reply);
                if (o instanceof String) {
                    String result = (String) o;
                    if (result.startsWith(ADDRESS)) {
                        log.info("Created {}", topic);
                    } else {
                        log.warn("ActiveMQ exception when creating {} {}", topic, o);
                    }
                }
            }
        }
    }

    private void createTopic(String topic, int partitions) throws Exception {
        String firstPartition = topic + ".0";
        if (!topicExists(firstPartition) && partitions > 0) {
            for (int i=0; i < partitions; i++) {
                createTopic(topic + "." + i);
            }
        }
    }

    private void deleteTopic(String topic) throws Exception {
        if (topicSubstitution) {
            if (preAllocatedTopics.get(topic) == null) {
                throw new IllegalArgumentException("Missing topic substitution for "+topic);
            }
            return;
        }
        if (topicExists(topic)) {
            try (ClientRequestor requestor = new ClientRequestor(session, ACTIVEMQ_MANAGEMENT)) {
                ClientMessage m = session.createMessage(false);
                ManagementHelper.putOperationInvocation(m, ResourceNames.BROKER,
                        "deleteAddress", topic);
                ClientMessage reply = requestor.request(m);
                Object o = ManagementHelper.getResult(reply);
                if (o != null) {
                    log.warn("ActiveMQ exception when deleting {} {}", topic, o);
                }
            }
        }
    }

    private List<String> listTopics() throws Exception {
        if (topicSubstitution) {
            return new ArrayList<>(preAllocatedTopics.keySet());
        }
        List<String> result = new ArrayList<>();
        Utility util = Utility.getInstance();
        try (ClientRequestor requestor = new ClientRequestor(session, ACTIVEMQ_MANAGEMENT)) {
            ClientMessage m = session.createMessage(false);
            ManagementHelper.putOperationInvocation(m, ResourceNames.BROKER,
                    "listAddresses", "|");
            ClientMessage reply = requestor.request(m);
            Object o = ManagementHelper.getResult(reply);
            if (o instanceof String) {
                List<String> topicList = util.split((String) o, "|");
                for (String t : topicList) {
                    if (!isReserved(t) && isMulticast(t)) {
                        result.add(t);
                    }
                }
            }
        }
        return result;
    }

    private boolean isMulticast(String topic) throws Exception {
        if (topicSubstitution) {
            return true;
        }
        try (ClientRequestor requestor = new ClientRequestor(session, ACTIVEMQ_MANAGEMENT)) {
            ClientMessage m = session.createMessage(false);
            ManagementHelper.putOperationInvocation(m, ResourceNames.BROKER,
                    "getAddressInfo", topic);
            ClientMessage reply = requestor.request(m);
            Object o = ManagementHelper.getResult(reply);
            if (o instanceof String) {
                String result = (String) o;
                return result.contains(TOPIC_SIGNATURE);
            }
            return false;
        }
    }

    private boolean isReserved(String topic) {
        if (topic.startsWith(ACTIVEMQ_PREFIX)) {
            return true;
        }
        for (String reserved: ACTIVEMQ_RESERVED) {
            if (reserved.equals(topic)) {
                return true;
            }
        }
        return false;
    }

}
