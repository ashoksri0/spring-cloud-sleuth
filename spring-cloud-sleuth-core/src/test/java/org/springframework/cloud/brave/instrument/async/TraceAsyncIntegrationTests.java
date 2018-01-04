
package org.springframework.cloud.brave.instrument.async;

import java.util.AbstractMap;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import brave.Span;
import brave.Tracer;
import brave.Tracing;
import brave.sampler.Sampler;
import org.awaitility.Awaitility;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.cloud.brave.SpanName;
import org.springframework.cloud.brave.instrument.DefaultTestAutoConfiguration;
import org.springframework.cloud.brave.util.ArrayListSpanReporter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.Async;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.test.context.junit4.SpringRunner;

import static java.util.concurrent.TimeUnit.SECONDS;
import static org.springframework.cloud.sleuth.assertions.SleuthAssertions.then;

@RunWith(SpringRunner.class)
@SpringBootTest(classes = {
		TraceAsyncIntegrationTests.TraceAsyncITestConfiguration.class })
public class TraceAsyncIntegrationTests {

	@Autowired
	ClassPerformingAsyncLogic classPerformingAsyncLogic;
	@Autowired 
	Tracing tracer;
	@Autowired
	ArrayListSpanReporter reporter;

	@Before
	public void cleanup() {
		this.classPerformingAsyncLogic.clear();
		this.reporter.clear();
	}

	@Test
	public void should_set_span_on_an_async_annotated_method() {
		whenAsyncProcessingTakesPlace();

		thenANewAsyncSpanGetsCreated();
	}

	@Test
	public void should_set_span_with_custom_method_on_an_async_annotated_method() {
		whenAsyncProcessingTakesPlaceWithCustomSpanName();

		thenAsyncSpanHasCustomName();
	}

	@Test
	public void should_continue_a_span_on_an_async_annotated_method() {
		Span span = givenASpanInCurrentThread();

		try (Tracer.SpanInScope ws = this.tracer.tracer().withSpanInScope(span.start())) {
			whenAsyncProcessingTakesPlace();

			thenTraceIdIsPassedFromTheCurrentThreadToTheAsyncOne(span);
		} finally {
			span.finish();
		}
	}

	@Test
	public void should_continue_a_span_with_custom_method_on_an_async_annotated_method() {
		Span span = givenASpanInCurrentThread();

		try (Tracer.SpanInScope ws = this.tracer.tracer().withSpanInScope(span.start())) {
			whenAsyncProcessingTakesPlaceWithCustomSpanName();

			thenTraceIdIsPassedFromTheCurrentThreadToTheAsyncOneAndSpanHasCustomName(span);
		} finally {
			span.finish();
		}
	}

	private Span givenASpanInCurrentThread() {
		return this.tracer.tracer().nextSpan().name("http:existing");
	}

	private void whenAsyncProcessingTakesPlace() {
		this.classPerformingAsyncLogic.invokeAsynchronousLogic();
	}

	private void whenAsyncProcessingTakesPlaceWithCustomSpanName() {
		this.classPerformingAsyncLogic.customNameInvokeAsynchronousLogic();
	}

	private void thenTraceIdIsPassedFromTheCurrentThreadToTheAsyncOne(final Span span) {
		Awaitility.await().atMost(5, SECONDS).untilAsserted(
				() -> {
					Span asyncSpan = TraceAsyncIntegrationTests.this.classPerformingAsyncLogic.getSpan();
					then(asyncSpan.context().traceId()).isEqualTo(span.context().traceId());
					List<zipkin2.Span> spans = TraceAsyncIntegrationTests.this.reporter
							.getSpans();
					then(spans).hasSize(1);
					zipkin2.Span reportedAsyncSpan = spans.get(0);
					then(reportedAsyncSpan.traceId()).isEqualTo(span.context().traceIdString());
					then(reportedAsyncSpan.name()).isEqualTo("invoke-asynchronous-logic");
					then(reportedAsyncSpan.tags())
							.contains(new AbstractMap.SimpleEntry<>("class", "ClassPerformingAsyncLogic"))
							.contains(new AbstractMap.SimpleEntry<>("method", "invokeAsynchronousLogic"));
				});
	}

