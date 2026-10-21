package com.ayush.shortener.analytics;

import java.time.Clock;
import java.util.List;
import java.util.Optional;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.mock.web.MockHttpServletRequest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

class ClickRecorderTest {

    ClickWriter writer = mock(ClickWriter.class);
    /** Local-only: the stream never takes a click, so everything goes through the in-process queue. */
    ClickPublisher noStream = (click, onFailure) -> false;
    SimpleMeterRegistry metrics = new SimpleMeterRegistry();
    ClickEnricher enricher = new ClickEnricher(new UserAgentClassifier(), ip -> Optional.empty(),
            new VisitorHasher(TestProps.props(10, 10)));

    ClickRecorder recorder(int capacity, int batch) {
        return recorder(capacity, batch, noStream);
    }

    ClickRecorder recorder(int capacity, int batch, ClickPublisher publisher) {
        return new ClickRecorder(TestProps.props(capacity, batch), enricher, publisher, writer, Clock.systemUTC(), metrics);
    }

    static MockHttpServletRequest get() {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/linkly-abc");
        request.setRemoteAddr("203.0.113.5");
        request.addHeader("User-Agent", "Mozilla/5.0 (X11; Linux x86_64; rv:121.0) Gecko/20100101 Firefox/121.0");
        return request;
    }

    double count(String name) {
        return metrics.counter(name).count();
    }

    @BeforeEach
    void writerStoresEverythingItIsGiven() {
        // a real writer returns how many rows were new; here every row is
        org.mockito.Mockito.when(writer.write(anyList())).thenAnswer(inv -> ((List<?>) inv.getArgument(0)).size());
    }

    @Test
    void recordNeverTouchesTheDatabase() {
        recorder(10, 5).record(1, get());
        verify(writer, never()).write(anyList());
    }

    @Test
    void onlyGetRequestsAreCounted() {
        ClickRecorder recorder = recorder(10, 5);
        recorder.record(1, new MockHttpServletRequest("HEAD", "/x"));
        recorder.record(1, new MockHttpServletRequest("OPTIONS", "/x"));
        recorder.record(1, get());

        assertThat(recorder.flush()).isEqualTo(1);
        assertThat(count("clicks.recorded")).isEqualTo(1);
    }

    @Test
    void dropsClicksWhenTheQueueIsFullInsteadOfBlocking() {
        ClickRecorder recorder = recorder(3, 10);
        for (int i = 0; i < 10; i++) {
            recorder.record(1, get());
        }
        assertThat(count("clicks.recorded")).isEqualTo(3);
        assertThat(count("clicks.dropped")).isEqualTo(7);
        assertThat(recorder.flush()).isEqualTo(3);
    }

    @Test
    void flushWritesInBatchesOfTheConfiguredSize() {
        ClickRecorder recorder = recorder(100, 2);
        for (int i = 0; i < 5; i++) {
            recorder.record(i, get());
        }
        assertThat(recorder.flush()).isEqualTo(5);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<ClickRow>> batches = ArgumentCaptor.forClass(List.class);
        verify(writer, times(3)).write(batches.capture());
        assertThat(batches.getAllValues()).extracting(List::size).containsExactly(2, 2, 1);
        assertThat(count("clicks.written")).isEqualTo(5);
    }

    @Test
    void flushOnAnEmptyQueueDoesNothing() {
        assertThat(recorder(10, 5).flush()).isZero();
        verify(writer, never()).write(anyList());
    }

    @Test
    void aFailedBatchIsRetriedAndSucceedsWithoutLosingClicks() {
        ClickRecorder recorder = recorder(10, 5);
        recorder.record(1, get());
        recorder.record(1, get());
        doThrow(new RuntimeException("db down")).doReturn(2).when(writer).write(anyList());

        assertThat(recorder.flush()).isZero();          // first attempt fails
        assertThat(recorder.flush()).isZero();          // retry of the pending batch succeeds (nothing new queued)
        verify(writer, times(2)).write(anyList());
        assertThat(count("clicks.written")).isEqualTo(2);
        assertThat(count("clicks.lost")).isZero();
    }

