package com.mesosphere.metrics.consumer.common;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.apache.avro.file.DataFileStream;
import org.apache.avro.io.DatumReader;
import org.apache.avro.specific.SpecificDatumReader;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.serialization.ByteArrayDeserializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import dcos.metrics.MetricList;

public class ConsumerRunner {

  public interface MetricOutputFactory {
    public MetricOutput getOutput() throws Exception;
  }

  private static final Logger LOGGER = LoggerFactory.getLogger(ConsumerRunner.class);

  private static class ConsumerRunnable implements Runnable {
    private final MetricOutput output;
    private final KafkaConsumer<byte[], byte[]> kafkaConsumer;
    private final ClientConfigs.ConsumerConfig consumerConfig;
    private final Stats.Values values;

    private ConsumerRunnable(
        MetricOutputFactory outputFactory,
        Map<String, Object> kafkaConfig,
        ClientConfigs.ConsumerConfig consumerConfig,
        Stats.Values values) throws Exception {
      ByteArrayDeserializer deserializer = new ByteArrayDeserializer();
      this.output = outputFactory.getOutput();
      this.kafkaConsumer = new KafkaConsumer<>(kafkaConfig, deserializer, deserializer);
      this.consumerConfig = consumerConfig;
      this.values = values;
    }

    @Override
    public void run() {
      List<String> topics = new ArrayList<>();
      topics.add(consumerConfig.topic);
      kafkaConsumer.subscribe(topics);//TODO automatically subscribe to topics that start with 'metrics-' (see collector's -kafka-topic-prefix)
      DatumReader<MetricList> datumReader = new SpecificDatumReader<MetricList>(MetricList.class);
      while (!values.isShutdown()) {
        long messages = 0;
        long bytes = 0;
        MetricList metricList = null;
        try {
          LOGGER.info("Waiting {}ms for messages", consumerConfig.pollTimeoutMs);
          ConsumerRecords<byte[], byte[]> kafkaRecords = kafkaConsumer.poll(consumerConfig.pollTimeoutMs);
          messages = kafkaRecords.count();
          for (ConsumerRecord<byte[], byte[]> kafkaRecord : kafkaRecords) {
            if (kafkaRecord.key() != null) {
              bytes += kafkaRecord.key().length;
              LOGGER.warn("Kafka message had non-null key ({} bytes), expected key to be missing.",
                  kafkaRecord.key().length);
            }
            bytes += kafkaRecord.value().length;

            LOGGER.info("Processing {} byte Kafka message", kafkaRecord.value().length);

            // Recreate the DataFileStream every time: Each Kafka record has a datafile header.
            InputStream inputStream = new ByteArrayInputStream(kafkaRecord.value());
            DataFileStream<MetricList> dataFileStream =
                new DataFileStream<MetricList>(inputStream, datumReader);
            long metricLists = 0;
            long tags = 0;
            long datapoints = 0;
            while (dataFileStream.hasNext()) {
              metricList = dataFileStream.next(metricList);// reuse same object. docs imply this is more efficient.

              output.append(metricList);

              metricLists++;
              tags += metricList.getTags().size();
              datapoints += metricList.getDatapoints().size();
            }
            dataFileStream.close();

            LOGGER.info("Kafka message ({} bytes) contained {} Datapoints and {} Tags across {} MetricLists",
                kafkaRecord.value().length, datapoints, tags, metricLists, kafkaRecord.value().length);
          }
          LOGGER.info("Got {} messages ({} bytes)", messages, bytes);
        } catch (Throwable e) {
          values.registerError(e);
        }
        output.flush(); // always flush, even if consumption fails (in case it failed after several append()s)
        values.incMessages(messages);
        values.incBytes(bytes);
      }
      kafkaConsumer.close();
    }
  }

  private static boolean runConsumers(MetricOutputFactory outputFactory, ConfigParser.Config config, Stats.PrintRunner printer) {
    ClientConfigs.ConsumerConfig consumerConfig = config.getConsumerConfig();
    if (consumerConfig == null) {
      LOGGER.error("Unable to load consumer config, exiting");
      return false;
    }

    ThreadRunner runner = new ThreadRunner(printer.getValues());
    runner.add("printStatsThread", printer);
    for (int i = 0; i < consumerConfig.threads; ++i) {
      ConsumerRunnable consumer;
      try {
        consumer = new ConsumerRunnable(outputFactory, config.getKafkaConfig(), consumerConfig, printer.getValues());
      } catch (Throwable e) {
        printer.getValues().registerError(e);
        return false;
      }
      runner.add("consumerThread-" + String.valueOf(i), consumer);
    }

    runner.runThreads();
    return !runner.isFatalError();
  }

  public static void run(MetricOutputFactory outputFactory) {
    ConfigParser.Config config = ConfigParser.getConfig();
    if (config == null) {
      LOGGER.error("Unable to load base config, exiting");
      System.exit(1);
    }

    ClientConfigs.StatsConfig statsConfig = config.getStatsConfig();
    if (statsConfig == null) {
      LOGGER.error("Unable to load stats config, exiting");
      System.exit(1);
    }
    Stats.PrintRunner printer = new Stats.PrintRunner(statsConfig);
    System.exit(runConsumers(outputFactory, config, printer) ? 0 : -1);
  }
}