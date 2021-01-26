#!/bin/bash

NAMESPACE=`kubectl config view --minify --output 'jsonpath={..namespace}'`

sed "s|_NAMESPACE_|${NAMESPACE}|g" src/main/kubernetes/clusterroles.yml | kubectl create -f - 