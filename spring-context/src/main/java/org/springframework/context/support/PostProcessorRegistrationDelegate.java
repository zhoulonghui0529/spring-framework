/*
 * Copyright 2002-2021 the original author or authors.
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

package org.springframework.context.support;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.beans.factory.config.BeanFactoryPostProcessor;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.beans.factory.support.AbstractBeanFactory;
import org.springframework.beans.factory.support.BeanDefinitionRegistry;
import org.springframework.beans.factory.support.BeanDefinitionRegistryPostProcessor;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.beans.factory.support.MergedBeanDefinitionPostProcessor;
import org.springframework.beans.factory.support.RootBeanDefinition;
import org.springframework.core.OrderComparator;
import org.springframework.core.Ordered;
import org.springframework.core.PriorityOrdered;
import org.springframework.core.metrics.ApplicationStartup;
import org.springframework.core.metrics.StartupStep;
import org.springframework.lang.Nullable;

/**
 * Delegate for AbstractApplicationContext's post-processor handling.
 *
 * @author Juergen Hoeller
 * @author Sam Brannen
 * @since 4.0
 */
final class PostProcessorRegistrationDelegate {

	private PostProcessorRegistrationDelegate() {
	}

	/**
	 * invokeBeanFactoryPostProcessors 执行BeanFactoryPostProcessor所有实现类
	 * 所有实现类{
	 *     1、spring内置的---在这个方法之前被封装成BeanDefinition对象 并且put到beanDefinitionMap集合中
	 *     2、程序员提供的
	 *     		2.1通过扫描出来的
	 *     		2.2通过api提供的 即invokeBeanFactoryPostProcessors方法的参数List<BeanFactoryPostProcessor> beanFactoryPostProcessors
	 *     		{
	 *     		 	AnnotationConfigApplicationContext ac=new AnnotationConfigApplicationContext();
	 * 				ac.register(AppConfig.class);
	 * 				//api提供
	 * 				ac.addBeanFactoryPostProcessor(new JHBeanFactoryProcessor());
	 * 				ac.refresh();
	 *     		}
	 *     		2.3实现Ordered接口
	 * }
	 */
	public static void invokeBeanFactoryPostProcessors(
			ConfigurableListableBeanFactory beanFactory, List<BeanFactoryPostProcessor> beanFactoryPostProcessors) {

		// WARNING: Although it may appear that the body of this method can be easily
		// refactored to avoid the use of multiple loops and multiple lists, the use
		// of multiple lists and multiple passes over the names of processors is
		// intentional. We must ensure that we honor the contracts for PriorityOrdered
		// and Ordered processors. Specifically, we must NOT cause processors to be
		// instantiated (via getBean() invocations) or registered in the ApplicationContext
		// in the wrong order.
		//
		// Before submitting a pull request (PR) to change this method, please review the
		// list of all declined PRs involving changes to PostProcessorRegistrationDelegate
		// to ensure that your proposal does not result in a breaking change:
		// https://github.com/spring-projects/spring-framework/issues?q=PostProcessorRegistrationDelegate+is%3Aclosed+label%3A%22status%3A+declined%22

		// Invoke BeanDefinitionRegistryPostProcessors first, if any.
		//存放已经处理完成的BeanFactoryPostProcessor的名字
		Set<String> processedBeans = new HashSet<>();
		//绝大部分beanFactory都为DefaultListableBeanFactory类型的(实现了BeanDefinitionRegistry接口)
		if (beanFactory instanceof BeanDefinitionRegistry) {
			BeanDefinitionRegistry registry = (BeanDefinitionRegistry) beanFactory;
			//存放api提供直接实现BeanFactoryPostProcessor的postProcessor后置处理器
			List<BeanFactoryPostProcessor> regularPostProcessors = new ArrayList<>();
			//存放所有已经执行了的后置处理器
			List<BeanDefinitionRegistryPostProcessor> registryProcessors = new ArrayList<>();
			/**
			 * 遍历方法入参beanFactoryPostProcessors集合，即通过api提供的BeanFactoryPostProcessor
			 * BeanDefinitionRegistryPostProcessor是BeanFactoryPostProcessor的子类
			 */
			for (BeanFactoryPostProcessor postProcessor : beanFactoryPostProcessors) {
				if (postProcessor instanceof BeanDefinitionRegistryPostProcessor) {
					BeanDefinitionRegistryPostProcessor registryProcessor =
							(BeanDefinitionRegistryPostProcessor) postProcessor;
					/**
					 * 执行实现BeanDefinitionRegistryPostProcessor接口的后置处理器---api提供的BeanDefinitionRegistryPostProcessor类型的后置处理器
					 *
					 * 这两种 BeanFactoryPostProcessor(1、BeanDefinitionRegistryPostProcessor子类；2、BeanFactoryPostProcessor父类)
					 * 先执行BeanDefinitionRegistryPostProcessor，再执行BeanFactoryPostProcessor
					 */
					registryProcessor.postProcessBeanDefinitionRegistry(registry);
					registryProcessors.add(registryProcessor);
				}
				else {
					/**
					 * 将实现了BeanFactoryPostProcessor接口的后置处理器，缓存到regularPostProcessors集合中
					 *
					 * 为什么这里不直接执行而是缓存起来？
					 * 因为spring容器是先执行BeanDefinitionRegistryPostProcessor，再执行BeanFactoryPostProcessor，
					 * 如果在这里直接执行，那么此时的BeanFactoryPostProcessor类型的后置处理器会先于spring内置的BeanDefinitionRegistryPostProcessor类型的后置处理器，
					 * 执行顺序就错乱了。
					 */
					//如果api提供的postProcessor是直接实现了BeanFactoryPostProcessor接口的后置处理器
					regularPostProcessors.add(postProcessor);
				}
			}

			// Do not initialize FactoryBeans here: We need to leave all regular beans
			// uninitialized to let the bean factory post-processors apply to them!
			// Separate between BeanDefinitionRegistryPostProcessors that implement
			// PriorityOrdered, Ordered, and the rest.
			/**
			 * 存储当前需要执行的BeanDefinitionRegistryPostProcessor实现类的对象
			 * 每次执行完成都会清除 防止重复执行。
			 */
			List<BeanDefinitionRegistryPostProcessor> currentRegistryProcessors = new ArrayList<>();

			// First, invoke the BeanDefinitionRegistryPostProcessors that implement PriorityOrdered.
			//获取并执行BeanDefinitionRegistryPostProcessor类型并且实现PriorityOrdered接口的后置处理器
			/**
			 * 从spring的BeanDefinitionMap集合中获取spring内置的后置处理器----符合条件：ConfigurationClassPostProcessor内置后置处理器
			 * 		即内置的后置处理器只有ConfigurationClassPostProcessor实现了BeanDefinitionRegistryPostProcessor接口
			 *
			 * 		遍历beanDefinitionNames[]，然后根据遍历出的beanName，从beanDefinitionMap中获取对应的BeanDefinition对象，
			 * 		然后判断BeanDefinition所对应的class和目标class(实现BeanDefinitionRegistryPostProcessor)是否相同，
			 * 		如果相同就返回。
			 * 		因为可能有多个类实现BeanDefinitionRegistryPostProcessor接口，所以返回一个数组。
			 *
			 * 		当前的postProcessorNames是从beanDefinitionMap中获取的，
			 * 		所以这里获取执行的是spring内置的BeanFactoryPostProcessor。
			 *
			 * 		这里符合条件的是ConfigurationClassPostProcessor后置处理器
			 */
			String[] postProcessorNames =
					beanFactory.getBeanNamesForType(BeanDefinitionRegistryPostProcessor.class, true, false);
			for (String ppName : postProcessorNames) {
				//将实现PriorityOrdered接口的后置处理器添加到当前需要执行的后置处理器List集合中
				/**
				 * beanFactory.getBean()获取对应bean对象。
				 *
				 * 实例化、初始化ConfigurationClassPostProcessor内置后置处理器，
				 * 经过完整的bean的生命周期，并将其创建好的bean放入spring单例池中。
				 *
				 * 在此之前，单例池中只包含applicationStartup、systemEnvironment、environment(StandardEnvironment)、systemProperties单实例bean
				 */
				if (beanFactory.isTypeMatch(ppName, PriorityOrdered.class)) {
					currentRegistryProcessors.add(beanFactory.getBean(ppName, BeanDefinitionRegistryPostProcessor.class));
					//将实现PriorityOrdered接口的后置处理器，添加到存放已经处理完成的BeanFactoryPostProcessor的名字的Set集合中
					processedBeans.add(ppName);
				}
			}
			//将存放spring内置的后置处理器的currentRegistryProcessors集合中的后置处理器，进行排序
			sortPostProcessors(currentRegistryProcessors, beanFactory);
			//将所有spring内置的后置处理器存放到registryProcessors
			registryProcessors.addAll(currentRegistryProcessors);
			/**
			 * 执行ConfigurationClassPostProcessor内置后置处理器
			 * 		回调ConfigurationClassPostProcessor#processConfigBeanDefinitions(BeanDefinitionRegistry)方法
			 * 		主要进行配置类扫描，将配置类中配置信息扫描出来。
			 */
			//执行排序后的当前currentRegistryProcessors集合中的后置处理器。
			invokeBeanDefinitionRegistryPostProcessors(currentRegistryProcessors, registry, beanFactory.getApplicationStartup());
			//清除currentRegistryProcessors集合中所有后置处理器
			currentRegistryProcessors.clear();

			// Next, invoke the BeanDefinitionRegistryPostProcessors that implement Ordered.
			postProcessorNames = beanFactory.getBeanNamesForType(BeanDefinitionRegistryPostProcessor.class, true, false);
			//获取并执行 程序员提供的 BeanDefinitionRegistryPostProcessor类型并且实现Ordered接口的后置处理器
			for (String ppName : postProcessorNames) {
				//需要排除已经执行的实现PriorityOrdered接口的后置处理器
				if (!processedBeans.contains(ppName) && beanFactory.isTypeMatch(ppName, Ordered.class)) {
					currentRegistryProcessors.add(beanFactory.getBean(ppName, BeanDefinitionRegistryPostProcessor.class));
					processedBeans.add(ppName);
				}
			}
			sortPostProcessors(currentRegistryProcessors, beanFactory);
			//将所有 程序员提供的 实现Ordered接口的后置处理器也存放到registryProcessors
			registryProcessors.addAll(currentRegistryProcessors);
			invokeBeanDefinitionRegistryPostProcessors(currentRegistryProcessors, registry, beanFactory.getApplicationStartup());
			currentRegistryProcessors.clear();

			// Finally, invoke all other BeanDefinitionRegistryPostProcessors until no further ones appear.
			//获取并执行 程序员提供的 BeanDefinitionRegistryPostProcessor类型没有实现PriorityOrdered、Ordered接口的后置处理器
			boolean reiterate = true;
			while (reiterate) {
				reiterate = false;
				postProcessorNames = beanFactory.getBeanNamesForType(BeanDefinitionRegistryPostProcessor.class, true, false);
				for (String ppName : postProcessorNames) {
					//需要排除已经执行的实现PriorityOrdered、Ordered接口的后置处理器
					if (!processedBeans.contains(ppName)) {
						currentRegistryProcessors.add(beanFactory.getBean(ppName, BeanDefinitionRegistryPostProcessor.class));
						processedBeans.add(ppName);
						reiterate = true;
					}
				}
				sortPostProcessors(currentRegistryProcessors, beanFactory);
				//将所有其他后置处理器（没有实现PriorityOrdered、Ordered接口）也存放到registryProcessors
				registryProcessors.addAll(currentRegistryProcessors);
				invokeBeanDefinitionRegistryPostProcessors(currentRegistryProcessors, registry, beanFactory.getApplicationStartup());
				currentRegistryProcessors.clear();
			}

			/**
			 * 以上的代码：
			 * 	执行BeanFactoryPostProcessor的子类BeanDefinitionRegistryPostProcessor的所有实现类的postProcessBeanDefinitionRegistry方法。
			 *
			 * 	1、先执行程序员提供的实现BeanDefinitionRegistryPostProcessor的实现类
			 * 	2、执行spring内置的实现BeanDefinitionPostProcessor的实现类---ConfigurationClassPostProcessor.class
			 * 	3、然后通过ConfigurationClassPostProcessor的方法postProcessBeanDefinitionRegistry()去完成扫描符合spring条件的组件(注解、xml等方式)。
			 * 	4、执行扫描得到实现了Ordered接口的BeanDefinitionRegistryPostProcessor类型的所有实现类
			 * 	5、最后执行扫描得到 没有 实现了Ordered接口的BeanDefinitionRegistryPostProcessor类型的所有实现类
			 */

			// Now, invoke the postProcessBeanFactory callback of all processors handled so far.
			/**
			 * 执行所有实现BeanFactoryPostProcessor接口的实现类后置处理器(spring内置的、程序员提供的)
			 * 在当前if语句中的后置处理器分为：直接实现BeanDefinitionRegistryPostProcessor接口、实现BeanFactoryPostProcessor接口。
			 * 而BeanDefinitionRegistryPostProcessor类型的后置处理器其父类为BeanFactoryPostProcessor
			 * 因此在registryProcessors集合中的所有后置处理器也就实现了其父类BeanFactoryPostProcessor接口
			 * 所以会执行实现BeanFactoryPostProcessor的postProcessBeanFactory方法
			 *
			 * spring先执行实现子类BeanDefinitionRegistryPostProcessor的后置处理器，再执行直接实现父类BeanFactoryPostProcessor的后置处理器
			 */
			//执行所有实现BeanDefinitionRegistryPostProcessor(BeanDefinitionRegistryPostProcessor是BeanFactoryPostProcessor的子类)接口的后置处理器。
			invokeBeanFactoryPostProcessors(registryProcessors, beanFactory);
			//执行api提供的所有直接实现BeanFactoryPostProcessor接口的后置处理器。
			invokeBeanFactoryPostProcessors(regularPostProcessors, beanFactory);
		}

		else {
			// Invoke factory processors registered with the context instance.
			//一般不会执行这里。因为绝大部分beanFactory都为DefaultListableBeanFactory类型的(实现了BeanDefinitionRegistry接口)
			invokeBeanFactoryPostProcessors(beanFactoryPostProcessors, beanFactory);
		}

		// Do not initialize FactoryBeans here: We need to leave all regular beans
		// uninitialized to let the bean factory post-processors apply to them!
		//获取所有实现BeanFactoryPostProcessor接口的后置处理器的名称，包括spring内置的和程序员通过注解提供的。
		String[] postProcessorNames =
				beanFactory.getBeanNamesForType(BeanFactoryPostProcessor.class, true, false);

		// Separate between BeanFactoryPostProcessors that implement PriorityOrdered,
		// Ordered, and the rest.
		//存放所有实现BeanFactoryPostProcessor接口并且实现PriorityOrdered接口的后置处理器
		List<BeanFactoryPostProcessor> priorityOrderedPostProcessors = new ArrayList<>();
		//存放所有实现BeanFactoryPostProcessor接口并且实现Ordered接口的后置处理器的名称
		List<String> orderedPostProcessorNames = new ArrayList<>();
		//存放所有实现BeanFactoryPostProcessor接口并且没有实现PriorityOrdered、Ordered接口的后置处理器的名称
		List<String> nonOrderedPostProcessorNames = new ArrayList<>();
		for (String ppName : postProcessorNames) {
			//不考虑前面已经执行的BeanFactoryPostProcessor后置处理器(processedBeans集合中的)
			if (processedBeans.contains(ppName)) {
				// skip - already processed in first phase above
			}
			//判断BeanFactoryPostProcessor后置处理器是否实现PriorityOrdered接口，缓存到priorityOrderedPostProcessors集合中
			else if (beanFactory.isTypeMatch(ppName, PriorityOrdered.class)) {
				priorityOrderedPostProcessors.add(beanFactory.getBean(ppName, BeanFactoryPostProcessor.class));
			}
			//判断BeanFactoryPostProcessor后置处理器是否实现Ordered接口，缓存 名称 到orderedPostProcessorNames集合中
			else if (beanFactory.isTypeMatch(ppName, Ordered.class)) {
				orderedPostProcessorNames.add(ppName);
			}
			//判断BeanFactoryPostProcessor后置处理器没有实现PriorityOrdered、Ordered接口，缓存 名称 到nonOrderedPostProcessorNames集合中
			else {
				nonOrderedPostProcessorNames.add(ppName);
			}
		}

		// First, invoke the BeanFactoryPostProcessors that implement PriorityOrdered.
		//排序、执行实现PriorityOrdered接口的BeanFactoryPostProcessor类型的后置处理器
		sortPostProcessors(priorityOrderedPostProcessors, beanFactory);
		invokeBeanFactoryPostProcessors(priorityOrderedPostProcessors, beanFactory);

		// Next, invoke the BeanFactoryPostProcessors that implement Ordered.
		//排序、执行实现Ordered接口的BeanFactoryPostProcessor类型的后置处理器
		List<BeanFactoryPostProcessor> orderedPostProcessors = new ArrayList<>(orderedPostProcessorNames.size());
		for (String postProcessorName : orderedPostProcessorNames) {
			orderedPostProcessors.add(beanFactory.getBean(postProcessorName, BeanFactoryPostProcessor.class));
		}
		sortPostProcessors(orderedPostProcessors, beanFactory);
		invokeBeanFactoryPostProcessors(orderedPostProcessors, beanFactory);

		// Finally, invoke all other BeanFactoryPostProcessors.
		List<BeanFactoryPostProcessor> nonOrderedPostProcessors = new ArrayList<>(nonOrderedPostProcessorNames.size());
		for (String postProcessorName : nonOrderedPostProcessorNames) {
			nonOrderedPostProcessors.add(beanFactory.getBean(postProcessorName, BeanFactoryPostProcessor.class));
		}
		invokeBeanFactoryPostProcessors(nonOrderedPostProcessors, beanFactory);

		// Clear cached merged bean definitions since the post-processors might have
		// modified the original metadata, e.g. replacing placeholders in values...
		beanFactory.clearMetadataCache();
	}

