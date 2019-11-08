package org.corfudb.infrastructure.redundancy;

import com.google.common.collect.ImmutableList;
import org.corfudb.infrastructure.LayoutBasedTestHelper;
import org.corfudb.infrastructure.log.statetransfer.StateTransferManager.TransferSegment;
import org.corfudb.infrastructure.log.statetransfer.StateTransferManager.TransferSegmentStatus;
import org.corfudb.infrastructure.log.statetransfer.StateTransferManager.TransferSegmentStatus.SegmentState;
import org.corfudb.infrastructure.log.statetransfer.TransferSegmentCreator;
import org.corfudb.runtime.view.Layout;
import org.corfudb.runtime.view.Layout.LayoutSegment;
import org.junit.Assert;
import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.corfudb.infrastructure.log.statetransfer.StateTransferManager.TransferSegmentStatus.SegmentState.FAILED;
import static org.corfudb.infrastructure.log.statetransfer.StateTransferManager.TransferSegmentStatus.SegmentState.NOT_TRANSFERRED;
import static org.corfudb.infrastructure.log.statetransfer.StateTransferManager.TransferSegmentStatus.SegmentState.RESTORED;
import static org.corfudb.infrastructure.log.statetransfer.StateTransferManager.TransferSegmentStatus.SegmentState.TRANSFERRED;
import static org.corfudb.runtime.view.Address.NON_ADDRESS;
import static org.corfudb.runtime.view.Layout.LayoutStripe;
import static org.corfudb.runtime.view.Layout.ReplicationMode.CHAIN_REPLICATION;

public class RedundancyCalculatorTest extends LayoutBasedTestHelper implements TransferSegmentCreator {

    @Test
    public void testCreateStateListTrimMarkNotMoved() {
        RedundancyCalculator redundancyCalculator =
                new RedundancyCalculator("localhost");

        Layout layout = createNonPresentLayout();

        ImmutableList<MockedSegment> expected = ImmutableList.of(
                new MockedSegment(0L, 1L,
                        createStatus(NOT_TRANSFERRED, 0L, Optional.empty())),
                new MockedSegment(2L, 3L,
                        createStatus(NOT_TRANSFERRED, 0L, Optional.empty())));

        ImmutableList<TransferSegment> result = redundancyCalculator
                .createStateList(layout, -1L);

        assertThat(transformListToMock(result)).isEqualTo(expected);

        layout = createPresentLayout();

        expected = ImmutableList.of(
                new MockedSegment(0L, 1L,
                        createStatus(NOT_TRANSFERRED, 0L, Optional.empty())),
                new MockedSegment(2L, 3L,
                        createStatus(RESTORED, 2L, Optional.empty())));

        result = redundancyCalculator.createStateList(layout, -1L);

        assertThat(transformListToMock(result))
                .isEqualTo(expected);
    }

    @Test
    public void testCreateStateListTrimMarkIntersectsSegment() {
        RedundancyCalculator redundancyCalculator =
                new RedundancyCalculator("localhost");

        Layout layout = createNonPresentLayout();

        // node is not present anywhere, trim mark starts from the middle of a second segment ->
        // transfer half of second and third segments
        ImmutableList<MockedSegment> expected =
                ImmutableList.of(
                        new MockedSegment(3L, 3L,
                                createStatus(NOT_TRANSFERRED, 0L, Optional.empty())));


        List<TransferSegment> result = redundancyCalculator.createStateList(layout, 3L);

        assertThat(transformListToMock(result)).isEqualTo(expected);

        layout = createPresentLayout();

        expected = ImmutableList.of(
                new MockedSegment(3L, 3L,
                        createStatus(RESTORED, 1L, Optional.empty())));

        result = redundancyCalculator.createStateList(layout, 3L);

        assertThat(transformListToMock(result)).isEqualTo(expected);

    }

    @Test
    public void testSegmentContainsServer() {


        LayoutStripe stripe1 = new LayoutStripe(Arrays.asList("localhost", "A", "B"));
        LayoutStripe stripe2 = new LayoutStripe(Collections.singletonList("C"));
        LayoutStripe stripe3 = new LayoutStripe(Collections.singletonList("D"));

        LayoutSegment segment = new LayoutSegment(CHAIN_REPLICATION, 0L, 1L,
                Arrays.asList(stripe1, stripe2, stripe3));

        RedundancyCalculator calculator = new RedundancyCalculator("localhost");

        Assert.assertTrue(calculator.segmentContainsServer(segment, "localhost"));

        stripe1 = new LayoutStripe(Arrays.asList("A", "B"));
        segment = new LayoutSegment(CHAIN_REPLICATION, 0L, 1L,
                Arrays.asList(stripe1, stripe2, stripe3));
        Assert.assertFalse(calculator.segmentContainsServer(segment, "localhost"));
    }

