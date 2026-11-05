WEBSTORM ?= $(HOME)/Applications/WebStorm.app
JBR ?= $(WEBSTORM)/Contents/jbr/Contents/Home
GRADLE ?= $(firstword $(wildcard $(HOME)/.gradle/wrapper/dists/gradle-9.3.1-bin/*/gradle-9.3.1/bin/gradle))

.PHONY: build test sandbox compatibility sign
build: TASK = buildPlugin
test: TASK = test
sandbox: TASK = runIde
compatibility: TASK = verifyPlugin
sign: TASK = signPlugin verifyPluginSignature


build test sandbox compatibility sign:
	@test -n "$(GRADLE)" && test -x "$(GRADLE)" || { echo 'Set GRADLE to a Gradle 9.3.1 executable.' >&2; exit 1; }
	@test -x "$(JBR)/bin/java" || { echo 'Set JBR to the bundled JetBrains Runtime home.' >&2; exit 1; }
	JAVA_HOME="$(JBR)" "$(GRADLE)" --no-daemon -PwebstormPath="$(WEBSTORM)" $(TASK)
