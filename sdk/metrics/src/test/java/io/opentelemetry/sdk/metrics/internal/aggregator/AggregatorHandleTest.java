/*
 * Copyright The OpenTelemetry Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package io.opentelemetry.sdk.metrics.internal.aggregator;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verifyNoMoreInteractions;

import com.google.common.util.concurrent.AtomicDouble;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.trace.SpanContext;
import io.opentelemetry.api.trace.TraceFlags;
import io.opentelemetry.api.trace.TraceState;
import io.opentelemetry.context.Context;
import io.opentelemetry.sdk.metrics.data.DoubleExemplarData;
import io.opentelemetry.sdk.metrics.internal.data.ImmutableDoubleExemplarData;
import io.opentelemetry.sdk.metrics.internal.exemplar.DoubleExemplarReservoir;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import javax.annotation.Nullable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class AggregatorHandleTest {

  @Mock DoubleExemplarReservoir reservoir;

  @Test
  void acquireMapped() {
    TestAggregatorHandle testAggregator = new TestAggregatorHandle(reservoir);
    assertThat(testAggregator.acquire()).isTrue();
    testAggregator.release();
    assertThat(testAggregator.acquire()).isTrue();
    assertThat(testAggregator.acquire()).isTrue();
    testAggregator.release();
    assertThat(testAggregator.acquire()).isTrue();
    testAggregator.release();
    testAggregator.release();
  }

  @Test
  void tryUnmap_AcquiredHandler() {
    TestAggregatorHandle testAggregator = new TestAggregatorHandle(reservoir);
    assertThat(testAggregator.acquire()).isTrue();
    assertThat(testAggregator.tryUnmap()).isFalse();
    testAggregator.release();
    // The aggregator is by default acquired, so need an extra release.
    assertThat(testAggregator.tryUnmap()).isFalse();
    testAggregator.release();
    assertThat(testAggregator.tryUnmap()).isTrue();
  }

  @Test
  void tryUnmap_AcquiredHandler_MultipleTimes() {
    TestAggregatorHandle testAggregator = new TestAggregatorHandle(reservoir);
    assertThat(testAggregator.acquire()).isTrue();
    assertThat(testAggregator.acquire()).isTrue();
    assertThat(testAggregator.acquire()).isTrue();
    assertThat(testAggregator.tryUnmap()).isFalse();
    testAggregator.release();
    assertThat(testAggregator.acquire()).isTrue();
    assertThat(testAggregator.tryUnmap()).isFalse();
    testAggregator.release();
    assertThat(testAggregator.tryUnmap()).isFalse();
    testAggregator.release();
    assertThat(testAggregator.tryUnmap()).isFalse();
    testAggregator.release();
    // The aggregator is by default acquired, so need an extra release.
    assertThat(testAggregator.tryUnmap()).isFalse();
    testAggregator.release();
    assertThat(testAggregator.tryUnmap()).isTrue();
  }

  @Test
  void bind_ThenUnmap_ThenTryToBind() {
    TestAggregatorHandle testAggregator = new TestAggregatorHandle(reservoir);
    testAggregator.release();
    assertThat(testAggregator.tryUnmap()).isTrue();
    assertThat(testAggregator.acquire()).isFalse();
    testAggregator.release();
  }

  @Test
  void testRecordings() {
    TestAggregatorHandle testAggregator = new TestAggregatorHandle(reservoir);

    testAggregator.recordLong(22);
    assertThat(testAggregator.recordedLong.get()).isEqualTo(22);
    assertThat(testAggregator.recordedDouble.get()).isEqualTo(0);

    testAggregator.accumulateThenReset(Attributes.empty());
    assertThat(testAggregator.recordedLong.get()).isEqualTo(0);
    assertThat(testAggregator.recordedDouble.get()).isEqualTo(0);

    testAggregator.recordDouble(33.55);
    assertThat(testAggregator.recordedLong.get()).isEqualTo(0);
    assertThat(testAggregator.recordedDouble.get()).isEqualTo(33.55);

    testAggregator.accumulateThenReset(Attributes.empty());
    assertThat(testAggregator.recordedLong.get()).isEqualTo(0);
    assertThat(testAggregator.recordedDouble.get()).isEqualTo(0);
  }

  @Test
  void testOfferMeasurementLongToExemplar() {
    TestAggregatorHandle testAggregator = new TestAggregatorHandle(reservoir);
    Attributes attributes = Attributes.builder().put("test", "value").build();
    Context context = Context.root();
    testAggregator.recordLong(1L, attributes, context);
    // Since double reservoir, long value is not offered.
    verifyNoMoreInteractions(reservoir);
  }

  @Test
  void testOfferMeasurementDoubleToExemplar() {
    TestAggregatorHandle testAggregator = new TestAggregatorHandle(reservoir);
    Attributes attributes = Attributes.builder().put("test", "value").build();
    Context context = Context.root();
    testAggregator.recordDouble(1.0d, attributes, context);
    Mockito.verify(reservoir).offerMeasurement(1.0d, attributes, context);
  }

  @Test
  void testGenerateExemplarsOnCollect() {
    TestAggregatorHandle testAggregator = new TestAggregatorHandle(reservoir);
    Attributes attributes = Attributes.builder().put("test", "value").build();
    DoubleExemplarData result =
        ImmutableDoubleExemplarData.create(
            attributes,
            2L,
            SpanContext.create(
                "00000000000000000000000000000001",
                "0000000000000002",
                TraceFlags.getDefault(),
                TraceState.getDefault()),
            1);
    // We need to first record a value so that collect and reset does something.
    testAggregator.recordDouble(1.0, Attributes.empty(), Context.root());
    Mockito.when(reservoir.collectAndReset(attributes))
        .thenReturn(Collections.singletonList(result));
    testAggregator.accumulateThenReset(attributes);
    assertThat(testAggregator.recordedExemplars.get()).containsExactly(result);
  }

  private static class TestAggregatorHandle extends AggregatorHandle<Void, DoubleExemplarData> {
    final AtomicLong recordedLong = new AtomicLong();
    final AtomicDouble recordedDouble = new AtomicDouble();
    final AtomicReference<List<DoubleExemplarData>> recordedExemplars = new AtomicReference<>();

    TestAggregatorHandle(DoubleExemplarReservoir reservoir) {
      super(reservoir);
    }

    @Nullable
    @Override
    protected Void doAccumulateThenReset(List<DoubleExemplarData> exemplars) {
      recordedLong.set(0);
      recordedDouble.set(0);
      recordedExemplars.set(exemplars);
      return null;
    }

    @Override
    protected void doRecordLong(long value) {
      recordedLong.set(value);
    }

    @Override
    protected void doRecordDouble(double value) {
      recordedDouble.set(value);
    }
  }
}
