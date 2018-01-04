/*
 * Copyright 2013-2016 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.springframework.cloud.brave.instrument.web.client;

import java.io.IOException;
import java.net.URI;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import brave.Span;
import brave.Tracer;
import brave.Tracing;
import brave.sampler.Sampler;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.awaitility.Awaitility;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.springframework.beans.factory.BeanFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.web.server.LocalServerPort;
import org.springframework.cloud.brave.instrument.async.LazyTraceExecutor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.task.SimpleAsyncTaskExecutor;
import org.springframework.http.HttpMethod;
import org.springframework.http.client.AsyncClientHttpRequest;
import org.springframework.http.client.AsyncClientHttpRequestFactory;
import org.springframework.http.client.ClientHttpRequest;
import org.springframework.http.client.ClientHttpRequestFactory;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.scheduling.annotation.AsyncConfigurer;
import org.springframework.scheduling.annotation.AsyncConfigurerSupport;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.test.context.junit4.SpringRunner;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.client.AsyncRestTemplate;

import static org.assertj.core.api.BDDAssertions.then;

/**
 * @author Marcin Grzejszczak
 */
@RunWith(SpringRunner.class)
@SpringBootTest(
		classes = { MultipleAsyncRestTemplateTests.Config.class,
				MultipleAsyncRestTemplateTests.CustomExecutorConfig.class,
				MultipleAsyncRestTemplateTests.ControllerConfig.class },
		webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
public class MultipleAsyncRestTemplateTests {

	private static final Log log = LogFactory.getLog(MultipleAsyncRestTemplateTests.class);

	@Autowired @Qualifier("customAsyncRestTemplate") AsyncRestTemplate asyncRestTemplate;
	@Autowired AsyncConfigurer executor;
	@Autowired Tracing tracing;
	@LocalServerPort int port;

	@Test
	public void should_start_context_with_custom_async_client() throws Exception {
		then(this.asyncRestTemplate).isNotNull();
	}

	@Test
	public void should_pass_tracing_context_with_custom_async_client() throws Exception {
		Span span = this.tracing.tracer().nextSpan().name("foo");
		try (Tracer.SpanInScope ws = this.tracing.tracer().withSpanInScope(span.start())) {
			String result = this.asyncRestTemplate.getForEntity("http://localhost:"
					+ port + "/foo", String.class).get().getBody();
			then(span.context().traceIdString()).isEqualTo(result);
		} finally {
			span.finish();
		}
	}

	@Test
	public void should_start_context_with_custom_executor() throws Exception {
		then(this.executor).isNotNull();
		then(this.executor.getAsyncExecutor()).isInstanceOf(LazyTraceExecutor.class);
	}

	@Test
	public void should_inject_traced_executor_that_passes_tracing_context() throws Exception {
		Span span = this.tracing.tracer().nextSpan().name("foo");
		AtomicBoolean executed = new AtomicBoolean(false);
		try (Tracer.SpanInScope ws = this.tracing.tracer().withSpanInScope(span.start())) {
			log.info("Hello from test");
			this.executor.getAsyncExecutor().execute(new Runnable() {
				@Override public void run() {
					then(Tracing.currentTracer().currentSpan().context().traceId()).isEqualTo(span.context().traceId());
					executed.set(true);
					log.info("Hello from runnable");
				}
			});
		} finally {
			span.finish();
		}

		Awaitility.await().atMost(5L, TimeUnit.SECONDS)
				.untilAsserted(() -> {
					then(executed.get()).isTrue();
				});
	}

	//tag::custom_async_rest_template[]
	@Configuration
	@EnableAutoConfiguration
	static class Config {

		@Bean(name = "customAsyncRestTemplate")
		public AsyncRestTemplate traceAsyncRestTemplate() {
			return new AsyncRestTemplate(asyncClientFactory(), clientHttpRequestFactory());
		}

		private ClientHttpRequestFactory clientHttpRequestFactory() {
			ClientHttpRequestFactory clientHttpRequestFactory = new CustomClientHttpRequestFactory();
			//CUSTOMIZE HERE
			return clientHttpRequestFactory;
		}

		private AsyncClientHttpRequestFactory asyncClientFactory() {
			AsyncClientHttpRequestFactory factory = new CustomAsyncClientHttpRequestFactory();
			//CUSTOMIZE HERE
			return factory;
		}
	}
	//end::custom_async_rest_template[]

	//tag::custom_executor[]
	@Configuration
	@EnableAutoConfiguration
	@EnableAsync
	static class CustomExecutorConfig extends AsyncConfigurerSupport {

		@Autowired BeanFactory beanFactory;

		@Override public Executor getAsyncExecutor() {
			ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
			// CUSTOMIZE HERE
			executor.setCorePoolSize(7);
			executor.setMaxPoolSize(42);
			executor.setQueueCapacity(11);
			executor.setThreadNamePrefix("MyExecutor-");
			// DON'T FORGET TO INITIALIZE
			executor.initialize();
			return new LazyTraceExecutor(this.beanFactory, executor);
		}
	}
	//end::custom_executor[]

	@Configuration
	static class ControllerConfig {
		@Bean
		MyRestController myRestController(Tracing tracing) {
			return new MyRestController(tracing);
		}

		@Bean Sampler sampler() {
			return Sampler.ALWAYS_SAMPLE;
		}
	}
}

class CustomClientHttpRequestFactory implements ClientHttpRequestFactory {

	private final SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();

	@Override public ClientHttpRequest createRequest(URI uri, HttpMethod httpMethod)
			throws IOException {
		return this.factory.createRequest(uri, httpMethod);
	}
}

class CustomAsyncClientHttpRequestFactory implements AsyncClientHttpRequestFactory {

	private final SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();

	public CustomAsyncClientHttpRequestFactory() {
		this.factory.setTaskExecutor(new SimpleAsyncTaskExecutor());
	}

	@Override
	public AsyncClientHttpRequest createAsyncRequest(URI uri, HttpMethod httpMethod)
			throws IOException {
		return this.factory.createAsyncRequest(uri, httpMethod);
	}
}

@RestController
class MyRestController {

	private final Tracing tracing;

	MyRestController(Tracing tracing) {
		this.tracing = tracing;
	}

	@RequestMapping("/foo")
	String foo() {
		return this.tracing.tracer().currentSpan().context().traceIdString();
	}
}