package com.keteracel.cassandra.compute.thrift.mapper;

import java.util.HashMap;
import java.util.Map;

import com.keteracel.cassandra.compute.thrift.annotations.ThriftInterface;

public class Thriftify {

	public Thriftify() {
	}

	private final Map<Class<?>,Object> instances = new HashMap<Class<?>, Object>();
	public <T> T maybeProxy(Class<T> type) {
		Object o = instances.get(type);
		if (o != null) {
			return (T) o;
		}
		
		if (type.getAnnotation(ThriftInterface.class) == null) {
			return instantiate(type);
		} else {
			return proxy(type);
		}
	}
	
	private <T> T instantiate(Class<T> type) {
		return null;
	}
	
	private <T> T proxy(Class<T> type) {
		return null;
	}

}
