# agent-sync

- the agent-operator will need to declare the usage of this sidecar.  I added the kubernetes-annotations jar for that, but realized there nowhere to use that given the operator framework.  So that's probably not the right approach - maybe just configuration is all that is needed.

- This module will likely need to depend upon the agent module as we'll be shared the ManagedKafka cr.

Presuming that the architecture of an http connection from agent-sync to the control plane:


- The full set of information that the control plane needs should be captured somewhere.  The agent sync may be able to just copy the full status section of the ManagedKafka cr, so there may not need to be deep logic here.  The Agent resource should also provide status containing the used/available managed kafka units.

- From https://kubernetes.io/docs/reference/using-api/api-concepts/ there is a Reflector concept that can be used for watching / Storing an object.  There is an even higher level SharedInformer.