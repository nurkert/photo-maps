# Commands and permissions

`/photomaps` (aliases `/pm`, `/photomap`) shows the help.

| Command | Permission | |
|---|---|---|
| `/photomaps give [player] [amount]` | `photomaps.admin` | give photo codes (to yourself without a player) |
| `/photomaps link` | `photomaps.admin` | open an upload page without a photo code, the link appears in chat |
| `/photomaps reload` | `photomaps.admin` | reload config and texts, restart the web server |

| Permission | Default | |
|---|---|---|
| `photomaps.command` | everyone | use `/photomaps` (the help) |
| `photomaps.use` | everyone | craft and use photo codes |
| `photomaps.unlimited` | op | uploads cost no empty maps |
| `photomaps.admin` | op | `give`, `link` and `reload` |
