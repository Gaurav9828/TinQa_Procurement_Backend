package com.tinqa.procurement.common.monitoring;

import net.ttddyy.dsproxy.support.ProxyDataSource;
import net.ttddyy.dsproxy.support.ProxyDataSourceBuilder;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;

/** Wraps the data source so every SQL statement a request runs is counted (see {@link ApiCallMonitorFilter}). */
@Component
@ConditionalOnProperty(prefix = "monitoring.api", name = "enabled", matchIfMissing = true)
public class QueryCountingDataSourcePostProcessor implements BeanPostProcessor {

    @Override
    public Object postProcessAfterInitialization(Object bean, String beanName) {
        if (!(bean instanceof DataSource dataSource) || bean instanceof ProxyDataSource) {
            return bean;
        }
        return ProxyDataSourceBuilder.create(dataSource)
                .name(beanName)
                .afterQuery((execution, queries) -> RequestDbStats.record(
                        queries.isEmpty() ? "" : queries.get(0).getQuery(), execution.getElapsedTime()))
                .build();
    }
}
