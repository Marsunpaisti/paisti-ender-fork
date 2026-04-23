## Your responses

- Offer constructive criticism that identifies flaws and oversights.
- Always ask for more details and liberally search the web or code as needed to provide a good answer or implementation.
  Do not state your assumptions like they're facts.
- User should not have to ask you for your opinion explicitly. Always evaluate what the user is asking you to do, and
  voice your concerns before proceeding if you don’t think it's a good idea. If possible, propose a better solution, but
  you can voice concerns even without one. Still evaluate whether your original approach was better. The user may be
  missing important context. If there was a solid reasoning you suggested that approach, push back with reasoning
  instead of silently complying.

## MCP Servers
- Use the ide-index-mcp skill for code navigation and search.

## References
- If the user references the Nurgling repository, a local clone of it can be found under C:\Users\kytol\Desktop\Repositories\haven-nurgling2

## Build And Run
- This repo is Ant-only.
- Main commands:
  - `ant bin -buildfile build.xml` builds the distributable client in `bin/`
  - `ant run -buildfile build.xml` builds `bin/` and launches it
  - `ant clean-code bin -buildfile build.xml` recompiles code without wiping downloaded native deps
  - `ant clean -buildfile build.xml` also deletes `lib/ext`, so the next build redownloads JOGL/LWJGL/Steamworks jars
- `build/hafen.jar` is not the normal play target. Use `bin/hafen.jar`; `bin/` is where the official-server config, `client-res.jar`, and downloaded native jars are assembled.
- If you change `resources/src/**` or packaged data under `etc/*.json*`, rerun full `ant bin`; those files are bundled into `build/client-res.jar`/`bin/client-res.jar`.

## Repo Boundaries
- `src/haven/**` is still the vanilla Haven client core and the main surface for upstream Loftar merges. Some of the vanilla code might have been modified by Ender.
- `src/me/ender/**`: Ender fork UI/features/helpers
- `src/auto/**`: Ender fork automation helpers
- `src/paisti/**`: The place where most of our own code additions are concentrated in.
- `resources/src/local/paginae/add/**`: custom action pages/icons/help text
- `etc/*.json*`: some static raw data like the executables icon, alchemy/treatment datasets etc.

## What This Fork Adds Beyond Vanilla
- Ender UI/features from the Ender upstream fork
- Local resources under `resources/src/local/`
- Our forks custom code under `src/paisti/`