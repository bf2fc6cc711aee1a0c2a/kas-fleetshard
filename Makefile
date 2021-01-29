ROOT_DIR = $(shell dirname $(realpath $(lastword $(MAKEFILE_LIST))))
include $(ROOT_DIR)/Makefile.env.mk

all: clean build unit-test

clean:
	mvn clean

build:
	mvn package -DskipTests

unit-test:
	mvn test

test:
	mvn verify -pl systemtest

agent-dev:
	make QUARKUS_KUBERNETES_CLIENT_TRUST_CERTS=true -C agent-operator dev

agent-image-build:
	make -C agent-operator build-image

.PHONY: clean build unit-test test agent-dev agent-image-build