	public static void registerBeanPostProcessors(
			ConfigurableListableBeanFactory beanFactory, AbstractApplicationContext applicationContext) {

		// WARNING: Although it may appear that the body of this method can be easily
		// refactored to avoid the use of multiple loops and multiple lists, the use
		// of multiple lists and multiple passes over the names of processors is
		// intentional. We must ensure that we honor the contracts for PriorityOrdered
		// and Ordered processors. Specifically, we must NOT cause processors to be
		// instantiated (via getBean() invocations) or registered in the ApplicationContext
		// in the wrong order.
		//
		// Before submitting a pull request (PR) to change this method, please review the
		// list of all declined PRs involving changes to PostProcessorRegistrationDelegate
		// to ensure that your proposal does not result in a breaking change:
		// https://github.com/spring-projects/spring-framework/issues?q=PostProcessorRegistrationDelegate+is%3Aclosed+label%3A%22status%3A+declined%22
		//获取BeanPostProcessor类型的bean名称
		String[] postProcessorNames = beanFactory.getBeanNamesForType(BeanPostProcessor.class, true, false);

		// Register BeanPostProcessorChecker that logs an info message when
		// a bean is created during BeanPostProcessor instantiation, i.e. when
		// a bean is not eligible for getting processed by all BeanPostProcessors.
		//注册BeanPostProcessorChecker，当bean在BeanPostProcessor实例化期间被创建时，即当一个bean不符合被所有BeanPostProcessor处理的条件时，记录一个信息消息。
		int beanProcessorTargetCount = beanFactory.getBeanPostProcessorCount() + 1 + postProcessorNames.length;
		beanFactory.addBeanPostProcessor(new BeanPostProcessorChecker(beanFactory, beanProcessorTargetCount));

		// Separate between BeanPostProcessors that implement PriorityOrdered,
		// Ordered, and the rest.
		//实现PriorityOrdered接口的bean后置处理器
		List<BeanPostProcessor> priorityOrderedPostProcessors = new ArrayList<>();
		//实现MergedBeanDefinitionPostProcessor的bean后置处理器
		List<BeanPostProcessor> internalPostProcessors = new ArrayList<>();
		//实现Ordered接口的bean后置处理器
		List<String> orderedPostProcessorNames = new ArrayList<>();
		//不需要排序的bean后置处理器
		List<String> nonOrderedPostProcessorNames = new ArrayList<>();
		//遍历所有的postProcessorNames集合
		for (String ppName : postProcessorNames) {
			if (beanFactory.isTypeMatch(ppName, PriorityOrdered.class)) {
				BeanPostProcessor pp = beanFactory.getBean(ppName, BeanPostProcessor.class);
				priorityOrderedPostProcessors.add(pp);
				//实现PriorityOrdered接口的bean后置处理器，并且实现MergedBeanDefinitionPostProcessor的bean后置处理器
				if (pp instanceof MergedBeanDefinitionPostProcessor) {
					internalPostProcessors.add(pp);
				}
			}
			else if (beanFactory.isTypeMatch(ppName, Ordered.class)) {
				orderedPostProcessorNames.add(ppName);
			}
			else {
				nonOrderedPostProcessorNames.add(ppName);
			}
		}

		// First, register the BeanPostProcessors that implement PriorityOrdered.
		//排序、注册实现PriorityOrdered接口的bean后置处理器
		sortPostProcessors(priorityOrderedPostProcessors, beanFactory);
		registerBeanPostProcessors(beanFactory, priorityOrderedPostProcessors);

		// Next, register the BeanPostProcessors that implement Ordered.
		List<BeanPostProcessor> orderedPostProcessors = new ArrayList<>(orderedPostProcessorNames.size());
		for (String ppName : orderedPostProcessorNames) {
			BeanPostProcessor pp = beanFactory.getBean(ppName, BeanPostProcessor.class);
			orderedPostProcessors.add(pp);
			//实现Ordered接口的bean后置处理器，并且实现MergedBeanDefinitionPostProcessor的bean后置处理器
			if (pp instanceof MergedBeanDefinitionPostProcessor) {
				internalPostProcessors.add(pp);
			}
		}
		sortPostProcessors(orderedPostProcessors, beanFactory);
		registerBeanPostProcessors(beanFactory, orderedPostProcessors);

		// Now, register all regular BeanPostProcessors.
		List<BeanPostProcessor> nonOrderedPostProcessors = new ArrayList<>(nonOrderedPostProcessorNames.size());
		for (String ppName : nonOrderedPostProcessorNames) {
			BeanPostProcessor pp = beanFactory.getBean(ppName, BeanPostProcessor.class);
			nonOrderedPostProcessors.add(pp);
			//不需要排序的bean后置处理器，并且实现MergedBeanDefinitionPostProcessor的bean后置处理器
			if (pp instanceof MergedBeanDefinitionPostProcessor) {
				internalPostProcessors.add(pp);
			}
		}
		registerBeanPostProcessors(beanFactory, nonOrderedPostProcessors);

		// Finally, re-register all internal BeanPostProcessors.
		sortPostProcessors(internalPostProcessors, beanFactory);
		registerBeanPostProcessors(beanFactory, internalPostProcessors);

		// Re-register post-processor for detecting inner beans as ApplicationListeners,
		// moving it to the end of the processor chain (for picking up proxies etc).
		beanFactory.addBeanPostProcessor(new ApplicationListenerDetector(applicationContext));
	}

