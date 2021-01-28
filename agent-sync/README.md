# agent-sync

Responsible for communications between the agent and the control plane.

See https://issues.redhat.com/browse/MGDSTRM-1279

- the agent-operator is added as a sidecar in the agent-operator application.properties.  There may need to be a different file/profile for deployment of the operator without the sidecar.

- Based upon the current design thinking the full remote state, including status, is returned on each poll.  So we are saving that locally to compare with update events from the informer.  An alternative would be to assume that a successful put/patch is guaranteed to work, in which case we could use the CompletionStage to update the cached state.
