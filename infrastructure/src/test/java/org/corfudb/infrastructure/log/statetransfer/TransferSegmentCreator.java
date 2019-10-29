package org.corfudb.infrastructure.log.statetransfer;

import org.corfudb.infrastructure.log.statetransfer.StateTransferManager.CurrentTransferSegment;
import org.corfudb.infrastructure.log.statetransfer.streamprocessor.TransferSegmentFailure;

import java.util.Optional;

import static org.corfudb.infrastructure.log.statetransfer.StateTransferManager.*;
import static org.corfudb.infrastructure.log.statetransfer.StateTransferManager.SegmentState.RESTORED;
import static org.corfudb.infrastructure.log.statetransfer.StateTransferManager.SegmentState.TRANSFERRED;

public interface TransferSegmentCreator {
    default CurrentTransferSegment createTransferSegment(long start,
                                                         long end,
                                                         SegmentState state) {
        if (state == RESTORED || state == TRANSFERRED) {
            return CurrentTransferSegment
                    .builder()
                    .status(
                            CurrentTransferSegmentStatus
                                    .builder()
                                    .totalTransferred(end - start + 1)
                                    .segmentState(state)
                                    .build()
                    )
                    .startAddress(start)
                    .endAddress(end)
                    .build();
        } else {
            return CurrentTransferSegment
                    .builder()
                    .status(
                            CurrentTransferSegmentStatus
                                    .builder()
                                    .totalTransferred(0L)
                                    .segmentState(state)
                                    .causeOfFailure(Optional.of(new TransferSegmentFailure()))
                                    .build()
                    )
                    .startAddress(start)
                    .endAddress(end)
                    .build();
        }
    }

    default CurrentTransferSegmentStatus createStatus(SegmentState state, long total, Optional<TransferSegmentFailure> fail) {
        return CurrentTransferSegmentStatus.builder().totalTransferred(total).segmentState(state).causeOfFailure(fail).build();
    }

}
