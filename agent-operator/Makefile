ROOT_DIR = $(shell dirname $(realpath $(lastword $(MAKEFILE_LIST))))
include $(ROOT_DIR)/../Makefile.env.mk

dev:
	./mvnw quarkus:dev

build-image:
	./mvnw clean package -Dquarkus.container-image.build=true \
		-Dquarkus.container-image.registry=$(IMAGE_REGISTRY) \
		-Dquarkus.container-image.group=$(IMAGE_ORG) \
		-Dquarkus.container-image.name=agent-operator \
		-Dquarkus.container-image.insecure=true \
		-Dquarkus.container-image.push=$(IMAGE_PUSH)

.PHONY: agent-dev build-image