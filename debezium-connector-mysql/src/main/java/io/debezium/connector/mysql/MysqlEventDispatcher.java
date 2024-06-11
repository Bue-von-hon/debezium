package io.debezium.connector.mysql;

import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;

import org.apache.kafka.connect.data.Schema;
import org.apache.kafka.connect.data.Struct;
import org.apache.kafka.connect.header.ConnectHeaders;
import org.apache.kafka.connect.source.SourceRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.debezium.config.CommonConnectorConfig;
import io.debezium.connector.SnapshotRecord;
import io.debezium.connector.base.ChangeEventQueue;
import io.debezium.data.Envelope;
import io.debezium.data.Envelope.Operation;
import io.debezium.heartbeat.Heartbeat;
import io.debezium.pipeline.DataChangeEvent;
import io.debezium.pipeline.EventDispatcher;
import io.debezium.pipeline.signal.SignalProcessor;
import io.debezium.pipeline.source.spi.DataChangeEventListener;
import io.debezium.pipeline.source.spi.EventMetadataProvider;
import io.debezium.pipeline.spi.ChangeEventCreator;
import io.debezium.pipeline.spi.OffsetContext;
import io.debezium.pipeline.spi.Partition;
import io.debezium.pipeline.txmetadata.TransactionMonitor;
import io.debezium.schema.DataCollectionFilters.DataCollectionFilter;
import io.debezium.schema.DataCollectionSchema;
import io.debezium.schema.DatabaseSchema;
import io.debezium.schema.SchemaNameAdjuster;
import io.debezium.spi.schema.DataCollectionId;
import io.debezium.spi.topic.TopicNamingStrategy;

public class MysqlEventDispatcher<P extends Partition, T extends DataCollectionId> extends EventDispatcher {
    private static final Logger LOGGER = LoggerFactory.getLogger(MysqlEventDispatcher.class);

    public MysqlEventDispatcher(CommonConnectorConfig connectorConfig, TopicNamingStrategy topicNamingStrategy,
                                DatabaseSchema schema, ChangeEventQueue queue, DataCollectionFilter filter,
                                ChangeEventCreator changeEventCreator, EventMetadataProvider metadataProvider,
                                SchemaNameAdjuster schemaNameAdjuster, SignalProcessor signalProcessor) {
        super(connectorConfig, topicNamingStrategy, schema, queue, filter, changeEventCreator, metadataProvider,
              schemaNameAdjuster, signalProcessor);
    }

    public MysqlEventDispatcher(CommonConnectorConfig connectorConfig, TopicNamingStrategy topicNamingStrategy,
                                DatabaseSchema schema, ChangeEventQueue queue, DataCollectionFilter filter,
                                ChangeEventCreator changeEventCreator, EventMetadataProvider metadataProvider,
                                Heartbeat heartbeat, SchemaNameAdjuster schemaNameAdjuster,
                                SignalProcessor signalProcessor) {
        super(connectorConfig, topicNamingStrategy, schema, queue, filter, changeEventCreator, metadataProvider,
              heartbeat, schemaNameAdjuster, signalProcessor);
    }

    public MysqlEventDispatcher(CommonConnectorConfig connectorConfig, TopicNamingStrategy topicNamingStrategy,
                                DatabaseSchema schema, ChangeEventQueue queue, DataCollectionFilter filter,
                                ChangeEventCreator changeEventCreator, EventMetadataProvider metadataProvider,
                                Heartbeat heartbeat, SchemaNameAdjuster schemaNameAdjuster) {
        super(connectorConfig, topicNamingStrategy, schema, queue, filter, changeEventCreator, metadataProvider,
              heartbeat, schemaNameAdjuster);
    }

    public MysqlEventDispatcher(CommonConnectorConfig connectorConfig, TopicNamingStrategy topicNamingStrategy,
                                DatabaseSchema schema, ChangeEventQueue queue, DataCollectionFilter filter,
                                ChangeEventCreator changeEventCreator, EventMetadataProvider metadataProvider,
                                SchemaNameAdjuster schemaNameAdjuster) {
        super(connectorConfig, topicNamingStrategy, schema, queue, filter, changeEventCreator, metadataProvider,
              schemaNameAdjuster);
    }

    public MysqlEventDispatcher(CommonConnectorConfig connectorConfig, TopicNamingStrategy topicNamingStrategy,
                                DatabaseSchema schema, ChangeEventQueue queue, DataCollectionFilter filter,
                                ChangeEventCreator changeEventCreator,
                                InconsistentSchemaHandler inconsistentSchemaHandler,
                                EventMetadataProvider metadataProvider, Heartbeat heartbeat,
                                SchemaNameAdjuster schemaNameAdjuster, SignalProcessor signalProcessor) {
        super(connectorConfig, topicNamingStrategy, schema, queue, filter, changeEventCreator,
              inconsistentSchemaHandler, metadataProvider, heartbeat, schemaNameAdjuster, signalProcessor);
    }

    public MysqlEventDispatcher(CommonConnectorConfig connectorConfig, TopicNamingStrategy topicNamingStrategy,
                                DatabaseSchema schema, ChangeEventQueue queue, DataCollectionFilter filter,
                                ChangeEventCreator changeEventCreator,
                                InconsistentSchemaHandler inconsistentSchemaHandler, Heartbeat heartbeat,
                                SchemaNameAdjuster schemaNameAdjuster,
                                TransactionMonitor transactionMonitor,
                                SignalProcessor signalProcessor) {
        super(connectorConfig, topicNamingStrategy, schema, queue, filter, changeEventCreator,
              inconsistentSchemaHandler, heartbeat, schemaNameAdjuster, transactionMonitor, signalProcessor);
    }