	private static void sortPostProcessors(List<?> postProcessors, ConfigurableListableBeanFactory beanFactory) {
		// Nothing to sort?
		if (postProcessors.size() <= 1) {
			return;
		}
		Comparator<Object> comparatorToUse = null;
		if (beanFactory instanceof DefaultListableBeanFactory) {
			comparatorToUse = ((DefaultListableBeanFactory) beanFactory).getDependencyComparator();
		}
		if (comparatorToUse == null) {
			comparatorToUse = OrderComparator.INSTANCE;
		}
		postProcessors.sort(comparatorToUse);
	}

	/**
	 * Invoke the given BeanDefinitionRegistryPostProcessor beans.
	 */
	private static void invokeBeanDefinitionRegistryPostProcessors(
			Collection<? extends BeanDefinitionRegistryPostProcessor> postProcessors, BeanDefinitionRegistry registry, ApplicationStartup applicationStartup) {
		//拿到spring当中所有的BeanFactoryPostProcessor实现对象。
		for (BeanDefinitionRegistryPostProcessor postProcessor : postProcessors) {
			StartupStep postProcessBeanDefRegistry = applicationStartup.start("spring.context.beandef-registry.post-process")
					.tag("postProcessor", postProcessor::toString);
			postProcessor.postProcessBeanDefinitionRegistry(registry);
			postProcessBeanDefRegistry.end();
		}
	}

