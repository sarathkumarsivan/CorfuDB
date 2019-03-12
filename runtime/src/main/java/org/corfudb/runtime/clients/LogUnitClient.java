package org.corfudb.runtime.clients;

import com.codahale.metrics.MetricRegistry;
import com.codahale.metrics.Timer;
import com.google.common.collect.Range;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.opentracing.Span;
import io.opentracing.propagation.Format;
import io.opentracing.propagation.TextMapInjectAdapter;
import lombok.Getter;
import org.corfudb.protocols.logprotocol.LogEntry;
import org.corfudb.protocols.wireprotocol.CorfuMsgType;
import org.corfudb.protocols.wireprotocol.DataType;
import org.corfudb.protocols.wireprotocol.FillHoleRequest;
import org.corfudb.protocols.wireprotocol.ILogData;
import org.corfudb.protocols.wireprotocol.IMetadata;
import org.corfudb.protocols.wireprotocol.LogData;
import org.corfudb.protocols.wireprotocol.MultipleReadRequest;
import org.corfudb.protocols.wireprotocol.RangeWriteMsg;
import org.corfudb.protocols.wireprotocol.ReadRequest;
import org.corfudb.protocols.wireprotocol.ReadResponse;
import org.corfudb.protocols.wireprotocol.TailsResponse;
import org.corfudb.protocols.wireprotocol.Token;
import org.corfudb.protocols.wireprotocol.TrimRequest;
import org.corfudb.protocols.wireprotocol.WriteMode;
import org.corfudb.protocols.wireprotocol.WriteRequest;
import org.corfudb.runtime.CorfuRuntime;
import org.corfudb.util.CorfuComponent;
import org.corfudb.util.serializer.Serializers;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;


/**
 * A client to send messages to a LogUnit.
 *
 * <p>This class provides access to operations on a remote log unit.
 * Created by mwei on 12/10/15.
 */
public class LogUnitClient extends AbstractClient {

    public LogUnitClient(IClientRouter router, long epoch) {
        super(router, epoch);
    }

    public String getHost() {
        return getRouter().getHost();
    }

    public Integer getPort() {
        return getRouter().getPort();
    }

    @Getter
    MetricRegistry metricRegistry = CorfuRuntime.getDefaultMetrics();

    private Timer.Context getTimerContext(String opName) {
        final String timerName = String.format("%s%s:%s-%s",
                CorfuComponent.LOG_UNIT_CLIENT.toString(),
                getHost(),
                getPort().toString(),
                opName);
        Timer t = getMetricRegistry().timer(timerName);
        return t.time();
    }

    /**
     * Asynchronously write to the logging unit.
     *
     * @param address        The address to write to.
     * @param streams        The streams, if any, that this write belongs to.
     * @param rank           The rank of this write (used for quorum
     *                       replication).
     * @param writeObject    The object, pre-serialization, to write.
     * @param backpointerMap The map of backpointers to write.
     * @return A CompletableFuture which will complete with the WriteResult once the
     *     write completes.
     */
    public CompletableFuture<Boolean> write(long address, Set<UUID> streams,
                                            IMetadata.DataRank rank, Object writeObject,
                                            Map<UUID, Long> backpointerMap) {
        Timer.Context context = getTimerContext("writeObject");
        ByteBuf payload = Unpooled.buffer();
        Serializers.CORFU.serialize(writeObject, payload);
        WriteRequest wr = new WriteRequest(WriteMode.NORMAL, null, payload);
        wr.setRank(rank);
        wr.setBackpointerMap(backpointerMap);
        wr.setGlobalAddress(address);
        CompletableFuture<Boolean> cf = sendMessageWithFuture(CorfuMsgType.WRITE.payloadMsg(wr));
        return cf.thenApply(x -> {
            context.stop();
            return x;
        });
    }

    /**
     * Asynchronously write to the logging unit.
     *
     * @param payload The log data to write to the logging unit.
     * @return A CompletableFuture which will complete with the WriteResult once the
     *     write completes.
     */
    public CompletableFuture<Boolean> write(ILogData payload) {
        Map<String, String> inject = new HashMap<>();
        Span activeSpan = getRouter().getParam().getTracer().activeSpan();
        if (activeSpan != null)
            getRouter().getParam().getTracer().inject(
                    activeSpan.context(),
                    Format.Builtin.TEXT_MAP, new TextMapInjectAdapter(inject));

        return sendMessageWithFuture(CorfuMsgType.WRITE.payloadMsg(new WriteRequest(payload, inject)));
    }

    /**
     * Asynchronously write an empty payload to the logging unit with ranked address space.
     * Used from the quorum replication when filling holes or during the first phase of the
     * recovery write.
     *
     * @param address The address to write to.
     * @param type    The data type
     * @param streams The streams, if any, that this write belongs to.
     * @param rank    The rank of this write]
     */
    public CompletableFuture<Boolean> writeEmptyData(long address, DataType type, Set<UUID> streams,
                                                     IMetadata.DataRank rank) {
        Timer.Context context = getTimerContext("writeObject");
        LogEntry entry = new LogEntry(LogEntry.LogEntryType.NOP);
        ByteBuf payload = Unpooled.buffer();
        Serializers.CORFU.serialize(entry, payload);
        WriteRequest wr = new WriteRequest(WriteMode.NORMAL, type, null, payload);
        wr.setRank(rank);
        wr.setGlobalAddress(address);
        CompletableFuture<Boolean> cf = sendMessageWithFuture(CorfuMsgType.WRITE.payloadMsg(wr));
        return cf.thenApply(x -> {
            context.stop();
            return x;
        });
    }

