package org.bf2.operator.resources.v1alpha1;

public class ManagedKafkaAgentSpec {

	// to use to kick off a status of cluster
	long version;  

	public long getVersion() {
		return version;
	}

	public void setVersion(long version) {
		this.version = version;
	}
	
	@Override
	public int hashCode() {
		final int prime = 31;
		int result = 1;
		result = prime * result + (int) (version ^ (version >>> 32));
		return result;
	}

	@Override
	public boolean equals(Object obj) {
		if (this == obj)
			return true;
		if (obj == null)
			return false;
		if (getClass() != obj.getClass())
			return false;
		ManagedKafkaAgentSpec other = (ManagedKafkaAgentSpec) obj;
		if (version != other.version)
			return false;
		return true;
	}	
}
