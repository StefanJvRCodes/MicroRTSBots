# JAR Bot Support in MicroRTS Frontend

## Overview
You can now add .jar bot files to the AI combobox in the frontend! This feature allows you to select and play against external bot JAR files without needing to manually launch them.

## How It Works

### 1. **JAR Bot Detection**
- The system automatically scans the `lib/bots/` directory for `.jar` files
- Discovered bots appear in the Player AI combobox with a "JAR: " prefix
- Example: `lib/bots/Coac.jar` appears as "JAR: Coac" in the combobox

### 2. **Launching JAR Bots**
- When you select a JAR bot and start a game, the `JarBotLauncher` class:
  1. Launches the JAR file as a separate Java process
  2. Assigns it a unique socket port (starting from 9900)
  3. Connects to it via `SocketAI` for socket-based communication
  4. Manages the process lifecycle (cleanup on game end)

### 3. **Socket Communication**
- JAR bots communicate with MicroRTS via XML socket protocol
- Logs for each bot process are saved as `jarbot_<botname>_<port>.log`

## Implementation Details

### New Files
- **`src/ai/socket/JarBotLauncher.java`** - Wrapper class for launching and managing external JAR bots

### Modified Files
- **`src/gui/frontend/FEStatePane.java`**:
  - Added `discoverJarBots()` method to scan `lib/bots/` directory
  - Updated `discoverAIs()` to include jar bot paths
  - Modified combobox creation to include jar bot entries
  - Updated `createAIInternal()` to instantiate `JarBotLauncher` for jar bot selections
  - Updated `updateAIOptions()` to handle jar bots (no parameters to configure)

## Usage

1. Place your .jar bot files in the `lib/bots/` directory
2. Launch the MicroRTS frontend
3. In the Player AI dropdown, select a bot starting with "JAR: "
4. Click "Start" to begin playing
5. The bot process will be automatically launched and connected

## Requirements for JAR Bots

Your external JAR bot must:
1. Accept a port number as a command-line argument
2. Listen on that port for XML socket connections
3. Implement the MicroRTS socket protocol for receiving game state and sending actions
4. Be compatible with Java 8+ (or your current Java version)

## Socket Protocol

JAR bots communicate using the same socket protocol as `SocketAI`. For details on implementing a bot:
- See `src/ai/socket/SocketAI.java` for the protocol specification
- Review `src/ai/socket/GameVisualSimulationWithSocketAI.java` for example usage

## Troubleshooting

- **Bot doesn't start**: Check `jarbot_<botname>_<port>.log` for error messages
- **Connection timeout**: Ensure the bot is listening on the assigned port
- **Protocol errors**: Verify your bot implements the correct socket communication protocol
