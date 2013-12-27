
help:
	@echo
	@echo 'Usage: make {boot|help}'
	@echo
	@echo 'Targets:'
	@echo '  boot         Create executable boot jar file.'
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
	@echo "*** Done. Created boot executable: ./boot ***"
