# XiannnAddons

A modular addon plugin for Paper 1.21.8

## Features

### Modular Architecture
XiannnAddons uses a modular design that allows you to enable or disable individual features:

- **ChatReminder** - Broadcasts configurable tip messages on a timer with support for legacy color codes, hex colors, and MiniMessage formatting
- **ItemCleanup** - Automatically removes dropped items from loaded chunks with configurable intervals, boss bars, action bar warnings, and a whitelist system

### Core Features

- **Dynamic Module System** - Enable/disable modules without restarting the server
- **Per-Module Logging** - Toggle logging for each module individually
- **Command Aliases** - Create custom command shortcuts
- **Hot Reload** - Reload configurations without restarting
- **Player Preferences** - Players can toggle certain features on/off

## Commands

| Command | Description |
|---------|-------------|
| `/xiannnaddons` or `/xa` | Main command |
| `/xa help` | Show help menu |
| `/xa reload` | Reload all configurations |
| `/xa modules` | List all modules and their status |
| `/xa enable <module>` | Enable a module |
| `/xa disable <module>` | Disable a module |
| `/xa version` | Show plugin version info |
| `/xa log` | View and toggle logging settings |

### Module Commands

**ChatReminder:**
- `/tips toggle` - Toggle tips on/off for yourself
- `/tips reload` - Reload tip messages

**ItemCleanup:**
- `/cleanup now` - Force an immediate cleanup
- `/cleanup status` - View cleanup statistics
- `/cleanup whitelist add <material>` - Add item to whitelist
- `/cleanup whitelist remove <material>` - Remove item from whitelist
- `/cleanup whitelist list` - List all whitelisted items
- `/cleanup reload` - Reload cleanup configuration
- `/cleanup bossbar` - Toggle boss bar display
- `/cleanup actionbar` - Toggle action bar display

## Permissions

| Permission | Description |
|------------|-------------|
| `xiannnaddons.admin` | Full admin access |
| `xiannnaddons.reload` | Reload configurations |
| `xiannnaddons.modules` | Manage modules |
| `xiannnaddons.tips.toggle` | Toggle tips |
| `xiannnaddons.tips.reload` | Reload tips |
| `xiannnaddons.cleanup.now` | Force cleanup |
| `xiannnaddons.cleanup.status` | View cleanup stats |
| `xiannnaddons.cleanup.whitelist` | Manage whitelist |
| `xiannnaddons.cleanup.reload` | Reload cleanup config |
| `xiannnaddons.cleanup.toggle` | Toggle bossbar/actionbar |

## Configuration

Configuration files are located in `plugins/XiannnAddons/`:

- `config.yml` - Main plugin configuration
- `ChatReminder/config.yml` - Chat reminder settings
- `ChatReminder/messages/` - Tip message files
- `ItemCleanup/config.yml` - Item cleanup settings

### ChatReminder Message Format

Tip messages support multiple formatting systems:
- **Legacy color codes**: `&6Hello &cWorld`
- **Hex colors**: `&#FF6B6BHello`
- **MiniMessage**: `<gradient:gold:yellow>Text</gradient>`

### ItemCleanup Whitelist

The whitelist preserves items from cleanup:
- Specific materials (e.g., SHULKER_BOX, ELYTRA)
- Enchanted items (configurable)
- Named items (configurable)

## Building

Requirements:
- Java 21
- Maven

```bash
mvn clean package
```

The compiled JAR will be in the `target/` directory.

## Installation

1. Download the latest release
2. Place the JAR file in your server's `plugins/` folder
3. Restart or reload the server
4. Configure modules in `plugins/XiannnAddons/`

## License

MIT License
