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

.PHONY: help build clean run train autopilot play

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

# Trains the whole opponent/map grid, splitting off and specialising stalled matchups by itself.
autopilot: build
	$(JAVA) $(JAVA_OPTS) -cp "$(CLASSPATH)" ai.evolution.gp.GPAutopilot $(GP_ARGS)

play: build
	$(JAVA) $(JAVA_OPTS) -cp "$(CLASSPATH)" ai.evolution.gp.GPPlay $(PLAY_ARGS)

.PHONY: holdout
holdout: build
	$(JAVA) $(JAVA_OPTS) -cp "$(CLASSPATH)" ai.evolution.gp.GPPlay --holdout=true $(PLAY_ARGS)
