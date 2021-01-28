# agent-sync

Responsible for communications between the agent and the control plane.

See https://issues.redhat.com/browse/MGDSTRM-1279

- the agent-operator is added as a sidecar in the agent-operator application.properties.  There may need to be a different file/profile for deployment of the operator without the sidecar.
