package com.keteracel.cassandra.compute.thrift.mapper;

import org.springframework.beans.factory.NoSuchBeanDefinitionException;
import org.springframework.context.ApplicationContext;

/**
 * Thriftifier that grabs concrete services from Spring for thriftification.
 * 
 * @author Paul_Loy
 */
public class SpringThriftify extends Thriftify {

	private final ApplicationContext appContext;

	public SpringThriftify(ApplicationContext appContext) {
		this.appContext = appContext;
	}

	@Override
	protected synchronized <T> T instantiate(Class<T> type) {
		try {
			T object = appContext.getBean(type);
			return object;
		} catch (NoSuchBeanDefinitionException e) {
			return super.instantiate(type);
		}
	}

}
