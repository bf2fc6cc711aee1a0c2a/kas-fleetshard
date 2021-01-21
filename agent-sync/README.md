# agent-sync

- the agent-operator will need to declare the usage of this sidecar.  I added the kubernetes-annotations jar for that, but realized there nowhere to use that given the operator framework.  So that's probably not the right approach - maybe just configuration is all that is needed.

Presuming that the architecture will follow the path of a kube connection from the agent sync to the control plane we have:

- This module will likely need to depend upon the agent module as we'll be shared the ManagedKafka cr.

- For the kube connection to control plane it's not clear yet how that will happen.  In the proposal docs it's mentioned that the credentials would be rotated.  Presumably there will be a Secret that will need to be checked on connection failure or something.

- The full set of information that the control plane needs should be captured somewhere.  The agent sync may be able to just copy the full status section of the ManagedKafka cr, so there may not need to be deep logic here.