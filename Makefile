.DEFAULT_GOAL := help

.PHONY: help
help: ## show this help
	@awk 'BEGIN {FS = ":.*##"; print "usage: make <target>\n"} \
		/^##@/ { printf "\n\033[1m%s:\033[0m\n", substr($$0, 5); next } \
		/^[a-zA-Z0-9_.-]+:.*##/ { printf "  \033[36m%-16s\033[0m %s\n", $$1, $$2 }' $(MAKEFILE_LIST)

.PHONY: build test check-keys lock format scripts-test release publish publish-local

##@ Building and checking
build: ## check formatting, compile with error prone and nullaway, test, check declared dependencies; TESTS_JDK=21 tests on that JDK
	./gradlew build buildHealth $(if $(TESTS_JDK),-PtestsJdk=$(TESTS_JDK))

test: ## run the tests
	./gradlew test

check-keys: ## check the banks' DKIM keys in live DNS, through DNSSEC, against the ones pinned in the code
	./gradlew :modules:bank-notifications:checkKeys

lock: ## rewrite modules/bank-notifications/gradle.lockfile after a dependency changes, the vulnerability scan reads it
	./gradlew :modules:bank-notifications:dependencies --configuration runtimeClasspath --write-locks

format: ## reformat java and gradle files in place
	./gradlew spotlessApply

scripts-test: ## shellcheck every script, run every *.test.sh
	mise exec shellcheck -- shellcheck $$(git ls-files '*.sh')
	@rc=0; for t in $$(git ls-files '*.test.sh'); do echo "== $$t"; "./$$t" || { echo "FAIL $$t"; rc=1; }; done; exit $$rc

##@ Releasing
release: ## tag origin/main, asking which version, wait for release.yml to publish it, write the release page; TAG=v0.1.0 answers
	./bin/release.sh

publish: ## build and publish every jar to GitHub Packages, as the release workflow does; RELEASE_VERSION=v0.1.0 releases
	./gradlew build publish $(if $(RELEASE_VERSION),-PreleaseVersion=$(RELEASE_VERSION:v%=%))

publish-local: ## install every jar into ~/.m2, to try them in another project without a token
	./gradlew publishToMavenLocal
