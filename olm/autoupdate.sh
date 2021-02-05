#!/bin/sh
set -e
DOCKER=${DOCKER:-podman}
SED=sed
if [[ "$OSTYPE" == "darwin"* ]]; then
    SED=gsed
fi

cd olm

echo "Quay Login"
#${DOCKER} login quay.io -u=$1 -p=$2

echo "Updating version number"
oldnum=`cut -d '.' -f3 version`  
newnum=`expr $oldnum + 1`
${SED} -i "s/$oldnum\$/$newnum/g" version
version=`cat version`  

echo "Creating bundle"
mkdir olm-catalog/kas-fleetshard-operator/$version || true
cp -r olm-template/* olm-catalog/kas-fleetshard-operator/$version
${SED} -i "s/{{version}}/$version/g" olm-catalog/kas-fleetshard-operator/$version/manifests/kas-fleetshard-operator.clusterserviceversion.yaml 

echo "Building bundle"
${DOCKER} build -f olm-catalog/kas-fleetshard-operator/$version/Dockerfile olm-catalog/kas-fleetshard-operator/$version -t quay.io/k_wall/kas-fleetshard-operator-bundle:$version
${DOCKER} push quay.io/k_wall/kas-fleetshard-operator-bundle:$version

echo "Pushing updates back to github"
#git config --local user.email "supittma.bot@redhat.com"
#git config --local user.name "github-actions[bot]"
#git add olm-catalog/kas-fleetshard-operator/$version
#git commit -m "Autobump version" -a
