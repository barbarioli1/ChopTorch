# GitHub Shipping Release

The shipping APK workflow creates a signed APK and attaches it to a GitHub Release. It does not publish to Google Play. A public repository makes the release and APK publicly downloadable.

## Before dispatching

- Commit and push the workflow, dispatch script, and intended app changes to `main`.
- Confirm the repository's `play-internal` environment and Google Play version lookup are configured, along with the signing and shipping model values used by the existing Play workflow.
- Update the release notes in `docs/releases/shipping-v2.2.md` for this release. Keep the notes accurate for the APK being published.
- Choose an unused tag in `vMAJOR.MINOR` or `vMAJOR.MINOR.PATCH` form, such as `v2.2`.
- Make sure `gh` is installed and authenticated with permission to dispatch workflows in this repository.

## Dispatch from VS Code

Open the repository in VS Code, then run this in its PowerShell terminal:

```powershell
.\scripts\release-shipping-apk.ps1 `
  -Tag v2.2 `
  -ReleaseNotesPath docs/releases/shipping-v2.2.md
```

The script sends the tag and Markdown notes to GitHub Actions. The workflow validates the tag, runs core tests, builds and verifies the signed shipping APK, selects a version code after the recorded Play and GitHub releases, creates the GitHub Release, and attaches the APK. The release tag determines the APK's `versionName`.

Monitor the run in the repository's **Actions** tab. A successful run prints the GitHub Release URL in its summary. The workflow refuses to reuse an existing tag and does not run until explicitly dispatched.

## Distribution notes

- The APK is the shipping variant: personalized training is disabled, and users install it by sideloading.
- Play distribution remains a separate process through the Play publishing workflow and Play Console.
- Apps installed from GitHub may not be updatable by Play if Play App Signing uses a different signing certificate.