    @Test
    public void testRequiresRedundancyRestoration() {
        LayoutStripe stripe0 = new LayoutStripe(Collections.singletonList("A"));
        LayoutStripe stripe1 = new LayoutStripe(Arrays.asList("A", "B"));
        LayoutStripe stripe2 = new LayoutStripe(Arrays.asList("A", "B", "localhost"));
        LayoutSegment segment0 = new LayoutSegment(CHAIN_REPLICATION, 0L, 2L,
                Collections.singletonList(stripe0));
        LayoutSegment segment1 = new LayoutSegment(CHAIN_REPLICATION, 3L, 6L,
                Collections.singletonList(stripe1));
        LayoutSegment segment2 = new LayoutSegment(CHAIN_REPLICATION, 6L, 9L,
                Collections.singletonList(stripe2));

        // Not required, since A is present
        Layout layout = createTestLayout(Arrays.asList(segment1, segment2));
        assertThat(RedundancyCalculator.canRestoreRedundancy(layout, "A"))
                .isFalse();
        // Not required, since the layout consists of only one segment
        layout = createTestLayout(Collections.singletonList(segment1));
        assertThat(RedundancyCalculator.canRestoreRedundancy(layout, "A"))
                .isFalse();
        // Required, since node B is not present at the beginning
        layout = createTestLayout(Arrays.asList(segment0, segment1, segment2));
        assertThat(RedundancyCalculator.canRestoreRedundancy(layout, "B"))
                .isTrue();
        // Required, since localhost is not present in the second last segment
        layout = createTestLayout(Arrays.asList(segment0, segment2));
        assertThat(RedundancyCalculator.canRestoreRedundancy(layout, "localhost"))
                .isTrue();


    }

    @Test
    public void testCreateStateListComplex() {

        LayoutStripe stripe1 = new LayoutStripe(Arrays.asList("localhost", "A", "B"));
        LayoutStripe stripe2 = new LayoutStripe(Collections.singletonList("C"));
        LayoutStripe stripe3 = new LayoutStripe(Collections.singletonList("D"));
        LayoutSegment segment1 = new LayoutSegment(CHAIN_REPLICATION, 0L, 21L,
                Arrays.asList(stripe1, stripe2, stripe3));

        LayoutStripe stripe11 = new LayoutStripe(Arrays.asList("C", "D"));
        LayoutStripe stripe22 = new LayoutStripe(Collections.singletonList("C"));
        LayoutStripe stripe33 = new LayoutStripe(Collections.singletonList("D"));
        LayoutSegment segment2 = new LayoutSegment(CHAIN_REPLICATION, 21L, 51L,
                Arrays.asList(stripe11, stripe22, stripe33));

        List<LayoutSegment> layoutSegments = Arrays.asList(segment1, segment2);

        Layout testLayout = createTestLayout(layoutSegments);

        RedundancyCalculator calculator = new RedundancyCalculator("localhost");

        ImmutableList<TransferSegment> transferSegments =
                calculator.createStateList(testLayout, -1L);

        MockedSegment presentSegment = new MockedSegment(0L,
                20L,
                TransferSegmentStatus.builder().segmentState(RESTORED).totalTransferred(21L).build());


        MockedSegment nonPresentSegment = new MockedSegment(21L,
                50L, TransferSegmentStatus.builder().segmentState(NOT_TRANSFERRED).totalTransferred(0L).build());

        Assert.assertTrue(transformListToMock(transferSegments).containsAll(Arrays.asList(presentSegment,
                nonPresentSegment)));

        TransferSegmentStatus presentSegmentStatus = presentSegment.status;
        assertThat(SegmentState.RESTORED)
                .isEqualTo(presentSegmentStatus.getSegmentState());

        TransferSegmentStatus nonPresentSegmentStatus = nonPresentSegment.status;

        assertThat(NOT_TRANSFERRED)
                .isEqualTo(nonPresentSegmentStatus.getSegmentState());
    }

