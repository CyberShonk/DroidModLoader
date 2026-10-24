## Summary

Describe the focused change.

## Behavior visible to users

Describe what users will notice, or state that behavior is intentionally unchanged.

## Risk and safety

Describe affected storage, archive, profile, plugin, deployment, recovery, or Android lifecycle behavior.

## Validation

- [ ] `git diff --check`
- [ ] Focused tests, when applicable
- [ ] `./gradlew testDebugUnitTest`, when applicable
- [ ] `./gradlew lintDebug`, when applicable
- [ ] `./gradlew assembleDebug`, when applicable
- [ ] Validation on a real device, when the change needs it

For changes limited to documentation, run relevant documentation checks instead of app tests or builds.
Explain any checks that were not applicable or could not run.

## Documentation

- [ ] User documentation was updated when behavior or limitations changed
- [ ] Changelog or release notes were updated when applicable
