package vip.toby.rpc.client;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.AnnotatedBeanDefinition;
import org.springframework.beans.factory.config.BeanDefinitionHolder;
import org.springframework.beans.factory.support.AbstractBeanDefinition;
import org.springframework.beans.factory.support.BeanDefinitionRegistry;
import org.springframework.beans.factory.support.GenericBeanDefinition;
import org.springframework.context.annotation.ClassPathBeanDefinitionScanner;
import org.springframework.core.type.filter.AnnotationTypeFilter;
import vip.toby.rpc.annotation.RpcClient;

import java.util.Arrays;
import java.util.Set;

/**
 * ClassPathRpcClientScanner
 *
 * @author toby
 */
public class ClassPathRpcClientScanner extends ClassPathBeanDefinitionScanner {

    private static final Logger LOGGER = LoggerFactory.getLogger(ClassPathRpcClientScanner.class);

    ClassPathRpcClientScanner(BeanDefinitionRegistry registry) {
        super(registry, false);
    }

    void registerFilters() {
        addIncludeFilter(new AnnotationTypeFilter(RpcClient.class));
    }

    @Override
    protected boolean isCandidateComponent(AnnotatedBeanDefinition beanDefinition) {
        return beanDefinition.getMetadata().isInterface() && beanDefinition.getMetadata().isIndependent();
    }

    @Override
    public Set<BeanDefinitionHolder> doScan(String... basePackages) {
        Set<BeanDefinitionHolder> beanDefinitions = super.doScan(basePackages);
        if (beanDefinitions.isEmpty()) {
            LOGGER.debug("No @RpcClient was found in '{}' package. Please check your configuration.", Arrays.toString(basePackages));
        } else {
            processBeanDefinitions(beanDefinitions);
        }
        return beanDefinitions;
    }

    private void processBeanDefinitions(Set<BeanDefinitionHolder> beanDefinitions) {
        GenericBeanDefinition rpcClientBeanDefinition;
        for (BeanDefinitionHolder holder : beanDefinitions) {
            rpcClientBeanDefinition = (GenericBeanDefinition) holder.getBeanDefinition();
            String beanClassName = rpcClientBeanDefinition.getBeanClassName();
            if (beanClassName != null) {
                // 获取真实接口class，并作为构造方法的参数
                rpcClientBeanDefinition.getConstructorArgumentValues().addGenericArgumentValue(beanClassName);
                // 修改类为 RpcClientProxyFactory
                rpcClientBeanDefinition.setBeanClass(RpcClientProxyFactory.class);
                // 采用按照类型注入的方式
                rpcClientBeanDefinition.setAutowireMode(AbstractBeanDefinition.AUTOWIRE_BY_TYPE);
            }
        }
    }

}
