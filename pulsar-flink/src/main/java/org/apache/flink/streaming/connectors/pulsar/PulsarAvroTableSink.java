/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.apache.flink.streaming.connectors.pulsar;

import static org.apache.flink.util.Preconditions.checkArgument;
import static org.apache.flink.util.Preconditions.checkNotNull;
import static org.apache.flink.util.Preconditions.checkState;

import java.util.Arrays;

import org.apache.avro.Schema;
import org.apache.avro.specific.SpecificData;
import org.apache.avro.specific.SpecificRecord;
import org.apache.flink.api.common.serialization.SerializationSchema;
import org.apache.flink.api.common.typeinfo.TypeInformation;
import org.apache.flink.api.java.typeutils.RowTypeInfo;
import org.apache.flink.formats.avro.AvroRowSerializationSchema;
import org.apache.flink.streaming.api.datastream.DataStream;
import org.apache.flink.streaming.connectors.pulsar.partitioner.PulsarKeyExtractor;
import org.apache.flink.table.sinks.AppendStreamTableSink;
import org.apache.flink.table.sinks.TableSink;
import org.apache.flink.types.Row;

/**
 * An append-only table sink to emit a streaming table as a Pulsar stream that serializes data in Avro format.
 */
public class PulsarAvroTableSink implements AppendStreamTableSink<Row> {

    protected final String serviceUrl;
    protected final String topic;
    protected final String routingKeyFieldName;
    protected SerializationSchema<Row> serializationSchema;
    protected String[] fieldNames;
    protected TypeInformation[] fieldTypes;
    protected PulsarKeyExtractor<Row> keyExtractor;
    private Class<? extends SpecificRecord> recordClazz;

    /**
     * Create PulsarAvroTableSink.
     *
     * @param serviceUrl          pulsar service url
     * @param topic               topic in pulsar to which table is written
     * @param producerConf        producer configuration
     * @param routingKeyFieldName routing key field name
     */
    public PulsarAvroTableSink(
            String serviceUrl,
            String topic,
            String routingKeyFieldName,
            Class<? extends SpecificRecord> recordClazz) {
        this.serviceUrl = checkNotNull(serviceUrl, "Service url not set");
        this.topic = checkNotNull(topic, "Topic is null");
        this.routingKeyFieldName = routingKeyFieldName;
        this.recordClazz = recordClazz;
    }

    /**
     * Returns the low-level producer.
     */
    protected FlinkPulsarProducer<Row> createFlinkPulsarProducer() {
        serializationSchema = new AvroRowSerializationSchema(recordClazz);
        return new FlinkPulsarProducer<Row>(
                serviceUrl,
                topic,
                serializationSchema,
                keyExtractor);
    }

    @Override
    public void emitDataStream(DataStream<Row> dataStream) {
        checkState(fieldNames != null, "Table sink is not configured");
        checkState(fieldTypes != null, "Table sink is not configured");
        checkState(serializationSchema != null, "Table sink is not configured");
        checkState(keyExtractor != null, "Table sink is not configured");
        FlinkPulsarProducer<Row> producer = createFlinkPulsarProducer();
        dataStream.addSink(producer);
    }

    @Override
    public TypeInformation<Row> getOutputType() {
        RowTypeInfo rowTypeInfo = new RowTypeInfo(fieldTypes, fieldNames);
        return rowTypeInfo;
    }

    @Override
    public String[] getFieldNames() {
        return fieldNames;
    }

    @Override
    public TypeInformation<?>[] getFieldTypes() {
        return fieldTypes;
    }

    @Override
    public TableSink<Row> configure(String[] fieldNames, TypeInformation<?>[] fieldTypes) {
        PulsarAvroTableSink sink = new PulsarAvroTableSink(serviceUrl, topic, routingKeyFieldName, recordClazz);

        sink.fieldNames = checkNotNull(fieldNames, "Field names are null");
        sink.fieldTypes = checkNotNull(fieldTypes, "Field types are null");
        checkArgument(fieldNames.length == fieldTypes.length,
                "Number of provided field names and types do not match");

        sink.serializationSchema = new AvroRowSerializationSchema(recordClazz);
        sink.keyExtractor = new AvroKeyExtractor(
                routingKeyFieldName,
                fieldNames,
                fieldTypes,
                recordClazz);

        return sink;
    }


    /**
     * A key extractor that extracts the routing key from a {@link Row} by field name.
     */
    private static class AvroKeyExtractor implements PulsarKeyExtractor<Row> {
        private final int keyIndex;

        public AvroKeyExtractor(
                String keyFieldName,
                String[] fieldNames,
                TypeInformation<?>[] fieldTypes,
                Class<? extends SpecificRecord> recordClazz) {

            checkArgument(fieldNames.length == fieldTypes.length,
                    "Number of provided field names and types does not match.");

            Schema schema = SpecificData.get().getSchema(recordClazz);
            Schema.Field keyField = schema.getField(keyFieldName);
            Schema.Type keyType = keyField.schema().getType();

            int keyIndex = Arrays.asList(fieldNames).indexOf(keyFieldName);
            checkArgument(keyIndex >= 0,
                    "Key field '" + keyFieldName + "' not found");

            checkArgument(Schema.Type.STRING.equals(keyType),
                    "Key field must be of type 'STRING'");
            this.keyIndex = keyIndex;
        }

        @Override
        public String getKey(Row event) {
            return event.getField(keyIndex).toString();
        }
    }

}
