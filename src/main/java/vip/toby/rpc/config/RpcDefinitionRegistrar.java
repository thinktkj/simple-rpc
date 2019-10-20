package vip.toby.rpc.config;

import org.springframework.amqp.core.*;
import org.springframework.amqp.rabbit.annotation.RabbitListenerAnnotationBeanPostProcessor;
import org.springframework.amqp.rabbit.config.RabbitListenerConfigUtils;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.rabbit.listener.SimpleMessageListenerContainer;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.BeanClassLoaderAware;
import org.springframework.beans.factory.BeanFactory;
import org.springframework.beans.factory.BeanFactoryAware;
import org.springframework.beans.factory.annotation.AnnotatedBeanDefinition;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.beans.factory.support.*;
import org.springframework.boot.autoconfigure.AutoConfigurationPackages;
import org.springframework.context.EnvironmentAware;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.context.annotation.ImportBeanDefinitionRegistrar;
import org.springframework.core.Ordered;
import org.springframework.core.env.Environment;
import org.springframework.core.type.AnnotationMetadata;
import org.springframework.core.type.filter.AnnotationTypeFilter;
import org.springframework.retry.policy.SimpleRetryPolicy;
import org.springframework.retry.support.RetryTemplate;
import org.springframework.util.StringUtils;
import vip.toby.rpc.annotation.RpcClient;
import vip.toby.rpc.annotation.RpcServer;
import vip.toby.rpc.client.RpcClientProxyFactory;
import vip.toby.rpc.entity.RpcType;
import vip.toby.rpc.server.RpcServerHandler;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * RpcScanDefinitionRegistrar
 *
 * @author toby
 */
public class RpcDefinitionRegistrar implements ImportBeanDefinitionRegistrar, EnvironmentAware, BeanFactoryAware, BeanClassLoaderAware, Ordered {

    private Environment environment;
    private BeanFactory beanFactory;
    private ClassLoader classLoader;
    private DirectExchange syncDirectExchange;
    private DirectExchange asyncDirectExchange;
    private DirectExchange syncReplyDirectExchange;
    private ConnectionFactory connectionFactory;

    @Override
    public int getOrder() {
        return Ordered.LOWEST_PRECEDENCE;
    }

    @Override
    public void setEnvironment(Environment environment) {
        this.environment = environment;
    }

    @Override
    public void setBeanFactory(BeanFactory beanFactory) throws BeansException {
        this.beanFactory = beanFactory;
    }

    @Override
    public void setBeanClassLoader(ClassLoader classLoader) {
        this.classLoader = classLoader;
    }

    @Override
    public void registerBeanDefinitions(AnnotationMetadata metadata, BeanDefinitionRegistry registry) {

        if (!registry.containsBeanDefinition(
                RabbitListenerConfigUtils.RABBIT_LISTENER_ANNOTATION_PROCESSOR_BEAN_NAME)) {

            registry.registerBeanDefinition(RabbitListenerConfigUtils.RABBIT_LISTENER_ANNOTATION_PROCESSOR_BEAN_NAME,
                    new RootBeanDefinition(RabbitListenerAnnotationBeanPostProcessor.class));
        }
        // 强制初始化RpcRabbitProperties
//        this.beanFactory.getBean(RpcRabbitProperties.class);
        // 强制初始化ConnectionFactory
        this.connectionFactory = this.beanFactory.getBean(ConnectionFactory.class);
        // 强制初始化AmqpAdmin
//        this.beanFactory.getBean(AmqpAdmin.class);
        // 开始扫描默认包路径
        String[] basePackages = StringUtils.toStringArray(AutoConfigurationPackages.get(this.beanFactory));
        // 扫描 RpcClient 注解
        scanClient(basePackages, registry);
        // 扫描 RpcServer 注解
        scanServer(basePackages);
    }

