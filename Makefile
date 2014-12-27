.PHONY: help deps install deploy test clean

SHELL       := /bin/bash
export PATH := bin:l4j:$(PATH)

green        = '\e[0;32m'
nc           = '\e[0m'

version      = $(shell grep ^version version.properties |sed 's/.*=//')
verfile      = version.properties
bootbin      = $(PWD)/bin/boot.sh
bootexe      = $(PWD)/bin/boot.exe
bootjarurl   = https://github.com/boot-clj/boot/releases/download/p1/boot
bootjar      = boot/boot/target/boot-$(version).jar
podjar       = boot/pod/target/pod-$(version).jar
aetherjar    = boot/aether/target/aether-$(version).jar
aetheruber   = aether.uber.jar
workerjar    = boot/worker/target/worker-$(version).jar
corejar      = boot/core/target/core-$(version).jar
basejar      = boot/base/target/base-$(version).jar
baseuber     = boot/base/target/base-$(version)-jar-with-dependencies.jar
alljars      = $(podjar) $(aetherjar) $(workerjar) $(corejar) $(baseuber) $(bootjar)

help:
	@echo "version =" $(version)
	@echo "Usage: make {help|deps|install|deploy|test|clean}" 1>&2 && false

clean:
	(cd boot/base && mvn -q clean)
	(cd boot/core && lein clean)
	(cd boot/aether && lein clean)
	(cd boot/pod && lein clean)
	(cd boot/worker && lein clean)

bloop:
	which lein

bin/lein:
	mkdir -p bin
	wget https://raw.githubusercontent.com/technomancy/leiningen/stable/bin/lein -O bin/lein
	chmod 755 bin/lein

deps: bin/lein

$(bootjar): $(verfile) boot/boot/project.clj
	(cd boot/boot && lein install)

boot/base/pom.xml: $(verfile) boot/base/pom.in.xml
	(cd boot/base && cat pom.in.xml |sed 's/__VERSION__/$(version)/' > pom.xml)

$(basejar): boot/base/pom.xml $(shell find boot/base/src/main/java)
	(cd boot/base && mvn -q install)

$(podjar): $(verfile) boot/pod/project.clj $(shell find boot/pod/src)
	(cd boot/pod && lein install)

$(aetherjar): $(verfile) boot/aether/project.clj $(podjar) $(shell find boot/aether/src)
	(cd boot/aether && lein install && lein uberjar && \
		mkdir -p ../base/src/main/resources && \
		cp target/aether-$(version)-standalone.jar ../base/src/main/resources/$(aetheruber))

$(workerjar): $(verfile) boot/worker/project.clj $(shell find boot/worker/src)
	(cd boot/worker && lein install)

$(corejar): $(verfile) boot/core/project.clj $(shell find boot/core/src)
	(cd boot/core && lein install)

$(baseuber): boot/base/pom.xml $(shell find boot/base/src/main)
	(cd boot/base && mvn -q assembly:assembly -DdescriptorId=jar-with-dependencies)

$(bootbin): head.sh $(baseuber)
	mkdir -p bin
	cat head.sh $(baseuber) > $(bootbin)
	chmod 0755 $(bootbin)
	@echo -e "\033[0;32m<< Created boot executable: $(bootbin) >>\033[0m"

$(bootexe): $(baseuber)
	@if [ -z $$RUNNING_IN_CI ] && which launch4j; then \
		sed -e "s@__VERSION__@`cat version.properties |sed 's/.*=//'`@" \
			launch4j-config.in.xml > launch4j-config.xml; \
		launch4j launch4j-config.xml; \
		echo -e "\033[0;32m<< Created boot executable: $(bootexe) >>\033[0m"; \
		[ -e $(bootexe) ] && touch $(bootexe); \
	else true; fi

.installed: $(basejar) $(alljars) $(bootbin) $(bootexe)
	date > .installed

install: .installed

.deployed: .installed
	(cd boot/base   && lein deploy clojars boot/base $(version) target/base-$(version).jar pom.xml)
	(cd boot/pod    && lein deploy clojars)
	(cd boot/aether && lein deploy clojars)
	(cd boot/worker && lein deploy clojars)
	(cd boot/core   && lein deploy clojars)
	(cd boot/boot   && lein deploy clojars)
	date > .deployed

deploy: .deployed

test:
	echo "<< no tests yet >>"
