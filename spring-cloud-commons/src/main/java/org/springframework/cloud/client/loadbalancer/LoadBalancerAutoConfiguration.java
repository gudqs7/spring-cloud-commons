/*
 * Copyright 2012-2020 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.springframework.cloud.client.loadbalancer;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.AnyNestedCondition;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.cloud.client.ServiceInstance;
import org.springframework.cloud.client.loadbalancer.reactive.ReactiveLoadBalancer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Conditional;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.ClientHttpRequestInterceptor;
import org.springframework.retry.support.RetryTemplate;
import org.springframework.web.client.RestTemplate;

/**
 * Auto-configuration for blocking client-side load balancing.
 *
 * @author Spencer Gibb
 * @author Dave Syer
 * @author Will Tran
 * @author Gang Li
 * @author Olga Maciaszek-Sharma
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnClass(RestTemplate.class)
@ConditionalOnBean(LoadBalancerClient.class)
@EnableConfigurationProperties(LoadBalancerProperties.class)
public class LoadBalancerAutoConfiguration {
	/*
	 * 此类作用总结: 为用户自定义(如配置类中写了个@Bean + return new RestTemplate() 这种形式)的 RestTemplate
	 * 添加一个拦截器, 在请求执行前进行拦截, 然后将请求数据的 host 作为 serviceId, 接着使用某个具体的 LoadBalancerClient 实现类
	 * 调用其方法获取真实的 url. 若对应存在多个 url, 由其算法策略决定如何选择.
	 *
	 * 步骤:
	 * 1.@Bean 加入一个 LoadBalancerRequestFactory, 并且带有用户自定义的 transformers(作用: 对选取真实 url 后的请求对象进行干预)
	 * 2.@Bean 加入一个 LoadBalancerClient, 其作用是, 根据 serviceId 获取/选取真实 url, 以及执行请求
	 * 3.@Bean 加入一个 LoadBalancerInterceptor, 即核心拦截器. 逻辑是: 获取 host, 调用 LoadBalancerRequestFactory 生成请求, 用 LoadBalancerClient 执行.
	 * 4.@Bean 加入一个 RestTemplateCustomizer, 其作用是: 为给定的 RestTemplate 添加一个 LoadBalancerInterceptor.
	 * 5.@Bean 加入一个 SmartInitializingSingleton, 作用是单例都加载后触发回调, 回调代码为:
	 *      遍历所有的 RestTemplateCustomizer 和 restTemplates, 用 RestTemplateCustomizer 对 RestTemplate 做设置. 包括(4)刚刚加入的那个.
	 *
	 */

	/**
	 * 从容器中获取用户注入的 RestTemplate, 用于为其添加拦截器
	 */
	@LoadBalanced
	@Autowired(required = false)
	private List<RestTemplate> restTemplates = Collections.emptyList();

	/**
	 * 从容器中注入用户定义的 LoadBalancerRequestTransformer
	 */
	@Autowired(required = false)
	private List<LoadBalancerRequestTransformer> transformers = Collections.emptyList();

	/**
	 * 写一个回调留到 bean 都加载了再触发 (Spring 会处理? 看源码没看到... 可能漏了一些 BeanPostProcessor, 回头再找找)
	 * 这个回调的作用是, 将用户加入的 RestTemplate 添加拦截器
	 * @param restTemplateCustomizers 为 RestTemplate 添加拦截器
	 * @return
	 */
	@Bean
	public SmartInitializingSingleton loadBalancedRestTemplateInitializerDeprecated(
			final ObjectProvider<List<RestTemplateCustomizer>> restTemplateCustomizers) {
		return () -> restTemplateCustomizers.ifAvailable(customizers -> {
			for (RestTemplate restTemplate : LoadBalancerAutoConfiguration.this.restTemplates) {
				for (RestTemplateCustomizer customizer : customizers) {
					customizer.customize(restTemplate);
				}
			}
		});
	}

	@Bean
	@ConditionalOnMissingBean
	public LoadBalancerRequestFactory loadBalancerRequestFactory(LoadBalancerClient loadBalancerClient) {
		// 创建一个带 this.transformers 的 LoadBalancerRequestFactory 的 bean, 以便其他 @Bean 使用
		return new LoadBalancerRequestFactory(loadBalancerClient, this.transformers);
	}

	@Configuration(proxyBeanMethods = false)
	@Conditional(RetryMissingOrDisabledCondition.class)
	static class LoadBalancerInterceptorConfig {

		@Bean
		public LoadBalancerInterceptor loadBalancerInterceptor(LoadBalancerClient loadBalancerClient,
				LoadBalancerRequestFactory requestFactory) {
			// 生产一个 请求 拦截器, 其需要 LoadBalancerRequestFactory 和 LoadBalancerClient
			// LoadBalancerRequestFactory: 工厂
			// LoadBalancerClient
			return new LoadBalancerInterceptor(loadBalancerClient, requestFactory);
		}

		@Bean
		@ConditionalOnMissingBean
		public RestTemplateCustomizer restTemplateCustomizer(final LoadBalancerInterceptor loadBalancerInterceptor) {
			// 是一个 RestTemplateCustomizer, 作用, 添加核心拦截器.
			// 将 LoadBalancerInterceptor 这个拦截器加入到 restTemplate 对象中, 用于拦截请求, 修改请求 host 为具体 ip
			return restTemplate -> {
				List<ClientHttpRequestInterceptor> list = new ArrayList<>(restTemplate.getInterceptors());
				list.add(loadBalancerInterceptor);
				restTemplate.setInterceptors(list);
			};
		}

	}

	private static class RetryMissingOrDisabledCondition extends AnyNestedCondition {

		RetryMissingOrDisabledCondition() {
			super(ConfigurationPhase.REGISTER_BEAN);
		}

		@ConditionalOnMissingClass("org.springframework.retry.support.RetryTemplate")
		static class RetryTemplateMissing {

		}

		@ConditionalOnProperty(value = "spring.cloud.loadbalancer.retry.enabled", havingValue = "false")
		static class RetryDisabled {

		}

	}

	/**
	 * Auto configuration for retry mechanism.
	 */
	@Configuration(proxyBeanMethods = false)
	@ConditionalOnClass(RetryTemplate.class)
	public static class RetryAutoConfiguration {

		@Bean
		@ConditionalOnMissingBean
		public LoadBalancedRetryFactory loadBalancedRetryFactory() {
			return new LoadBalancedRetryFactory() {
			};
		}

	}

	/**
	 * Auto configuration for retry intercepting mechanism.
	 */
	@Configuration(proxyBeanMethods = false)
	@ConditionalOnClass(RetryTemplate.class)
	@ConditionalOnBean(ReactiveLoadBalancer.Factory.class)
	@ConditionalOnProperty(value = "spring.cloud.loadbalancer.retry.enabled", matchIfMissing = true)
	public static class RetryInterceptorAutoConfiguration {

		@Bean
		@ConditionalOnMissingBean
		public RetryLoadBalancerInterceptor loadBalancerInterceptor(LoadBalancerClient loadBalancerClient,
				LoadBalancerProperties properties, LoadBalancerRequestFactory requestFactory,
				LoadBalancedRetryFactory loadBalancedRetryFactory,
				ReactiveLoadBalancer.Factory<ServiceInstance> loadBalancerFactory) {
			return new RetryLoadBalancerInterceptor(loadBalancerClient, properties, requestFactory,
					loadBalancedRetryFactory, loadBalancerFactory);
		}

		@Bean
		@ConditionalOnMissingBean
		public RestTemplateCustomizer restTemplateCustomizer(
				final RetryLoadBalancerInterceptor loadBalancerInterceptor) {
			return restTemplate -> {
				List<ClientHttpRequestInterceptor> list = new ArrayList<>(restTemplate.getInterceptors());
				list.add(loadBalancerInterceptor);
				restTemplate.setInterceptors(list);
			};
		}

	}

}
