package org.bf2.operator.resources.v1alpha1;

import io.sundr.builder.annotations.Buildable;

@Buildable
public class ClusterResizeInfo {
	private int nodeDelta;
	private ClusterCapacity delta;
	
	public int getNodeDelta() {
		return nodeDelta;
	}
	
	public void setNodeDelta(int nodeDelta) {
		this.nodeDelta = nodeDelta;
	}
	
	public ClusterCapacity getDelta() {
		return delta;
	}
	
	public void setDelta(ClusterCapacity delta) {
		this.delta = delta;
	}
}
