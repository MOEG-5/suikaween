# Suikaween 🎃

A Halloween take on Suika (the watermelon game) for Android. Drop creatures into the jar; two of
the same kind merge into the next one. Don't let the pile cross the dashed line.

![Screenshot](docs/screenshot.png)

## Evolution chain

Candy Corn → Eyeball → Spider → Bat → Ghost → Skull → Black Cat → Cauldron → Mummy → Pumpkin →
Jack-o'-Lantern. Only the first five are ever dropped. Two Jack-o'-Lanterns vanish for a bonus.

## Squishy & slippery

Creatures are soft rather than rigid fruit, which makes the game a bit more forgiving:

- **Low friction** (`World.FRICTION`), so creatures slide past each other and into gaps
  instead of jamming.
- **Soft outlines.** Collision uses a circle a little smaller than the drawn body
  (`COLLIDE_FRACTION`), so neighbours press into each other. Each outline is a ring of springy
  vertices that flatten into a shared contact face, bulge elsewhere to keep their area, and
  wobble on impacts and merges (`World.updateShapes`).
- The physics is a stable position-based solver in small substeps, with inelastic contacts and a
  cap on how much speed a contact can add, so big piles settle instead of jittering or exploding.

Every tuning knob is a constant at the top of `World`.

## Shapes and sizes

Not every creature is round. Candy Corn, Bat, Ghost, Black Cat, Mummy, Pumpkin and
Jack-o'-Lantern are ovals (`Creature.aspect`), and they really are oval to the physics: each body
is a capsule, so wide ones sit flat, tall ones topple onto their side, and pieces rock and roll
off each other. Every creature has the same area as a circle of its `radius`, so tiers stay
comparable whatever their shape.

Sizes are tuned so the biggest creature is about half the jar: two Jack-o'-Lanterns only just fit
side by side (a unit test pins this), while the smallest are around a tenth of the jar's width.

`BalanceSimulation` lets simple bots play whole games and prints how far they get, which is
handy when tuning sizes or the jar. It is skipped unless `SIM` is set:

```sh
SIM=1 ./gradlew testDebugUnitTest --tests '*BalanceSimulation*' -i
```

## Download

Every push to the default branch is built by GitHub Actions and published as a Release. Grab the
newest APK on your phone from **Releases → latest → `suikaween.apk`**, or directly at
`https://github.com/MOEG-5/suikaween/releases/latest/download/suikaween.apk`.
Builds from other branches and pull requests are attached to their workflow run as artifacts.

### Release signing

For each build to install as an update over the last one (keeping your best score), they must
all be signed with the same key. Add these repository secrets (Settings → Secrets and variables →
Actions):

| Secret | Value |
| --- | --- |
| `SIGNING_KEYSTORE_BASE64` | your keystore file, base64-encoded (`base64 -w0 release.keystore`) |
| `SIGNING_STORE_PASSWORD` | keystore password |
| `SIGNING_KEY_ALIAS` | key alias |
| `SIGNING_KEY_PASSWORD` | key password |

Create a keystore once with
`keytool -genkeypair -keystore release.keystore -alias suikaween -keyalg RSA -keysize 2048 -validity 10000`
and keep it somewhere safe. Without the secrets, builds still work but use a throwaway debug key.

## Build

Requires JDK 17+ and the Android SDK (platform 35).

```sh
./gradlew assembleDebug        # app/build/outputs/apk/debug/app-debug.apk
./gradlew installDebug         # install on a connected device
./gradlew testDebugUnitTest    # physics tests + Robolectric render test
```

The render test writes real frames to `app/build/screenshots/`.

## Code map

- `World.kt`: capsule physics, merging, scoring, game over, and soft-body outlines (pure Kotlin)
- `Tiers.kt`: the creature chain (sizes, shapes, colours, points)
- `CreatureArt.kt`: procedural Canvas drawings of every creature (no image assets)
- `GameView.kt`: game loop, rendering, effects, HUD, and touch input
