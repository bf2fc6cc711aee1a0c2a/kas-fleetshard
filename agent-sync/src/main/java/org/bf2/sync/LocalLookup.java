package org.bf2.sync;

import org.bf2.operator.resources.v1alpha1.ManagedKafka;

public interface LocalLookup {

	ManagedKafka getLocalManagedKafka(ManagedKafka remote);
	
	//getLocalManagedKafkaAgent
}
