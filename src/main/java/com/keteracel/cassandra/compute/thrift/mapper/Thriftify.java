package com.keteracel.cassandra.compute.thrift.mapper;

import java.util.HashMap;
import java.util.Map;

import com.keteracel.cassandra.compute.thrift.annotations.ThriftInterface;

/**
 * @author Paul_Loy
 */
public class Thriftify {

	public Thriftify() {
	}

	public <T> T maybeProxy(Class<T> type) {
		if (type.getAnnotation(ThriftInterface.class) == null) {
			return instantiate(type);
		} else {
			return proxy(type);
		}
	}
	
	private final Map<Class<?>,Object> instances = new HashMap<Class<?>, Object>();
	protected <T> T instantiate(Class<T> type) {
		Object o = instances.get(type);
		if (o != null) {
			return (T) o;
		}
		
		synchronized (instances) {
			o = instances.get(type);
			if (o != null) {
				return (T) o;
			}
			
			try {
				T object = type.getConstructor().newInstance();
				instances.put(type, object);
				return object;
			} catch (Exception e) {
				throw new RuntimeException("possibly no zero-args constructor on your service?", e);
			}
		}
	}
	
	private final Map<Class<?>,Object> proxies = new HashMap<Class<?>, Object>();
	private synchronized <T> T proxy(Class<T> type) {
		Object o = proxies.get(type);
		if (o != null) {
			return (T) o;
		}
		
		synchronized (proxies) {
			o = proxies.get(type);
			if (o != null) {
				return (T) o;
			}
			
			T t = wrap(instantiate(type));
			proxies.put(type, t);
			return t;
		}
	}
	
	public synchronized <T> T wrap(T object) {
		//TODO:
		return null;
	}

}