    /**
     * 扫描 RpcServer 注解
     */
    private void scanServer(String[] basePackages) {
        // 扫描 RpcServer 注解
        ClassPathScanningCandidateComponentProvider serverProvider = new ClassPathScanningCandidateComponentProvider(false, this.environment) {
            @Override
            protected boolean isCandidateComponent(AnnotatedBeanDefinition beanDefinition) {
                AnnotationMetadata metadata = beanDefinition.getMetadata();
                return metadata.isIndependent() && metadata.isConcrete();
            }
        };
        serverProvider.addIncludeFilter(new AnnotationTypeFilter(RpcServer.class));
        for (String basePackage : basePackages) {
            for (BeanDefinition beanDefinition : serverProvider.findCandidateComponents(basePackage)) {
                GenericBeanDefinition rpcServerBeanDefinition = new GenericBeanDefinition(beanDefinition);
                try {
                    Class<?> rpcServerClass = rpcServerBeanDefinition.resolveBeanClass(this.classLoader);
                    if (rpcServerClass != null) {
                        Object rpcServerBean = registerBean(beanDefinition.getBeanClassName(), rpcServerClass);
                        RpcServer rpcServer = rpcServerClass.getAnnotation(RpcServer.class);
                        if (rpcServer != null) {
                            String rpcName = rpcServer.name();
                            for (RpcType rpcType : rpcServer.type()) {
                                switch (rpcType) {
                                    case SYNC:
                                        Map<String, Object> params = new HashMap<>(1);
                                        params.put("x-message-ttl", rpcServer.xMessageTTL());
                                        Queue syncQueue = queue(rpcName, rpcType, false, params);
                                        binding(rpcName, rpcType, syncQueue);
                                        RpcServerHandler syncServerHandler = rpcServerHandler(rpcName, rpcType, rpcServerBean);
                                        messageListenerContainer(rpcName, rpcType, syncQueue, syncServerHandler, rpcServer.threadNum());
                                        break;
                                    case ASYNC:
                                        Queue asyncQueue = queue(rpcName, rpcType, true, null);
                                        binding(rpcName, rpcType, asyncQueue);
                                        RpcServerHandler asyncServerHandler = rpcServerHandler(rpcName, rpcType, rpcServerBean);
                                        messageListenerContainer(rpcName, rpcType, asyncQueue, asyncServerHandler, rpcServer.threadNum());
                                        break;
                                    default:
                                        break;
                                }
                            }
                        }
                    }
                } catch (ClassNotFoundException e) {
                    e.printStackTrace();
                }
            }
        }
    }

    /**
     * 扫描 RpcClient 注解
     */
    private void scanClient(String[] basePackages, BeanDefinitionRegistry registry) {
        ClassPathScanningCandidateComponentProvider clientProvider = new ClassPathScanningCandidateComponentProvider(false, this.environment) {
            @Override
            protected boolean isCandidateComponent(AnnotatedBeanDefinition beanDefinition) {
                AnnotationMetadata metadata = beanDefinition.getMetadata();
                return metadata.isIndependent() && metadata.isInterface();
            }
        };
        clientProvider.addIncludeFilter(new AnnotationTypeFilter(RpcClient.class));
        for (String basePackage : basePackages) {
            for (BeanDefinition beanDefinition : clientProvider.findCandidateComponents(basePackage)) {
                GenericBeanDefinition rpcClientBeanDefinition = new GenericBeanDefinition(beanDefinition);
                try {
                    Class<?> rpcClientClass = rpcClientBeanDefinition.resolveBeanClass(this.classLoader);
                    if (rpcClientClass != null) {
                        RpcClient rpcClient = rpcClientClass.getAnnotation(RpcClient.class);
                        if (rpcClient != null) {
                            String rpcName = rpcClient.name();
                            RpcType rpcType = rpcClient.type();
                            // 获取真实接口class，并作为构造方法的参数
                            rpcClientBeanDefinition.getConstructorArgumentValues().addGenericArgumentValue(rpcClientClass);
                            // 修改类为 RpcClientProxyFactory
                            rpcClientBeanDefinition.setBeanClass(RpcClientProxyFactory.class);
                            // 注入值
                            rpcClientBeanDefinition.getPropertyValues().add("rpcClientClass", rpcClientClass);
                            rpcClientBeanDefinition.getPropertyValues().add("rpcName", rpcName);
                            rpcClientBeanDefinition.getPropertyValues().add("rpcType", rpcType);
                            rpcClientBeanDefinition.getPropertyValues().add("sender", rpcClientSender(rpcClient));
                            // 采用按照类型注入的方式
                            rpcClientBeanDefinition.setAutowireMode(AbstractBeanDefinition.AUTOWIRE_BY_TYPE);
                            // 注入到spring
                            registry.registerBeanDefinition(beanDefinition.getBeanClassName(), rpcClientBeanDefinition);
                        }
                    }
                } catch (ClassNotFoundException e) {
                    e.printStackTrace();
                }
            }
        }
    }