    @Test
    public void testRestoreRedundancyForSegment() {
        TransferSegment segment = createTransferSegment(0L, 20L, RESTORED);

        LayoutStripe stripe1 = new LayoutStripe(Collections.singletonList("A"));
        LayoutStripe stripe2 = new LayoutStripe(Collections.singletonList("C"));
        LayoutStripe stripe3 = new LayoutStripe(Collections.singletonList("D"));
        LayoutSegment segment1 = new LayoutSegment(CHAIN_REPLICATION, 0L, 21L,
                Arrays.asList(stripe1, stripe2, stripe3));

        LayoutStripe stripe11 = new LayoutStripe(Arrays.asList("C", "D"));
        LayoutStripe stripe22 = new LayoutStripe(Collections.singletonList("C"));
        LayoutStripe stripe33 = new LayoutStripe(Collections.singletonList("D"));
        LayoutSegment segment2 = new LayoutSegment(CHAIN_REPLICATION, 21L, 51L,
                Arrays.asList(stripe11, stripe22, stripe33));

        List<LayoutSegment> layoutSegments = Arrays.asList(segment1, segment2);

        Layout testLayout = createTestLayout(layoutSegments);

        RedundancyCalculator calculator = new RedundancyCalculator("localhost");
        Layout layout = calculator.restoreRedundancyForSegment(segment, testLayout);
        assertThat(layout.getFirstSegment().getFirstStripe().getLogServers())
                .contains("localhost");

    }

    @Test
    public void testUpdateLayoutAfterRedundancyRestoration() {

        LayoutStripe stripe1 = new LayoutStripe(Collections.singletonList("A"));
        LayoutStripe stripe2 = new LayoutStripe(Collections.singletonList("C"));
        LayoutStripe stripe3 = new LayoutStripe(Collections.singletonList("D"));
        LayoutSegment segment1 = new LayoutSegment(CHAIN_REPLICATION, 0L, 21L,
                Arrays.asList(stripe1, stripe2, stripe3));

        LayoutStripe stripe11 = new LayoutStripe(Arrays.asList("C", "D"));
        LayoutStripe stripe22 = new LayoutStripe(Collections.singletonList("C"));
        LayoutStripe stripe33 = new LayoutStripe(Collections.singletonList("D"));
        LayoutSegment segment2 = new LayoutSegment(CHAIN_REPLICATION, 21L, 51L,
                Arrays.asList(stripe11, stripe22, stripe33));


        LayoutStripe stripe111 = new LayoutStripe(Arrays.asList("E", "F"));
        LayoutStripe stripe222 = new LayoutStripe(Collections.singletonList("G"));
        LayoutStripe stripe333 = new LayoutStripe(Collections.singletonList("H"));
        LayoutSegment segment3 = new LayoutSegment(CHAIN_REPLICATION, 51L, 61L,
                Arrays.asList(stripe111, stripe222, stripe333));

        List<LayoutSegment> layoutSegments = Arrays.asList(segment1, segment2, segment3);

        Layout testLayout = createTestLayout(layoutSegments);

        List<TransferSegment> transferSegments = Arrays.asList
                (createTransferSegment(0L, 20L, RESTORED),
                        createTransferSegment(21L, 50L, RESTORED),
                        createTransferSegment(51L, 60L, RESTORED));


        RedundancyCalculator calculator = new RedundancyCalculator("localhost");

        Layout consolidatedLayout =
                calculator.updateLayoutAfterRedundancyRestoration(transferSegments,
                        testLayout);

        assertThat(consolidatedLayout.getSegments().stream())
                .allMatch(segment -> segment.getFirstStripe().getLogServers().contains("localhost"));

    }


    @Test
    public void testMergeSegmentsMatchFailed() {
        RedundancyCalculator calculator = new RedundancyCalculator("localhost");

        // old segment had a failed transfer, segments match completely -> preserve
        TransferSegment oldSegment = createTransferSegment(0L, 5L, FAILED);

        TransferSegment newSegment = createTransferSegment(0L, 5L, NOT_TRANSFERRED);

        ImmutableList<TransferSegment> oldList = ImmutableList.of(oldSegment);
        ImmutableList<TransferSegment> newList = ImmutableList.of(newSegment);

        assertThat(calculator.mergeLists(oldList, newList).get(0).getStatus()
                .getSegmentState())
                .isEqualTo(FAILED);
    }