    @Test
    void givesUpOnABatchAfterThreeFailuresAndKeepsGoing() {
        ClickRecorder recorder = recorder(10, 5);
        recorder.record(1, get());
        doThrow(new RuntimeException("poison")).when(writer).write(anyList());

        recorder.flush();
        recorder.flush();
        recorder.flush();
        assertThat(count("clicks.lost")).isEqualTo(1);

        // The poison batch is gone, so later clicks are not stuck behind it.
        org.mockito.Mockito.reset(writer);
        recorder.record(2, get());
        assertThat(recorder.flush()).isEqualTo(1);
    }

    @Test
    void aClickThatCannotBeEnrichedDoesNotSinkItsBatch() {
        ClickEnricher flaky = mock(ClickEnricher.class);
        org.mockito.Mockito.when(flaky.capture(org.mockito.ArgumentMatchers.anyLong(), org.mockito.ArgumentMatchers.any(),
                        org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any()))
                .thenAnswer(inv -> enricher.capture(inv.getArgument(0), inv.getArgument(1), inv.getArgument(2), inv.getArgument(3), inv.getArgument(4)));
        org.mockito.Mockito.when(flaky.finish(org.mockito.ArgumentMatchers.argThat(c -> c != null && c.linkId() == 13)))
                .thenThrow(new IllegalStateException("bad row"));
        org.mockito.Mockito.when(flaky.finish(org.mockito.ArgumentMatchers.argThat(c -> c != null && c.linkId() != 13)))
                .thenAnswer(inv -> enricher.finish(inv.getArgument(0)));
        ClickRecorder recorder = new ClickRecorder(TestProps.props(10, 10), flaky, noStream, writer, Clock.systemUTC(), metrics);

        recorder.record(1, get());
        recorder.record(13, get());
        recorder.record(2, get());

        assertThat(recorder.flush()).isEqualTo(2);
        assertThat(count("clicks.lost")).isEqualTo(1);
    }

    @Test
    void aClickTheStreamTakesIsNotAlsoKeptInMemory() {
        ClickRecorder recorder = recorder(10, 5, (click, onFailure) -> true); // the stream accepts everything
        recorder.record(1, get());
        assertThat(count("clicks.recorded")).isEqualTo(1);
        assertThat(recorder.flush()).isZero();
        verify(writer, never()).write(anyList());
    }

    @Test
    void aClickWhoseStreamSendFailsLaterIsKeptInMemoryInstead() {
        java.util.concurrent.atomic.AtomicReference<Runnable> failure = new java.util.concurrent.atomic.AtomicReference<>();
        ClickRecorder recorder = recorder(10, 5, (click, onFailure) -> {
            failure.set(onFailure);
            return true;
        });
        recorder.record(1, get());
        assertThat(recorder.flush()).isZero();          // nothing local yet: the stream has it

        failure.get().run();                            // the asynchronous send came back with an error
        assertThat(recorder.flush()).isEqualTo(1);      // ...and now the in-process queue writes it
    }

    @Test
    void aClickTheStreamRefusesGoesStraightToMemory() {
        ClickRecorder recorder = recorder(10, 5, (click, onFailure) -> false);
        recorder.record(1, get());
        assertThat(recorder.flush()).isEqualTo(1);
    }

    @Test
    void clicksAlreadyStoredAreCountedAsDuplicatesNotAsWritten() {
        org.mockito.Mockito.when(writer.write(anyList())).thenReturn(1); // of 3 rows only 1 was new
        ClickRecorder recorder = recorder(10, 5);
        recorder.record(1, get());
        recorder.record(1, get());
        recorder.record(1, get());
        recorder.flush();
        assertThat(count("clicks.written")).isEqualTo(1);
        assertThat(count("clicks.duplicates")).isEqualTo(2);
    }

    @Test
    void shutdownFlushesWhatIsStillQueued() {
        ClickRecorder recorder = recorder(10, 5);
        recorder.record(1, get());
        recorder.flushOnShutdown();
        verify(writer, times(1)).write(anyList());
    }
}
