# Contributing

PRs and issues: [nanocodium/ndi-displays](https://github.com/nanocodium/ndi-displays).

The wiki lives in this repo under `docs/` (VitePress) and is published at [wiki.nailec.fr](https://wiki.nailec.fr). Edit the markdown; do not maintain a second wiki.

## Ground rules

- **One job per PR.** A hoist bug and a new camera do not share a branch.
- **Do not rewrite NDI, Theatrical, Extra Lights, or SEF** inside this tree. Soft-compat only (`compat/`). Official jars — no vendored forks.
- **Do not commit secrets**, local `JAVA_HOME` paths, or `~/.gradle/gradle.properties`.
- Ship the **`-all`** jar. The thin jar has no Devolay and is not a release.

## Changelog (required)

Every user-visible change must be written **twice**, same meaning, Keep a Changelog headings:

1. Root [`CHANGELOG.md`](https://github.com/nanocodium/ndi-displays/blob/main/CHANGELOG.md)
2. Wiki [`docs/reference/changelog.md`](/reference/changelog)

Put new notes under **`## [Unreleased]`**. Do not edit a shipped version’s notes unless you are fixing a typo.

Use the existing buckets:

| Heading | Use for |
|---------|---------|
| Added | New block, item, guide, tag, config key |
| Changed | Behaviour or look of something that already existed |
| Fixed | A bug |
| Removed | Something players used to have |

Rules we actually enforce:

- Write **why it matters**, not a file list.
- Name the **registry id** (`ndidisplays:led_inner_corner`) and the **wiki link** on the wiki copy.
- Name the **ship jar** when you cut a release (`ndidisplays-1.20.1-x.y.z-all.jar`).
- If you pull [upstream `main`](https://github.com/nanocodium/ndi-displays), say which commit you include. Do not leave their work undocumented.
- Versions follow [SemVer](https://semver.org/). Betas keep the `-beta.N` suffix on both the changelog heading and the artifact.

A PR that changes gameplay or docs without an Unreleased line will be asked to add one before merge.

## Build

Java **17** for `compileJava`. Gradle 8.1.1 must **run** on 8–19 (not 21). See [Install](/guide/install) and the README **Build** section.

```bat
gradlew.bat build
```

```bash
./gradlew build
```

Ship `build/libs/ndidisplays-1.20.1-*-all.jar` only.

Dev client: `gradlew.bat runClient`. Runtime dir: `NDI_RUNTIME_DIR_V6` / `V5` or `-PndiRuntimeDir=…`.

## Wiki

```bash
cd docs
npm install
npm run docs:dev
```

- Block catalog [`docs/blocks/index.md`](/blocks/) is generated (`docs/scripts/gen-from-mod.mjs`). Do not hand-edit it; add a page + script map entry for a new registry id.
- Keep the player voice: short sentences, registry ids in backticks, links to other wiki pages.
- English is the wiki language (`en-US`).
- After a new guide, add it to `.vitepress/config.ts` sidebar **and** the home “Start here” list when it is a first-run path.

## PR checklist

- [ ] `CHANGELOG.md` Unreleased updated
- [ ] `docs/reference/changelog.md` Unreleased updated (same facts)
- [ ] Wiki / lang / recipe / tag updated if players see the change
- [ ] No second copy of Theatrical / Extra Lights / SEF / NDI SDK sources
- [ ] Tested Forge 1.20.1; `-all` jar still starts without optional mods
