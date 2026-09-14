JAVAC       := javac
JAVA        := java
JAVA_OPTS   ?=
GP_ARGS     ?=
PLAY_ARGS   ?=
SRC_DIR     := src
BIN_DIR     := bin
LIBS        := lib/*
CLASSPATH   := $(BIN_DIR):$(LIBS)
SOURCES     := $(shell find $(SRC_DIR) -name "*.java")
STAMP       := $(BIN_DIR)/.compiled

.PHONY: help build clean run train play holdout

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

play: build
	$(JAVA) $(JAVA_OPTS) -cp "$(CLASSPATH)" ai.evolution.gp.GPPlay $(PLAY_ARGS)

holdout: build
	$(JAVA) $(JAVA_OPTS) -cp "$(CLASSPATH)" ai.evolution.gp.GPPlay --playHoldout=true $(PLAY_ARGS)

help:
	@echo "make build    compile everything into bin/"
	@echo "make run      start microRTS (resources/config.properties)"
	@echo "make train    evolve a bot           GP_ARGS=\"--populationSize=500 --generations=100\" or GP_ARGS=--help"
	@echo "make play     benchmark a saved bot  PLAY_ARGS=\"--playBotFile=runs/<id>/best.txt\""
	@echo "make holdout  benchmark on unseen maps and opponents"
	@echo "make clean    remove bin/"