    /**
     * 实例化 Queue
     */
    private Queue queue(String rpcName, RpcType rpcType, boolean durable, Map<String, Object> params) {
        return registerBean(rpcType.getValue() + "_" + rpcName + "_Queue", Queue.class, rpcType == RpcType.ASYNC ? (rpcName + ".async") : rpcName, durable, false, false, params);
    }

    /**
     * 实例化 Binding
     */
    private void binding(String rpcName, RpcType rpcType, Queue queue) {
        registerBean(rpcType.getValue() + "_" + rpcName + "_Binding", Binding.class, queue.getName(), Binding.DestinationType.QUEUE, getDirectExchange(rpcType).getName(), queue.getName(), Collections.<String, Object>emptyMap());
    }

    /**
     * 实例化 RpcServerHandler
     */
    private RpcServerHandler rpcServerHandler(String rpcName, RpcType rpcType, Object rpcServerBean) {
        return registerBean(rpcType.getValue() + "_" + rpcName + "_RpcServerHandler", RpcServerHandler.class, rpcServerBean, rpcName, rpcType);
    }

    /**
     * 实例化 SimpleMessageListenerContainer
     */
    private void messageListenerContainer(String rpcName, RpcType rpcType, Queue queue, RpcServerHandler rpcServerHandler, int threadNum) {
        SimpleMessageListenerContainer messageListenerContainer = registerBean(rpcType.getValue() + "_" + rpcName + "_MessageListenerContainer", SimpleMessageListenerContainer.class, connectionFactory);
        messageListenerContainer.setQueueNames(queue.getName());
        messageListenerContainer.setMessageListener(rpcServerHandler);
        messageListenerContainer.setAcknowledgeMode(AcknowledgeMode.MANUAL);
        messageListenerContainer.setConcurrentConsumers(threadNum);
    }

    /**
     * 实例化 DirectExchange
     */
    private DirectExchange getDirectExchange(RpcType rpcType) {
        if (rpcType == RpcType.SYNC) {
            if (this.syncDirectExchange == null) {
                this.syncDirectExchange = registerBean("syncDirectExchange", DirectExchange.class, "simple.rpc.sync", true, false);
            }
            return this.syncDirectExchange;
        }
        if (this.asyncDirectExchange == null) {
            this.asyncDirectExchange = registerBean("asyncDirectExchange", DirectExchange.class, "simple.rpc.async", true, false);
        }
        return this.asyncDirectExchange;
    }

    /**
     * 客户端
     */
    private RabbitTemplate rpcClientSender(RpcClient rpcClient) {
        String rpcName = rpcClient.name();
        if (rpcClient.type() == RpcType.SYNC) {
            Queue replyQueue = replyQueue(rpcName, UUID.randomUUID().toString());
            replyBinding(rpcName, replyQueue);
            RabbitTemplate syncSender = syncSender(rpcName, replyQueue, rpcClient.replyTimeout(), rpcClient.maxAttempts());
            replyMessageListenerContainer(rpcName, syncSender);
            return syncSender;
        }
        return asyncSender(rpcName);
    }

