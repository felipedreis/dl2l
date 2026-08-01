package br.cefetmg.lsi.l2l.creature.bd;

import blue.strategic.parquet.Dehydrator;
import blue.strategic.parquet.ValueWriter;
import org.apache.hadoop.conf.Configuration;
import org.apache.parquet.column.ParquetProperties;
import org.apache.parquet.hadoop.api.WriteSupport;
import org.apache.parquet.hadoop.metadata.CompressionCodecName;
import org.apache.parquet.io.DelegatingPositionOutputStream;
import org.apache.parquet.io.OutputFile;
import org.apache.parquet.io.PositionOutputStream;
import org.apache.parquet.io.api.Binary;
import org.apache.parquet.io.api.RecordConsumer;
import org.apache.parquet.schema.LogicalTypeAnnotation;
import org.apache.parquet.schema.MessageType;
import org.apache.parquet.schema.PrimitiveType;

import java.io.Closeable;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.Collections;

/**
 * Drop-in replacement for {@code blue.strategic.parquet.ParquetWriter} that exposes
 * {@code withRowGroupSize}. {@code parquet-floor}'s own factory hardcodes only compression
 * codec + writer version and never calls {@code withRowGroupSize} on the (real,
 * {@code org.apache.parquet.hadoop.ParquetWriter.Builder}-derived) builder it wraps, so every
 * writer silently used parquet-hadoop's default - confirmed via javap:
 * {@code ParquetWriter.DEFAULT_BLOCK_SIZE = 134217728} (128MiB) - and parquet-floor's
 * {@code Builder} is {@code private}, so no caller could override it either. With
 * {@link ParquetBackend} holding 22 tables' writers open for a trial's entire lifetime, worst
 * case simultaneous row-group buffering was up to 22 * 128MiB = ~2.75GB, a real and distinct
 * contributor to the 10-creature OOM alongside the (already-measured) Akka mailbox backlog -
 * see docs/plans/parquet-write-path.md. {@link #ROW_GROUP_SIZE_BYTES} bounds the worst case
 * to 22 * 4MiB = ~88MB instead.
 *
 * <p>Everything below {@link #writeFile} is parquet-floor's own {@code SimpleWriteSupport}
 * copied verbatim (it was never the problem - only its {@code Builder} was) so
 * {@link ParquetBackend}'s {@link Dehydrator}/{@link ValueWriter} usage needs no changes.
 */
final class TunedParquetWriter<T> implements Closeable {

    static final long ROW_GROUP_SIZE_BYTES = 4L * 1024 * 1024;

    private final org.apache.parquet.hadoop.ParquetWriter<T> writer;

    static <T> TunedParquetWriter<T> writeFile(MessageType schema, File out, Dehydrator<T> dehydrator) throws IOException {
        OutputFile f = new OutputFile() {
            @Override
            public PositionOutputStream create(long blockSizeHint) throws IOException {
                return createOrOverwrite(blockSizeHint);
            }

            @Override
            public PositionOutputStream createOrOverwrite(long blockSizeHint) throws IOException {
                FileOutputStream fos = new FileOutputStream(out);
                return new DelegatingPositionOutputStream(fos) {
                    @Override
                    public long getPos() throws IOException {
                        return fos.getChannel().position();
                    }
                };
            }

            @Override
            public boolean supportsBlockSize() {
                return false;
            }

            @Override
            public long defaultBlockSize() {
                return ROW_GROUP_SIZE_BYTES;
            }
        };
        return new TunedParquetWriter<>(f, schema, dehydrator);
    }

    private TunedParquetWriter(OutputFile outputFile, MessageType schema, Dehydrator<T> dehydrator) throws IOException {
        this.writer = new Builder<T>(outputFile)
                .withType(schema)
                .withDehydrator(dehydrator)
                .withCompressionCodec(CompressionCodecName.SNAPPY)
                .withWriterVersion(ParquetProperties.WriterVersion.PARQUET_2_0)
                .withRowGroupSize(ROW_GROUP_SIZE_BYTES)
                .build();
    }

    void write(T record) throws IOException {
        writer.write(record);
    }

    @Override
    public void close() throws IOException {
        writer.close();
    }

    private static final class Builder<T> extends org.apache.parquet.hadoop.ParquetWriter.Builder<T, Builder<T>> {
        private MessageType schema;
        private Dehydrator<T> dehydrator;

        private Builder(OutputFile file) {
            super(file);
        }

        Builder<T> withType(MessageType schema) {
            this.schema = schema;
            return this;
        }

        Builder<T> withDehydrator(Dehydrator<T> dehydrator) {
            this.dehydrator = dehydrator;
            return this;
        }

        @Override
        protected Builder<T> self() {
            return this;
        }

        @Override
        protected WriteSupport<T> getWriteSupport(Configuration conf) {
            return new SimpleWriteSupport<>(schema, dehydrator);
        }
    }

    private static class SimpleWriteSupport<T> extends WriteSupport<T> {
        private final MessageType schema;
        private final Dehydrator<T> dehydrator;
        private final ValueWriter valueWriter = this::writeField;

        private RecordConsumer recordConsumer;

        SimpleWriteSupport(MessageType schema, Dehydrator<T> dehydrator) {
            this.schema = schema;
            this.dehydrator = dehydrator;
        }

        @Override
        public WriteContext init(Configuration configuration) {
            return new WriteContext(schema, Collections.emptyMap());
        }

        @Override
        public void prepareForWrite(RecordConsumer recordConsumer) {
            this.recordConsumer = recordConsumer;
        }

        @Override
        public void write(T record) {
            recordConsumer.startMessage();
            dehydrator.dehydrate(record, valueWriter);
            recordConsumer.endMessage();
        }

        @Override
        public String getName() {
            return "br.cefetmg.lsi.l2l.creature.bd.TunedParquetWriter";
        }

        private void writeField(String name, Object value) {
            int fieldIndex = schema.getFieldIndex(name);
            PrimitiveType type = schema.getType(fieldIndex).asPrimitiveType();
            recordConsumer.startField(name, fieldIndex);

            switch (type.getPrimitiveTypeName()) {
                case INT32 -> recordConsumer.addInteger((int) value);
                case INT64 -> recordConsumer.addLong((long) value);
                case DOUBLE -> recordConsumer.addDouble((double) value);
                case BOOLEAN -> recordConsumer.addBoolean((boolean) value);
                case FLOAT -> recordConsumer.addFloat((float) value);
                case BINARY -> {
                    if (type.getLogicalTypeAnnotation() == LogicalTypeAnnotation.stringType()) {
                        recordConsumer.addBinary(Binary.fromString((String) value));
                    } else {
                        throw new UnsupportedOperationException("We don't support writing " + type.getLogicalTypeAnnotation());
                    }
                }
                default -> throw new UnsupportedOperationException("We don't support writing " + type.getPrimitiveTypeName());
            }
            recordConsumer.endField(name, fieldIndex);
        }
    }
}
