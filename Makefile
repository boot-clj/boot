.PHONY: help deps install deploy test clean

SHELL       := /bin/bash
export PATH := bin:launch4j:$(PATH)

version      = $(shell grep ^version version.properties |sed 's/.*=//')
verfile      = version.properties
distjar      = $(PWD)/bin/boot.jar
aetheruber   = boot/base/resources/boot-aether-$(version)-uber.jar
basejar      = target/base/boot-base-$(version).jar
podjar       = target/pod/boot-pod-$(version).jar
corejar      = target/core/boot-core-$(version).jar
workerjar    = target/worker/boot-worker-$(version).jar
aetherjar    = target/aether/boot-aether-$(version).jar
txjar        = target/transaction-jar/boot-$(version).jar
baseuber     = $(PWD)/target/boot-base-$(version)-uber.jar
alljars      = $(podjar) $(aetherjar) $(workerjar) $(corejar) $(baseuber) $(txjar)

help:
	@echo "version =" $(version)
	@echo "Usage: make {help|deps|install|deploy|test|clean}" 1>&2 && false

bin/boot:
	mkdir -p bin
	curl -L https://github.com/boot-clj/boot/releases/download/2.4.0/boot.sh > bin/boot
	chmod 755 bin/boot

deps: bin/boot

clean:
	(rm -rf target/*)
	(if [ -a $(aetheruber) ]; then rm $(aetheruber); fi;)

$(txjar): $(verfile)
	(boot transaction-jar install)

$(basejar): $(shell find boot/base/src)
	(boot base install)

$(podjar): $(verfile) $(shell find boot/pod/src)
	(boot pod install)

$(aetherjar): $(verfile) $(podjar) $(shell find boot/aether/src)
	(boot aether install)

$(workerjar): $(verfile) $(shell find boot/worker/src)
	(boot worker install)

$(corejar): $(verfile) $(shell find boot/core/src)
	(boot core install)

$(aetheruber):
	(boot aether --uberjar)

$(baseuber): $(aetheruber) $(shell find boot/base/src)
	(boot base --uberjar install-boot-jar)

.installed: $(basejar) $(alljars)
	date > .installed

install: .installed

.deployed:
	(boot base push-release)
	(boot pod push-release)
	(boot aether push-release)
	(boot worker push-release)
	(boot core push-release)
	(boot transaction-jar push-release)
	date > .deployed

deploy: .deployed

test:
	echo "<< no tests yet >>"
