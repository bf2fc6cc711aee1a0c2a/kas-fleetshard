#!/bin/bash

NAMESPACE=`oc project --short`

sed "s|_NAMESPACE_|${NAMESPACE}|g" src/main/kubernetes/clusterroles.yml | oc create -f - 