	private void thenANewAsyncSpanGetsCreated() {
		Awaitility.await().atMost(5, SECONDS).untilAsserted(
				() -> {
					List<zipkin2.Span> spans = TraceAsyncIntegrationTests.this.reporter
							.getSpans();
					then(spans).hasSize(1);
					zipkin2.Span reportedAsyncSpan = spans.get(0);
					then(reportedAsyncSpan.name()).isEqualTo("invoke-asynchronous-logic");
					then(reportedAsyncSpan.tags())
							.contains(new AbstractMap.SimpleEntry<>("class", "ClassPerformingAsyncLogic"))
							.contains(new AbstractMap.SimpleEntry<>("method", "invokeAsynchronousLogic"));
				});
	}

	private void thenTraceIdIsPassedFromTheCurrentThreadToTheAsyncOneAndSpanHasCustomName(final Span span) {
		Awaitility.await().atMost(5, SECONDS).untilAsserted(
				() -> {
					Span asyncSpan = TraceAsyncIntegrationTests.this.classPerformingAsyncLogic.getSpan();
					then(asyncSpan.context().traceId()).isEqualTo(span.context().traceId());
					List<zipkin2.Span> spans = TraceAsyncIntegrationTests.this.reporter
							.getSpans();
					then(spans).hasSize(1);
					zipkin2.Span reportedAsyncSpan = spans.get(0);
					then(reportedAsyncSpan.traceId()).isEqualTo(span.context().traceIdString());
					then(reportedAsyncSpan.name()).isEqualTo("foo");
					then(reportedAsyncSpan.tags())
							.contains(new AbstractMap.SimpleEntry<>("class", "ClassPerformingAsyncLogic"))
							.contains(new AbstractMap.SimpleEntry<>("method", "customNameInvokeAsynchronousLogic"));
				});
	}

	private void thenAsyncSpanHasCustomName() {
		Awaitility.await().atMost(5, SECONDS).untilAsserted(
				() -> {
					List<zipkin2.Span> spans = TraceAsyncIntegrationTests.this.reporter
							.getSpans();
					then(spans).hasSize(1);
					zipkin2.Span reportedAsyncSpan = spans.get(0);
					then(reportedAsyncSpan.name()).isEqualTo("foo");
					then(reportedAsyncSpan.tags())
							.contains(new AbstractMap.SimpleEntry<>("class", "ClassPerformingAsyncLogic"))
							.contains(new AbstractMap.SimpleEntry<>("method", "customNameInvokeAsynchronousLogic"));
				});
	}

	@DefaultTestAutoConfiguration
	@EnableAsync
	@Configuration
	static class TraceAsyncITestConfiguration {

		@Bean
		ClassPerformingAsyncLogic asyncClass(Tracing tracing) {
			return new ClassPerformingAsyncLogic(tracing);
		}

		@Bean Sampler defaultSampler() {
			return Sampler.ALWAYS_SAMPLE;
		}

		@Bean
		ArrayListSpanReporter reporter() {
			return new ArrayListSpanReporter();
		}

	}

	static class ClassPerformingAsyncLogic {

		AtomicReference<Span> span = new AtomicReference<>();

		private final Tracing tracing;

		ClassPerformingAsyncLogic(Tracing tracing) {
			this.tracing = tracing;
		}

		@Async
		public void invokeAsynchronousLogic() {
			this.span.set(this.tracing.tracer().currentSpan());
		}

		@Async
		@SpanName("foo")
		public void customNameInvokeAsynchronousLogic() {
			this.span.set(this.tracing.tracer().currentSpan());
		}

		public Span getSpan() {
			return this.span.get();
		}

		public void clear() {
			this.span.set(null);
		}
	}
}
