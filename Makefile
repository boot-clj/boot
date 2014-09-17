.PHONY: help install deploy test

bootjar    = $(PWD)/bin/boot2
bootbin    = $(PWD)/bin/boot.sh
bootexe    = $(PWD)/bin/boot.exe
bootjarurl = https://github.com/tailrecursion/boot/releases/download/p1/boot
podjar     = boot/pod/target/pod-2.0.0-SNAPSHOT.jar
aetherjar  = boot/aether/target/aether-2.0.0-SNAPSHOT.jar
aetheruber = aether-2.0.0-SNAPSHOT.uber.jar
workerjar  = boot/worker/target/worker-2.0.0-SNAPSHOT.jar
corejar    = boot/core/target/core-2.0.0-SNAPSHOT.jar
basejar    = boot/base/target/base-2.0.0-SNAPSHOT.jar
baseuber   = boot/base/target/base-2.0.0-SNAPSHOT-jar-with-dependencies.jar
alljars    = $(podjar) $(aetherjar) $(workerjar) $(corejar) $(baseuber)

help:
	@echo "Usage: make {help|install|deploy|test}" 1>&2 && false

bin/lein:
	mkdir -p bin
	wget https://raw.githubusercontent.com/technomancy/leiningen/stable/bin/lein -O bin/lein
	chmod 755 bin/lein

$(podjar): boot/pod/project.clj $(shell find boot/pod/src)
	(cd boot/pod && lein clean && lein install)

$(aetherjar): boot/aether/project.clj $(podjar) $(shell find boot/aether/src)
	(cd boot/aether && lein clean && lein install && lein uberjar && \
		mkdir -p ../base/src/main/resources && \
	 	cp target/*standalone*.jar ../base/src/main/resources/$(aetheruber))

$(workerjar): boot/worker/project.clj $(shell find boot/worker/src)
	(cd boot/worker && lein clean && lein install)

$(corejar): boot/core/project.clj $(shell find boot/core/src)
	(cd boot/core && lein clean && lein install)

$(baseuber): boot/base/pom.xml $(shell find boot/base/src)
	(cd boot/base && mvn -q clean && mvn -q assembly:assembly -DdescriptorId=jar-with-dependencies)

$(bootbin): $(baseuber)
	mkdir -p bin
	echo '#!/usr/bin/env bash' > $(bootbin)
	echo 'DFL_OPTS="-client -XX:+TieredCompilation -XX:TieredStopAtLevel=1 -Xmx2g -XX:MaxPermSize=128m -XX:+UseConcMarkSweepGC -XX:+CMSClassUnloadingEnabled"' >> $(bootbin)
	echo 'java $${BOOT_JVM_OPTIONS:-$$DFL_OPTS} -jar $$0 "$$@"' >> $(bootbin)
	echo 'exit' >> $(bootbin)
	cat $(baseuber) >> $(bootbin)
	chmod 0755 $(bootbin)
	@echo "*** Created boot executable: $(bootbin) ***"

$(bootexe): $(baseuber)
	@if [ -z $$RUNNING_IN_CI ] && which launch4j; then \
		launch4j launch4j-config.xml; \
		echo "*** Created boot executable: $(bootexe) ***"; \
	else true; fi

.installed: $(alljars) $(bootbin) $(bootexe)
	date > .installed

install: .installed

.deployed: .installed
	(cd boot/pod    && lein push)
	(cd boot/aether && lein push)
	(cd boot/worker && lein push)
	(cd boot/core   && lein push)
	(cd boot/base   && scp pom.xml target/base-2.0.0-SNAPSHOT.jar clojars@clojars.org:)
	date > .deployed

deploy: .deployed

test:
	rm -rf test
	mkdir -p test/1/boot-project/src
	echo "hi there" > test/1/boot-project/src/hello.txt
	(cd test/1/boot-project \
		&& $(bootbin) -d org.clojure/clojure:1.6.0 -s src -- speak -t ordinance -- pom -p boot-project -v 0.1.0 -- jar -M Foo=bar -- uberjar -- war)# \
		&& jar tf target/boot-project-0.1.0.jar | grep '^META-INF/MANIFEST.MF$$' \
		&& unzip -p target/boot-project-0.1.0.jar META-INF/MANIFEST.MF | grep '^Foo: bar' \
		&& echo TEST 1 OK) \
		|| (echo TEST 1 FAIL 1>&2; false)