	/**
	 * Invoke the given BeanFactoryPostProcessor beans.
	 */
	private static void invokeBeanFactoryPostProcessors(
			Collection<? extends BeanFactoryPostProcessor> postProcessors, ConfigurableListableBeanFactory beanFactory) {

		for (BeanFactoryPostProcessor postProcessor : postProcessors) {
			StartupStep postProcessBeanFactory = beanFactory.getApplicationStartup().start("spring.context.bean-factory.post-process")
					.tag("postProcessor", postProcessor::toString);
			postProcessor.postProcessBeanFactory(beanFactory);
			postProcessBeanFactory.end();
		}
	}

	/**
	 * Register the given BeanPostProcessor beans.
	 */
	private static void registerBeanPostProcessors(
			ConfigurableListableBeanFactory beanFactory, List<BeanPostProcessor> postProcessors) {

		if (beanFactory instanceof AbstractBeanFactory) {
			// Bulk addition is more efficient against our CopyOnWriteArrayList there
			((AbstractBeanFactory) beanFactory).addBeanPostProcessors(postProcessors);
		}
		else {
			for (BeanPostProcessor postProcessor : postProcessors) {
				beanFactory.addBeanPostProcessor(postProcessor);
			}
		}
	}


	/**
	 * BeanPostProcessor that logs an info message when a bean is created during
	 * BeanPostProcessor instantiation, i.e. when a bean is not eligible for
	 * getting processed by all BeanPostProcessors.
	 */
	private static final class BeanPostProcessorChecker implements BeanPostProcessor {

