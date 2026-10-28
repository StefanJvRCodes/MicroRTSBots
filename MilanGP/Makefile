JAVAC       := javac
JAVA        := java
JAVA_OPTS   ?=
GP_ARGS     ?=
PLAY_ARGS   ?=
RUNS        ?= 50
BATCH       ?= batch
SRC_DIR     := src
BIN_DIR     := bin
LIBS        := lib/*:lib/bots/*
CLASSPATH   := $(BIN_DIR):$(LIBS)
SOURCES     := $(shell find $(SRC_DIR) -name "*.java")
STAMP       := $(BIN_DIR)/.compiled

.PHONY: help build clean run train train-cgp train-sbgp train-batch stats play holdout

.DEFAULT_GOAL := help

build: $(STAMP)

$(STAMP): $(SOURCES)
	mkdir -p $(BIN_DIR)
	$(JAVAC) -nowarn -d $(BIN_DIR) -cp "$(LIBS)" $(SOURCES)
	touch $(STAMP)

clean:
	rm -rf $(BIN_DIR)

run: build
	$(JAVA) -cp "$(CLASSPATH)" rts.MicroRTS

train: build
	$(JAVA) $(JAVA_OPTS) -cp "$(CLASSPATH)" ai.evolution.gp.GPTrain $(GP_ARGS)

train-cgp: build
	$(JAVA) $(JAVA_OPTS) -cp "$(CLASSPATH)" ai.evolution.gp.GPTrain --structureBased=false $(GP_ARGS)

train-sbgp: build
	$(JAVA) $(JAVA_OPTS) -cp "$(CLASSPATH)" ai.evolution.gp.GPTrain --structureBased=true $(GP_ARGS)

# Runs RUNS trainings with seeds 1..RUNS into runs/$(BATCH)/run-<seed>. Finished runs are skipped,
# so rerunning the same command after an interruption picks up where it stopped.
train-batch: build
	for i in $$(seq 1 $(RUNS)); do \
		[ -f runs/$(BATCH)/run-$$i/summary.properties ] && continue; \
		$(JAVA) $(JAVA_OPTS) -cp "$(CLASSPATH)" ai.evolution.gp.GPTrain \
			--runId=$(BATCH)/run-$$i --randomSeed=$$i $(GP_ARGS) || exit 1; \
	done
	$(MAKE) --no-print-directory stats

stats: build
	$(JAVA) -cp "$(CLASSPATH)" ai.evolution.gp.GPStats runs/$(BATCH)

play: build
	$(JAVA) $(JAVA_OPTS) -cp "$(CLASSPATH)" ai.evolution.gp.GPPlay $(PLAY_ARGS)

holdout: build
	$(JAVA) $(JAVA_OPTS) -cp "$(CLASSPATH)" ai.evolution.gp.GPPlay --playHoldout=true $(PLAY_ARGS)

help:
	@echo "make build    compile everything into bin/"
	@echo "make run      start microRTS (resources/config.properties)"
	@echo "make train    evolve a bot           GP_ARGS=\"--populationSize=500 --generations=100\" or GP_ARGS=--help"
	@echo "make train-cgp   evolve the ordinary GP baseline (the control arm)"
	@echo "make train-sbgp  evolve the structure-based GP bot (the treatment arm)"
	@echo "make train-batch  RUNS=50 BATCH=<name> independent runs (seeds 1..RUNS), then stats"
	@echo "make stats    mean/std across the runs in runs/\$$(BATCH)"
	@echo "make play     benchmark a saved bot  PLAY_ARGS=\"--playBotFile=runs/<id>/best.txt\""
	@echo "make holdout  benchmark on unseen maps and opponents"
	@echo "make clean    remove bin/"