    @Test
    public void testMergeMapsMatchRestored() {

        RedundancyCalculator calculator = new RedundancyCalculator("localhost");

        TransferSegment oldSegment = createTransferSegment(0L, 5L, NOT_TRANSFERRED);

        TransferSegment newSegment = createTransferSegment(0L, 5L, RESTORED);

        ImmutableList<TransferSegment> oldList = ImmutableList.of(oldSegment);

        ImmutableList<TransferSegment> newList = ImmutableList.of(newSegment);


        assertThat(calculator.mergeLists(oldList, newList)
                .get(0)
                .getStatus()
                .getSegmentState()).isEqualTo(RESTORED);

    }

    @Test
    public void testMergeMapsNonOverlap() {

        RedundancyCalculator calculator = new RedundancyCalculator("localhost");

        TransferSegment segment1 = createTransferSegment(0L, 5L, NOT_TRANSFERRED);
        TransferSegment segment2 = createTransferSegment(6L, 10L, NOT_TRANSFERRED);

        assertThat(calculator.mergeLists(ImmutableList.of(segment1), ImmutableList.of(segment2)))
                .contains(segment1).contains(segment2);

    }

    @Test
    public void testMergeMapsNewIsSubset() {
        RedundancyCalculator calculator = new RedundancyCalculator("localhost");

        TransferSegment oldSegment = createTransferSegment(0L, 5L, NOT_TRANSFERRED);
        TransferSegment newSegment = createTransferSegment(3L, 5L, RESTORED);

        assertThat(calculator.mergeLists(ImmutableList.of(oldSegment), ImmutableList.of(newSegment))
                .get(0)
                .getStatus().getSegmentState()).isEqualTo(RESTORED);

        assertThat(calculator.mergeLists(ImmutableList.of(oldSegment), ImmutableList.of(newSegment))
                .get(0).getStartAddress()).isEqualTo(3L);

    }

    @Test
    public void testMergeMapsNewIsSuperSet() {
        RedundancyCalculator calculator = new RedundancyCalculator("localhost");
        // oldSegment is not transferred and new segment is restored
        TransferSegment oldSegment = createTransferSegment(0L, 5L, NOT_TRANSFERRED);
        TransferSegment newSegment = createTransferSegment(0L, 15L, RESTORED);

        assertThat(calculator.mergeLists(ImmutableList.of(oldSegment), ImmutableList.of(newSegment))
                .get(0)
                .getStatus()
                .getSegmentState()).isEqualTo(RESTORED);
    }

    @Test
    public void testMergeMapsNewIsPrefixedAndMerged() {
        RedundancyCalculator calculator = new RedundancyCalculator("localhost");

        // oldSegment is transferred and newsegment is restored
        TransferSegment oldSegment = createTransferSegment(0L, 5L, NOT_TRANSFERRED);
        TransferSegment newSegment = createTransferSegment(3L, 15L, RESTORED);

        assertThat(calculator.mergeLists(ImmutableList.of(oldSegment), ImmutableList.of(newSegment))
                .get(0)
                .getStatus()
                .getSegmentState()).isEqualTo(RESTORED);
    }

    @Test
    public void testMergeMapsNewMapIsEmpty() {
        RedundancyCalculator calculator = new RedundancyCalculator("localhost");

        // oldSegment is transferred and new segment is non existent

        TransferSegment oldSegment = createTransferSegment(0L, 5L, TRANSFERRED);

        assertThat(calculator.mergeLists(ImmutableList.of(oldSegment),
                ImmutableList.of()).get(0).getStatus()
                .getSegmentState()).isEqualTo(RESTORED);


    }

    @Test
    public void testMergeComplex() {
        // old list:
        // 0 -> 10 transferred, 11 -> 12 not transferred, 13 -> 16 failed, 18 -> 20 failed, 21 -> 25 restored
        // new list:
        // 5 -> 10 restored, 11 -> 12 not transferred, 13 -> 16 is not transferred, 18 -> 20 not transferred,
        // 21 -> 25 not transferred, 26 -> 30 restored
        // result:
        // 5 -> 10 restored, 11 -> 12 not transferred, 13 -> 16 failed, 18 -> 20 failed, 21 -> 25 restored, 26 -> 30 restored

        // Old list:
        List<TransferSegment> oldList = Arrays.asList(
                createTransferSegment(0L, 10L, TRANSFERRED),
                createTransferSegment(11L, 12L, NOT_TRANSFERRED),
                createTransferSegment(13L, 16L, FAILED),
                createTransferSegment(18L, 20L, FAILED),
                createTransferSegment(21L, 25L, RESTORED)
        );

        // New list:
        List<TransferSegment> newList = Arrays.asList(
                createTransferSegment(5L, 10L, RESTORED),
                createTransferSegment(11L, 12L, NOT_TRANSFERRED),
                createTransferSegment(13L, 16L, NOT_TRANSFERRED),
                createTransferSegment(18L, 20L, NOT_TRANSFERRED),
                createTransferSegment(21L, 25L, NOT_TRANSFERRED),
                createTransferSegment(26L, 30L, RESTORED)
        );

        RedundancyCalculator calculator = new RedundancyCalculator("localhost");
        ImmutableList<TransferSegment> resultList = calculator.mergeLists(oldList, newList);
        // Expected:
        List<TransferSegment> expected = Arrays.asList(
                createTransferSegment(5L, 10L, RESTORED),
                createTransferSegment(11L, 12L, NOT_TRANSFERRED),
                createTransferSegment(13L, 16L, FAILED),
                createTransferSegment(18L, 20L, FAILED),
                createTransferSegment(21L, 25L, RESTORED),
                createTransferSegment(26L, 30L, RESTORED)
        );

        assertThat(resultList).isEqualTo(expected);

    }

