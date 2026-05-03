# ElpixEdge Plugin Usage

## Installation
1. Build: `mvn clean package`
2. Copy `target/ElpixEdge-1.0-SNAPSHOT.jar` to your server's `plugins/` folder
3. Restart server (creates default configs)

## Commands

| Command | Description |
|---------|-------------|
| `/status` | Check your status |
| `/edgeitem` | Manage and spawn custom items |
| `/edgemob` | Spawn and manage custom mobs |
| `/edgeinstance <enter\|leave> [id]` | Enter/leave private instances |
| `/edgescene <play\|stop> [id]` | Play or stop cutscenes |
| `/edgetag <add\|remove\|list> <player> [tag]` | Manage player tags |
| `/claimstash` | Claim temporary items from stash |
| `/admin_book` | Open admin options book |
| `/custom_holo` | Toggle custom holograms |

## Features

- **Custom Items**: Aspect of the End, Shadow Fury, magic staves, grimoires, spell scrolls
- **Custom Mobs**: Apex Enderman, Blighted Wyvern, Ruined Skeleton (configured in `config.yml`)
- **Skill System**: Combat, Mining, Gathering, Arcane, Enchantment with leveling
- **Custom Enchants**: Sharpness, Smite, Bane, Fire Aspect, Unbreaking, Efficiency, Aiming, Life Steal, Nullify
- **Collections**: Track mob kills/block breaks to unlock recipes
- **Instances**: Private instanced areas with schematics
- **Quest System**: Trigger-based quests with tags
- **Spawners**: Location-based custom mob spawning with levels and tiers

## Configuration

Edit `plugins/ElpixEdge/config.yml` after first run. Key sections:
- `game_settings` - Skill cooldowns
- `leveling` - XP formulas
- `skills` - Stat gains per skill
- `mobs` - Custom mob definitions and drops
- `collections` - Collection goals
- `enchants` - Enchantment definitions
- `items` - Custom item stats
- `spawners` - Mob spawner locations

## Dependencies (Optional)
- **ProtocolLib** - Enhanced packet handling
- **BetterModel** - Custom mob models
- **FancyNpcs** - NPC integration

Plugin works without them; features degrade gracefully.
