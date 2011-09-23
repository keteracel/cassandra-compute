package com.keteracel.cassandra.compute.thrift.mapper;

import net.sf.cglib.proxy.Callback;

public class Thriftified<T> implements Callback {

	private final T instance;

	public Thriftified(T instance) {
		this.instance = instance;
	}
	
}