    @Test
    public void testCanMergeSegmentsOneSegment() {
        LayoutSegment segment = new LayoutSegment(CHAIN_REPLICATION, 0L, 1L,
                Arrays.asList(new LayoutStripe(Arrays.asList("localhost"))));
        assertThat(RedundancyCalculator
                .canMergeSegments(createTestLayout(Arrays.asList(segment)))).isFalse();
    }

    @Test
    public void testCanMergeSegmentsServersNonPresent() {
        LayoutSegment segment1 = new LayoutSegment(CHAIN_REPLICATION, 0L, 1L,
                Arrays.asList(new LayoutStripe(Arrays.asList("localhost", "B"))));

        LayoutSegment segment2 = new LayoutSegment(CHAIN_REPLICATION, 0L, 1L,
                Arrays.asList(new LayoutStripe(Arrays.asList("C", "A"))));
        assertThat(RedundancyCalculator
                .canMergeSegments(createTestLayout(Arrays.asList(segment1, segment2)))).isFalse();
    }

    @Test
    public void testCanMergeSegmentsServerNotPresent() {
        LayoutSegment segment1 = new LayoutSegment(CHAIN_REPLICATION, 0L, 1L,
                Arrays.asList(new LayoutStripe(Arrays.asList("localhost", "B"))));

        LayoutSegment segment2 = new LayoutSegment(CHAIN_REPLICATION, 0L, 1L,
                Arrays.asList(new LayoutStripe(Arrays.asList("C", "B", "localhost"))));
        assertThat(RedundancyCalculator
                .canMergeSegments(createTestLayout(Arrays.asList(segment1, segment2)))).isFalse();
    }

    @Test
    public void testCanMergeSegmentsDoMerge() {
        LayoutSegment segment1 = new LayoutSegment(CHAIN_REPLICATION, 0L, 1L,
                Arrays.asList(new LayoutStripe(Arrays.asList("localhost", "B"))));

        LayoutSegment segment2 = new LayoutSegment(CHAIN_REPLICATION, 0L, 1L,
                Arrays.asList(new LayoutStripe(Arrays.asList("B", "localhost"))));
        assertThat(RedundancyCalculator
                .canMergeSegments(createTestLayout(Arrays.asList(segment1, segment2)))).isTrue();

    }

    @Test
    public void testSegmentVerification() {
        TransferSegmentStatus status = TransferSegmentStatus.builder().build();

        // start can't be < 0L.
        assertThatThrownBy(() ->
                TransferSegment.builder()
                        .startAddress(NON_ADDRESS)
                        .endAddress(0)
                        .status(status)
                        .build()).isInstanceOf(IllegalStateException.class);

        // end can't be < 0L.
        assertThatThrownBy(() ->
                TransferSegment.builder()
                        .startAddress(0)
                        .endAddress(NON_ADDRESS)
                        .status(status)
                        .build()).isInstanceOf(IllegalStateException.class);

        // start can't be greater than end.
        assertThatThrownBy(() ->
                TransferSegment.builder()
                        .startAddress(3)
                        .endAddress(2)
                        .status(status)
                        .build()).isInstanceOf(IllegalStateException.class);

        // status should be defined.
        assertThatThrownBy(() ->
                TransferSegment.builder()
                        .startAddress(0L)
                        .endAddress(1L)
                        .status(null)
                        .build()).isInstanceOf(IllegalStateException.class);


    }

}