    /**
     * Asynchronously read from the logging unit.
     *
     * @param address The address to read from.
     * @return A CompletableFuture which will complete with a ReadResult once the read
     * completes.
     */
    public CompletableFuture<ReadResponse> read(long address) {
        Timer.Context context = getTimerContext("read");
        CompletableFuture<ReadResponse> cf = sendMessageWithFuture(
                CorfuMsgType.READ_REQUEST.payloadMsg(new ReadRequest(address)));

        return cf.thenApply(x -> {
            context.stop();
            return x;
        });
    }

    /**
     * Read data from the log unit server for a range of addresses.
     *
     * @param range Range of global offsets.
     * @return CompletableFuture which returns a ReadResponse on completion.
     */
    public CompletableFuture<ReadResponse> read(Range<Long> range) {
        Timer.Context context = getTimerContext("readRange");
        CompletableFuture<ReadResponse> cf = sendMessageWithFuture(
                CorfuMsgType.READ_REQUEST.payloadMsg(new ReadRequest(range)));
        return cf.thenApply(x -> {
            context.stop();
            return x;
        });
    }

    /**
     * Read data from the log unit server for a list of addresses.
     *
     * @param list list of global addresses.
     * @return CompletableFuture which returns a ReadResponse on completion.
     */
    public CompletableFuture<ReadResponse> read(List<Long> list) {
        Timer.Context context = getTimerContext("readList");
        CompletableFuture<ReadResponse> cf = sendMessageWithFuture(
                CorfuMsgType.MULTIPLE_READ_REQUEST.payloadMsg(new MultipleReadRequest(list)));
        return cf.thenApply(x -> {
            context.stop();
            return x;
        });
    }

    /**
     * Get the global tail maximum address the log unit has written.
     *
     * @return A CompletableFuture which will complete with the globalTail once
     * received.
     */
    public CompletableFuture<TailsResponse> getTail() {
        return sendMessageWithFuture(CorfuMsgType.TAIL_REQUEST.msg());
    }

    /**
     * Get the starting address of a log unit.
     * @return A CompletableFuture for the starting address
     */
    public CompletableFuture<Long> getTrimMark() {
        return sendMessageWithFuture(CorfuMsgType.TRIM_MARK_REQUEST.msg());
    }


    /**
     * Send a prefix trim request that will trim the log up to a certain address
     *
     * @param address An address to trim up to (i.e. [0, address))
     */
    public CompletableFuture<Void> prefixTrim(Token address) {
        return sendMessageWithFuture(CorfuMsgType.PREFIX_TRIM
                .payloadMsg(new TrimRequest(address)));
    }

    /**
     * Send a compact request that will delete the trimmed parts of the log.
     */
    public CompletableFuture<Void> compact() {
        return sendMessageWithFuture(CorfuMsgType.COMPACT_REQUEST.msg());
    }

    /**
     * Send a flush cache request that will flush the logunit cache.
     */
    public CompletableFuture<Void> flushCache() {
        return sendMessageWithFuture(CorfuMsgType.FLUSH_CACHE.msg());
    }

    /**
     * Fill a hole at a given address.
     *
     * @param address The address to fill a hole at.
     */
    public CompletableFuture<Boolean> fillHole(Token address) {
        Timer.Context context = getTimerContext("fillHole");
        CompletableFuture<Boolean> cf = sendMessageWithFuture(
                CorfuMsgType.FILL_HOLE.payloadMsg(new FillHoleRequest(address)));
        return cf.thenApply(x -> {
            context.stop();
            return x;
        });
    }

    /**
     * Sends a request to write a range of addresses.
     *
     * @param range entries to write to the logunit. Must have at least one entry.
     * @return Completable future which returns true on success.
     */
    public CompletableFuture<Boolean> writeRange(List<LogData> range) {
        if (range.isEmpty()) {
            throw new IllegalArgumentException("Can't write an empty range");
        }

        long base = range.get(0).getGlobalAddress();
        for (int x = 0; x < range.size(); x++) {
            LogData curr = range.get(x);
            if (!curr.getGlobalAddress().equals(base + x)) {
                throw new IllegalArgumentException("Entries not in sequential order!");
            } else if (curr.isEmpty()) {
                throw new IllegalArgumentException("Can't write empty entries!");
            }
        }
        return sendMessageWithFuture(CorfuMsgType.RANGE_WRITE
                .payloadMsg(new RangeWriteMsg(range)));
    }

    /**
     * Send a reset request.
     *
     * @param epoch Epoch to check and set epochWaterMark.
     * @return Completable future which returns true on success.
     */
    public CompletableFuture<Boolean> resetLogUnit(long epoch) {
        return sendMessageWithFuture(CorfuMsgType.RESET_LOGUNIT.payloadMsg(epoch));
    }
}