    /**
     * 实例化 replyQueue
     */
    private Queue replyQueue(String rpcName, String rabbitClientId) {
        return registerBean(RpcType.SYNC.getValue() + "_" + rpcName + "_ReplyQueue", Queue.class, rpcName + ".reply." + rabbitClientId, true, false, false);
    }

    /**
     * 实例化 ReplyBinding
     */
    private void replyBinding(String rpcName, Queue queue) {
        registerBean(RpcType.SYNC.getValue() + "_" + rpcName + "_ReplyBinding", Binding.class, queue.getName(), Binding.DestinationType.QUEUE, getSyncReplyDirectExchange().getName(), queue.getName(), Collections.<String, Object>emptyMap());
    }

    /**
     * 实例化 ReplyMessageListenerContainer
     */
    private void replyMessageListenerContainer(String rpcName, RabbitTemplate syncSender) {
        SimpleMessageListenerContainer replyMessageListenerContainer = registerBean(RpcType.SYNC.getValue() + "_" + rpcName + "_ReplyMessageListenerContainer", SimpleMessageListenerContainer.class, connectionFactory);
        replyMessageListenerContainer.setQueueNames(rpcName);
        replyMessageListenerContainer.setMessageListener(syncSender);
    }

    /**
     * 实例化 AsyncSender
     */
    private RabbitTemplate asyncSender(String rpcName) {
        RabbitTemplate asyncSender = registerBean(RpcType.ASYNC.getValue() + "_" + rpcName + "_Sender", RabbitTemplate.class, connectionFactory);
        asyncSender.setDefaultReceiveQueue(rpcName + ".async");
        asyncSender.setRoutingKey(rpcName + ".async");
        return asyncSender;
    }

    /**
     * 实例化 SyncSender
     */
    private RabbitTemplate syncSender(String rpcName, Queue replyQueue, int replyTimeout, int maxAttempts) {
        SimpleRetryPolicy simpleRetryPolicy = new SimpleRetryPolicy();
        simpleRetryPolicy.setMaxAttempts(maxAttempts);
        RetryTemplate retryTemplate = new RetryTemplate();
        retryTemplate.setRetryPolicy(simpleRetryPolicy);
        RabbitTemplate syncSender = registerBean(RpcType.SYNC.getValue() + "_" + rpcName + "_Sender", RabbitTemplate.class, connectionFactory);
        syncSender.setDefaultReceiveQueue(rpcName);
        syncSender.setRoutingKey(rpcName);
        syncSender.setReplyAddress(replyQueue.getName());
        syncSender.setReplyTimeout(replyTimeout);
        syncSender.setRetryTemplate(retryTemplate);
        return syncSender;
    }

    /**
     * 实例化 SyncReplyDirectExchange
     */
    private DirectExchange getSyncReplyDirectExchange() {
        if (this.syncReplyDirectExchange == null) {
            this.syncReplyDirectExchange = registerBean("syncReplyDirectExchange", DirectExchange.class, "simple.rpc.sync.reply", true, false);
        }
        return this.syncReplyDirectExchange;
    }

    /**
     * 对象实例化并注册到Spring上下文
     */
    private <T> T registerBean(String name, Class<T> clazz, Object... args) {
        BeanDefinitionBuilder beanDefinitionBuilder = BeanDefinitionBuilder.genericBeanDefinition(clazz);
        if (args != null && args.length > 0) {
            for (Object arg : args) {
                beanDefinitionBuilder.addConstructorArgValue(arg);
            }
        }
        BeanDefinition beanDefinition = beanDefinitionBuilder.getRawBeanDefinition();
        BeanDefinitionRegistry beanDefinitionRegistry = (BeanDefinitionRegistry) beanFactory;
        if (beanDefinitionRegistry.isBeanNameInUse(name)) {
            throw new RuntimeException("Bean: " + name + " 实例化时发生重复");
        }
        beanDefinitionRegistry.registerBeanDefinition(name, beanDefinition);
        return beanFactory.getBean(name, clazz);
    }

}
