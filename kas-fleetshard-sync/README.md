# kas-fleetshard-sync

Responsible for communications between the operator and the control plane.

There are two main processing activities:

- Poll the control plane and sync to the remote state

- Process local events and push those updates to the control plane, currently in the Informer package.

## Notes

- the kas-fleetshard-sync will eventually be added as a sidecar in the operator application.properties.  There may need to be a different file/profile for deployment of the operator without the sidecar.

- for now we're blocked on issues with the properties driven sidecar, so we'll just provide instructions for running as a separate deployment.