    @Override
    public SnapshotReceiver<P> getIncrementalSnapshotChangeEventReceiver(DataChangeEventListener dataListener) {
        return new IncrementalSnapshotChangeRecordReceiver(dataListener);
    }

    @Override
    public SnapshotReceiver<P> getSnapshotChangeEventReceiver() {
        return new BufferingSnapshotChangeRecordReceiver(connectorConfig.getSnapshotMaxThreads() > 1);
    }

    private final class IncrementalSnapshotChangeRecordReceiver implements SnapshotReceiver<P> {

        public final DataChangeEventListener<P> dataListener;

        IncrementalSnapshotChangeRecordReceiver(DataChangeEventListener<P> dataListener) {
            this.dataListener = dataListener;
        }

        @Override
        public void changeRecord(P partition,
                                 DataCollectionSchema dataCollectionSchema,
                                 Operation operation,
                                 Object key, Struct value,
                                 OffsetContext offsetContext,
                                 ConnectHeaders headers)
                throws InterruptedException {
            Objects.requireNonNull(value, "value must not be null");

            LOGGER.trace("Received change record for {} operation on key {}", operation, key);

            Schema keySchema = dataCollectionSchema.keySchema();
            String topicName = topicNamingStrategy.dataChangeTopic((T) dataCollectionSchema.id());

            doPostProcessing(key, value);

            // TODO(hun): Marker for where to create a SourceRecord
            SourceRecord record = new SourceRecord(
                    partition.getSourcePartition(),
                    offsetContext.getOffset(),
                    topicName, null,
                    keySchema, key,
                    dataCollectionSchema.getEnvelopeSchema().schema(), value,
                    null, headers);
            dataListener.onEvent(partition, dataCollectionSchema.id(), offsetContext, keySchema, value, operation);
            queue.enqueue(changeEventCreator.createDataChangeEvent(record));
        }

        @Override
        public void completeSnapshot() throws InterruptedException {
        }
    }

    private static final class BufferedDataChangeEvent {
        private static final BufferedDataChangeEvent
                NULL = new BufferedDataChangeEvent();

        private DataChangeEvent dataChangeEvent;
        private OffsetContext offsetContext;

    }

    private final class BufferingSnapshotChangeRecordReceiver implements SnapshotReceiver<P> {

        private AtomicReference<BufferedDataChangeEvent>
                bufferedEventRef = new AtomicReference<>(BufferedDataChangeEvent.NULL);
        private final boolean threaded;

        BufferingSnapshotChangeRecordReceiver(boolean threaded) {
            this.threaded = threaded;
        }

        @Override
        public void changeRecord(P partition,
                                 DataCollectionSchema dataCollectionSchema,
                                 Operation operation,
                                 Object key, Struct value,
                                 OffsetContext offsetContext,
                                 ConnectHeaders headers)
                throws InterruptedException {
            Objects.requireNonNull(value, "value must not be null");

            LOGGER.trace("Received change record for {} operation on key {}", operation, key);

            doPostProcessing(key, value);

            // TODO(hun): Marker for where to create a SourceRecord
            SourceRecord record = new SourceRecord(
                    partition.getSourcePartition(),
                    offsetContext.getOffset(),
                    topicNamingStrategy.dataChangeTopic((T) dataCollectionSchema.id()),
                    null,
                    dataCollectionSchema.keySchema(),
                    key,
                    // TODO(hun): maybe it contains column
                    dataCollectionSchema.getEnvelopeSchema().schema(),
                    value,
                    null,
                    headers);

            BufferedDataChangeEvent nextBufferedEvent = new BufferedDataChangeEvent();
            nextBufferedEvent.offsetContext = offsetContext;
            nextBufferedEvent.dataChangeEvent = new DataChangeEvent(record);

            if (threaded) {
                // This entire step needs to happen atomically when using buffering with multiple threads.
                // This guarantees that the getAndSet and the enqueue do not cause a dispatch of out-of-order
                // events within a single thread.
                synchronized (queue) {
                    queue.enqueue(bufferedEventRef.getAndSet(nextBufferedEvent).dataChangeEvent);
                }
            }
            else {
                queue.enqueue(bufferedEventRef.getAndSet(nextBufferedEvent).dataChangeEvent);
            }
        }

        @Override
        public void completeSnapshot() throws InterruptedException {
            // It is possible that the last snapshotted table was empty
            // this way we ensure that the last event is always marked as last
            // even if it originates form non-last table
            final BufferedDataChangeEvent bufferedEvent = bufferedEventRef.getAndSet(BufferedDataChangeEvent.NULL);
            DataChangeEvent event = bufferedEvent.dataChangeEvent;
            if (event != null) {
                SourceRecord record = event.getRecord();
                final Struct envelope = (Struct) record.value();
                if (envelope.schema().field(Envelope.FieldName.SOURCE) != null) {
                    final Struct source = envelope.getStruct(Envelope.FieldName.SOURCE);
                    SnapshotRecord.LAST.toSource(source);
                }
                @SuppressWarnings("unchecked")
                final Map<String, Object> offset = (Map<String, Object>) record.sourceOffset();
                offset.clear();
                offset.putAll(bufferedEvent.offsetContext.getOffset());
                queue.enqueue(event);
            }
        }
    }
}
