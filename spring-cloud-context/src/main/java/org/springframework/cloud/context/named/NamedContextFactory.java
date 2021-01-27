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

package org.springframework.cloud.context.named;

import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.beans.BeansException;
import org.springframework.beans.factory.BeanFactoryUtils;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.NoSuchBeanDefinitionException;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.context.PropertyPlaceholderAutoConfiguration;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationContextAware;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.core.ResolvableType;
import org.springframework.core.env.MapPropertySource;

/**
 * Creates a set of child contexts that allows a set of Specifications to define the beans
 * in each child context.
 *
 * Ported from spring-cloud-netflix FeignClientFactory and SpringClientFactory
 *
 * @param <C> specification
 * @author Spencer Gibb
 * @author Dave Syer
 */
// TODO: add javadoc
public abstract class NamedContextFactory<C extends NamedContextFactory.Specification>
		implements DisposableBean, ApplicationContextAware {

	/*
	   默认为 loadbalancer
	 */
	private final String propertySourceName;

	/*
	   默认为 loadbalancer.client.name
	 */
	private final String propertyName;

	private Map<String, AnnotationConfigApplicationContext> contexts = new ConcurrentHashMap<>();

	private Map<String, C> configurations = new ConcurrentHashMap<>();

	private ApplicationContext parent;

	/**
	 * 默认为 LoadBalancerClientConfiguration.class
	 */
	private Class<?> defaultConfigType;

	public NamedContextFactory(Class<?> defaultConfigType, String propertySourceName, String propertyName) {
		this.defaultConfigType = defaultConfigType;
		this.propertySourceName = propertySourceName;
		this.propertyName = propertyName;
	}

	@Override
	public void setApplicationContext(ApplicationContext parent) throws BeansException {
		this.parent = parent;
	}

	public void setConfigurations(List<C> configurations) {
		for (C client : configurations) {
			this.configurations.put(client.getName(), client);
		}
	}

	public Set<String> getContextNames() {
		return new HashSet<>(this.contexts.keySet());
	}

	@Override
	public void destroy() {
		// 获取所有子容器, 销毁, 清空, help GC
		Collection<AnnotationConfigApplicationContext> values = this.contexts.values();
		for (AnnotationConfigApplicationContext context : values) {
			// This can fail, but it never throws an exception (you see stack traces
			// logged as WARN).
			context.close();
		}
		this.contexts.clear();
	}

	protected AnnotationConfigApplicationContext getContext(String name) {
		if (!this.contexts.containsKey(name)) {
			synchronized (this.contexts) {
				if (!this.contexts.containsKey(name)) {
					// 结论: 容器里有点东西, 但不多...  主要是于父容器打通... 所以又啥都有了.
					this.contexts.put(name, createContext(name));
				}
			}
		}
		return this.contexts.get(name);
	}

	protected AnnotationConfigApplicationContext createContext(String name) {
		// 0.结合实现类 LoadBalancerClientFactory 做出如下注释
		// 1.将 LoadBalancerAutoConfiguration 扫描到 configurations 注册到 name 对应的容器中.
		//     这里的 name 其实就是 serviceId, 也就是说, 若我们想给某个容器加入一些东西, 则实现 LoadBalancerClientSpecification 时, name 需要与 serviceId 对应起来(相同)
		// 2.当我上面那句没说啊... 原来 name 为 default. 开头是可以加入任意 serviceId 对应的容器的.........................(qiao)
		// 3.为容器加入一个占位符解析器, 和一个 defaultConfigType(=LoadBalancerClientConfiguration.class, 作用配置一些 bean)
		//     LoadBalancerClientConfiguration 会加入一个 RoundRobinLoadBalancer, 看来就是默认的负载均衡类了.
		// 4.默认为加了一个名为 loadbalancer 的 PropertySource, 里面有一个 loadbalancer.client.name=serviceId 的配置....
		// 5.设定父容器, 父容器通过 ApplicationContextAware 获得, 这样刚才那么辛苦的注册方式, 就仅适合于特性, 而非通用了.
		// 6.设置名称(啥意义呢?), 然后调用容器的 refresh() 完成容器加载


		AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext();
		if (this.configurations.containsKey(name)) {
			for (Class<?> configuration : this.configurations.get(name).getConfiguration()) {
				context.register(configuration);
			}
		}
		for (Map.Entry<String, C> entry : this.configurations.entrySet()) {
			if (entry.getKey().startsWith("default.")) {
				for (Class<?> configuration : entry.getValue().getConfiguration()) {
					context.register(configuration);
				}
			}
		}
		context.register(PropertyPlaceholderAutoConfiguration.class, this.defaultConfigType);
		// 默认为加了一个名为 loadbalancer 的 PropertySource, 里面有一个 loadbalancer.client.name=serviceId 的配置....
		context.getEnvironment().getPropertySources().addFirst(new MapPropertySource(this.propertySourceName,
				Collections.<String, Object>singletonMap(this.propertyName, name)));
		if (this.parent != null) {
			// Uses Environment from parent as well as beans
			context.setParent(this.parent);
			// jdk11 issue
			// https://github.com/spring-cloud/spring-cloud-netflix/issues/3101
			context.setClassLoader(this.parent.getClassLoader());
		}
		context.setDisplayName(generateDisplayName(name));
		context.refresh();
		return context;
	}

	protected String generateDisplayName(String name) {
		return this.getClass().getSimpleName() + "-" + name;
	}

	public <T> T getInstance(String name, Class<T> type) {
		AnnotationConfigApplicationContext context = getContext(name);
		try {
			return context.getBean(type);
		}
		catch (NoSuchBeanDefinitionException e) {
			// ignore
		}
		return null;
	}

	public <T> ObjectProvider<T> getLazyProvider(String name, Class<T> type) {
		// 懒加载, 封装一层 factory, 在使用具体方法是才会触发 getProvider 方法, 啊, 也就是下面👇的方法
		return new ClientFactoryObjectProvider<>(this, name, type);
	}

	public <T> ObjectProvider<T> getProvider(String name, Class<T> type) {
		// 实际的获取 ServiceInstanceListSupplier 的方法. 这个 ServiceInstanceListSupplier
		AnnotationConfigApplicationContext context = getContext(name);
		return context.getBeanProvider(type);
	}

	public <T> T getInstance(String name, Class<?> clazz, Class<?>... generics) {
		ResolvableType type = ResolvableType.forClassWithGenerics(clazz, generics);
		return getInstance(name, type);
	}

	@SuppressWarnings("unchecked")
	public <T> T getInstance(String name, ResolvableType type) {
		AnnotationConfigApplicationContext context = getContext(name);
		String[] beanNames = BeanFactoryUtils.beanNamesForTypeIncludingAncestors(context, type);
		if (beanNames.length > 0) {
			for (String beanName : beanNames) {
				if (context.isTypeMatch(beanName, type)) {
					return (T) context.getBean(beanName);
				}
			}
		}
		return null;
	}

	public <T> Map<String, T> getInstances(String name, Class<T> type) {
		AnnotationConfigApplicationContext context = getContext(name);

		return BeanFactoryUtils.beansOfTypeIncludingAncestors(context, type);
	}

	/**
	 * Specification with name and configuration.
	 */
	public interface Specification {

		String getName();

		Class<?>[] getConfiguration();

	}

}
