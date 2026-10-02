import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Set;
import java.util.UUID;
import org.apache.rocketmq.client.consumer.DefaultMQPullConsumer;
import org.apache.rocketmq.client.consumer.PullResult;
import org.apache.rocketmq.client.consumer.PullStatus;
import org.apache.rocketmq.client.producer.DefaultMQProducer;
import org.apache.rocketmq.client.producer.SendResult;
import org.apache.rocketmq.client.producer.SendStatus;
import org.apache.rocketmq.common.message.Message;
import org.apache.rocketmq.common.message.MessageQueue;

/** 宿主机功能探针：读模式核验 Broker 路由和协议；写模式仅使用专用随机 topic。 */
class MqDependencyProbe {
    public static void main(String[] args) throws Exception {
        String namesrv = args.length > 0 ? args[0] : "127.0.0.1:9876";
        boolean write = args.length > 1 && "--write".equals(args[1]);
        String suffix = UUID.randomUUID().toString().replace("-", "");
        DefaultMQPullConsumer consumer = new DefaultMQPullConsumer("stcloud_env_probe_consumer_" + suffix);
        DefaultMQProducer producer = null;
        consumer.setNamesrvAddr(namesrv);
        consumer.setInstanceName("env_probe_" + suffix);
        consumer.setConsumerPullTimeoutMillis(5000);
        try {
            consumer.start();
            Set<MessageQueue> queues = consumer.fetchSubscribeMessageQueues("TBW102");
            if (queues.isEmpty()) throw new IllegalStateException("MQ has no registered Broker route");
            MessageQueue queue = queues.iterator().next();
            consumer.minOffset(queue); // 实际访问 Broker，避免只有 NameServer 路由而 Broker 不可达。
            System.out.println("MQ_READ_PASS registeredBroker=" + queue.getBrokerName() + " queues=" + queues.size());
            if (write) {
                String topic = "stcloud_env_probe_" + suffix;
                byte[] body = ("stcloud dependency probe " + suffix).getBytes(StandardCharsets.UTF_8);
                producer = new DefaultMQProducer("stcloud_env_probe_producer_" + suffix);
                producer.setNamesrvAddr(namesrv);
                producer.setInstanceName("env_probe_send_" + suffix);
                producer.setSendMsgTimeout(5000);
                producer.setVipChannelEnabled(false);
                producer.start();
                SendResult sent = producer.send(new Message(topic, "environment_check", suffix, body));
                if (sent.getSendStatus() != SendStatus.SEND_OK) throw new IllegalStateException("MQ send not acknowledged");
                PullResult pulled = consumer.pull(sent.getMessageQueue(), "*", sent.getQueueOffset(), 1);
                if (pulled.getPullStatus() != PullStatus.FOUND || pulled.getMsgFoundList().size() != 1
                        || !Arrays.equals(body, pulled.getMsgFoundList().get(0).getBody())) {
                    throw new IllegalStateException("MQ round trip payload mismatch");
                }
                // 不提交用户消费位点，不删除 topic；专用消息保留审计。
                System.out.println("MQ_WRITE_READ_PASS topic=" + topic + " queueOffset=" + sent.getQueueOffset()
                        + " payloadBytes=" + body.length + " retained=true");
            }
        } finally {
            if (producer != null) producer.shutdown();
            consumer.shutdown();
        }
    }
}