		private static final Log logger = LogFactory.getLog(BeanPostProcessorChecker.class);

		private final ConfigurableListableBeanFactory beanFactory;

		private final int beanPostProcessorTargetCount;

		public BeanPostProcessorChecker(ConfigurableListableBeanFactory beanFactory, int beanPostProcessorTargetCount) {
			this.beanFactory = beanFactory;
			this.beanPostProcessorTargetCount = beanPostProcessorTargetCount;
		}

		@Override
		public Object postProcessBeforeInitialization(Object bean, String beanName) {
			return bean;
		}

		@Override
		public Object postProcessAfterInitialization(Object bean, String beanName) {
			if (!(bean instanceof BeanPostProcessor) && !isInfrastructureBean(beanName) &&
					this.beanFactory.getBeanPostProcessorCount() < this.beanPostProcessorTargetCount) {
				if (logger.isInfoEnabled()) {
					logger.info("Bean '" + beanName + "' of type [" + bean.getClass().getName() +
							"] is not eligible for getting processed by all BeanPostProcessors " +
							"(for example: not eligible for auto-proxying)");
				}
			}
			return bean;
		}

		private boolean isInfrastructureBean(@Nullable String beanName) {
			if (beanName != null && this.beanFactory.containsBeanDefinition(beanName)) {
				BeanDefinition bd = this.beanFactory.getBeanDefinition(beanName);
				return (bd.getRole() == RootBeanDefinition.ROLE_INFRASTRUCTURE);
			}
			return false;
		}
	}

}
