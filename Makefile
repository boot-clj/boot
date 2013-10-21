
help:
	@echo
	@echo 'Usage: make {boot|boot-faster}'
	@echo
	@echo 'Targets:'
	@echo '  boot         Create executable boot jar file.'
	@echo '  boot-faster  Create executable boot jar file with class data sharing.'
	@echo

clean:
	rm -f boot
	lein clean

build: clean
	lein uberjar

boot: build
	echo '#!/usr/bin/env bash' > boot
	echo 'java $$JVM_OPTS -jar $$0 "$$@"' >> boot
	echo 'exit' >> boot
	cat target/boot*-standalone.jar >> boot
	chmod 0755 boot
	@echo "*** Done. Copy ./boot to a directory in your PATH. ***"

boot-faster: build
	echo '#!/usr/bin/env bash' > boot
	echo 'java -Xshare:on -Xbootclasspath/a:$$0 tailrecursion.boot "$$@"' >> boot
	echo 'exit' >> boot
	cat target/boot*-standalone.jar >> boot
	chmod 0755 boot
	sudo java -Xshare:dump -Xbootclasspath/a:boot
	@echo "*** Done. Copy ./boot to a directory in your PATH. ***"
