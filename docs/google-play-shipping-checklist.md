# Google Play Shipping Checklist

Use this checklist to take a reviewed ChopTorch release candidate from the repository to a staged Google Play production rollout. Check an item only after its evidence exists. Play Console requirements can vary by account and change over time; verify them in Play Console before each release.

## 1. Release candidate

- [ ] Select a release owner and identify the exact source revision for the candidate.
- [ ] Review the working tree. Commit and push only the intended release changes to `main` or `master`; the publishing workflow checks out the selected branch and does not publish uncommitted local changes.
- [ ] Run `bash tests/run-core-tests.sh` and retain its output.
- [ ] Run `gradlew.bat lintDebug` and resolve or explicitly accept each warning relevant to release.
- [ ] Build development and shipping variants and confirm the shipping application ID is `com.barbarioli1.choptorch`.
- [ ] Run the device tests for both variants on the supported device set. Current smoke coverage checks Activity launch and the `ENABLE_TRAINING` build flag only; it does not cover monitoring, torch behavior, permissions, or foreground-service lifecycle.
- [ ] Add or run release-relevant tests for permission grant/denial/revocation, service start/stop, notification actions, model-load failure, torch/sensor unavailability, and lifecycle cleanup; record any explicitly accepted gaps.
- [ ] Verify the shipping model asset and record its hash and provenance.
- [ ] Check the current Play target API requirement, app-bundle requirements, account-specific testing requirements, and developer-account status in Play Console.
- [ ] Confirm the publishing workflow can read Google Play tracks. It selects one greater than the highest version code in active track releases, with a minimum of `5`; Play's API does not expose a complete history of removed releases, so investigate any manually uploaded or retired releases if they may have higher codes.
- [ ] Set the release `versionName` in `app/build.gradle` and verify the resulting artifact's version values.

## 2. Signing and key custody

- [ ] Decide whether to enroll in Play App Signing and document the distinction between Google's app-signing key and the upload key.
- [ ] Create or confirm the upload key using a secure process approved by the release owner.
- [ ] Back up the upload key and recovery information in approved secure storage; restrict access to the people and systems that need it.
- [ ] Never commit the keystore, passwords, private key material, service-account keys, or encoded credentials to the repository.
- [ ] Verify that the release build fails closed when signing values are missing and produces a signed shipping AAB when configured.

## 3. GitHub and Google Cloud publishing setup

- [ ] Confirm the repository contains `.github/workflows/publish-play-internal.yml` and that publishing is restricted to `main` or `master`.
- [ ] Create the GitHub Actions environment named `play-internal`; configure any required reviewers or branch restrictions.
- [ ] In Google Cloud, enable the Google Play Android Developer API and link the Google Cloud project to the Play developer account as required by the current Google setup flow.
- [ ] Configure Workload Identity Federation for GitHub Actions with conditions restricted to the intended repository, branch, and workflow identity.
- [ ] Create or select a service account for publishing and grant it only the Play Console app permissions needed to upload to the internal track.
- [ ] Add these GitHub environment **secrets**: `ANDROID_UPLOAD_KEYSTORE_BASE64`, `ANDROID_UPLOAD_KEYSTORE_PASSWORD`, `ANDROID_UPLOAD_KEY_ALIAS`, and `ANDROID_UPLOAD_KEY_PASSWORD`.
- [ ] Add these GitHub environment **variables**: `GCP_WORKLOAD_IDENTITY_PROVIDER` and `GCP_SERVICE_ACCOUNT`.
- [ ] Verify that no long-lived Google service-account key file is required or stored by the workflow; it authenticates through Workload Identity Federation.
- [ ] Confirm the workflow skips listing metadata, images, screenshots, and changelogs. Those must be entered or updated separately in Play Console.

## 4. App policy and store listing

- [ ] Review the manifest and runtime behavior for camera, notification, foreground-service, wake-lock, and any other permissions. Confirm each is necessary and disclosed.
- [ ] Complete the Play Console `specialUse` foreground-service declaration with an accurate explanation of user-started continuous sensor monitoring and provide any requested evidence. Do not assume approval.
- [ ] Audit the app and every included SDK for data collection, storage, sharing, identifiers, and retention. Base disclosures on the actual shipping behavior, including locally stored examples or models where applicable.
- [ ] Publish a privacy policy that accurately matches the shipping app and is reachable at its declared URL.
- [ ] Complete the Data safety form, content rating, target audience, ads declaration, app access instructions, and any other forms required for this app and account.
- [ ] Complete the store listing: app name, short and full descriptions, support contact, icon, feature graphic, and screenshots captured from the real shipping UI.
- [ ] Explain the gesture-triggered torch behavior, hardware requirements, user-controlled monitoring, battery costs, and known background limitations accurately.
- [ ] Verify that listing text and artwork comply with current Play metadata rules and that the project has rights to every submitted asset.

## 5. Internal testing release

- [ ] Confirm the reviewed release commit is on `main` or `master` and all required GitHub environment values are configured.
- [ ] In GitHub Actions, dispatch **Publish shipping AAB to Play internal testing**. The workflow looks up active releases across all tracks and selects the next version code automatically.
- [ ] Confirm the workflow passes core tests, builds the signed shipping release AAB, authenticates through Workload Identity Federation, and uploads to the **internal** track.
- [ ] Retain the workflow run URL, source revision, version code, AAB identity/hash, model hash, and upload result. The expected build output is `app/build/outputs/bundle/release/app-release.aab`.
- [ ] Confirm Play Console shows the release on the internal track and review processing, artifact details, policy notices, and pre-launch report.
- [ ] Add the intended internal testers and provide clear installation instructions.
- [ ] Install the app from the Play testing link, not only by sideloading a local APK.
- [ ] Test first install and upgrade from the previous production version. Verify app identity, version, model availability, the production UI, monitoring start/stop, torch behavior, permissions, and recovery paths.
- [ ] Record tester devices, Android versions, test results, defects, and the release owner's decision to proceed or stop.

## 6. Production rollout

- [ ] Confirm all applicable account-specific closed/open testing or production-access requirements are satisfied.
- [ ] Confirm required policy declarations and review outcomes are complete; resolve blocking Play Console warnings or rejection issues.
- [ ] Review internal-track feedback and pre-launch reports. Resolve release-blocking crashes, ANRs, policy problems, and data-disclosure mismatches.
- [ ] Name the production release owner, support contact, monitoring window, escalation path, and criteria for halting rollout.
- [ ] Choose a staged rollout percentage and schedule appropriate to the app's risk and audience.
- [ ] Promote the tested release through the required Play Console track to production. The GitHub workflow in this repository uploads only to the internal track; it does not publish to production.
- [ ] Monitor Android Vitals, crashes, ANRs, wake-lock and battery reports, device-specific failures, reviews, and support requests during rollout.
- [ ] Halt further rollout if the stop criteria are met. Keep a tested forward-fix plan; stopping a rollout does not downgrade users who already received the app.
- [ ] Archive the final release record: revision, signed artifact, version, model provenance, test evidence, policy review date, rollout decisions, and approvals. Do not archive secrets.

## 7. Store search optimization (ASO) and keywords

Play search ranks mainly on title, short description, full description, category/tags, and install/engagement signals. Google's metadata policy prohibits repetitive or irrelevant keyword blocks, misleading claims, and implied relationships with other brands, so optimize by being specific and natural. Verify all limits in Play Console; the character counter there is authoritative.

### 7.1 Naming and trademark safety (do first)

ChopTorch is inspired by Motorola's chop-to-flashlight feature. Motorola's site lists MOTOROLA as a registered trademark. I could not verify the status of the feature name "Chop Chop", so treat it as third-party.

- [ ] Do not use "Motorola", "Moto", or "Chop Chop" in the app title, developer name, icon, graphics, screenshots, package name, or description. Metadata policy bars brand names or logos without permission and any wording that implies a relationship or endorsement.
- [ ] Do not describe the app as a copy, clone, port, or "official" version of any phone maker's feature, and do not compare it to other apps.
- [ ] If you still want any reference to the Motorola feature, get legal review first. Do not rely on a "fan app" disclaimer.
- [ ] Check that "ChopTorch" does not resemble an existing Play app title or icon closely enough to mislead users.
- [ ] Do not show knives or cleavers in the icon, feature graphic, screenshots, or video. Play bars graphic violence in listing assets. Use a hand-chop or phone-motion illustration.

### 7.2 Keyword research (before writing copy)

I did not obtain real search-volume data. The candidates below are hypotheses to validate, not confirmed high-traffic terms.

- [ ] Write seed terms in plain user language, for example: gesture flashlight, chop flashlight, motion flashlight, flashlight gesture, hands-free torch, flashlight without touching phone, double chop flashlight.
- [ ] Type each seed into the Google Play search box and record every autocomplete suggestion.
- [ ] Open the top 10 results for each seed and note their titles, short descriptions, categories, install counts, and rating counts. Use this to find gaps, not to copy text or make comparisons.
- [ ] Check demand with a second source such as Google Trends. Do not rely on a single third-party ASO tool's volume numbers.
- [ ] Build a spreadsheet with columns: term, source, relevance (1-5), competition (1-5), and where it will be used. Drop terms the app cannot truthfully support.
- [ ] Group terms by user intent: finding a flashlight, hands-free use, and the specific chop gesture.
- [ ] Remove terms that imply something unverified: "works with screen locked", "works on all phones", "never misses", "accurate", or battery claims. Confirm each against test evidence first. The app needs a flash, an accelerometer, and a gyroscope.

### 7.3 Draft the metadata

- [ ] Title, 30 characters or fewer, no emojis, ALL CAPS, or promotional words such as "free" or "no ads". Candidate: `ChopTorch: Gesture Flashlight` (29 characters).
- [ ] Short description, 80 characters or fewer, benefit-led, with no price or ranking terms. Candidate: `Double-chop your phone to toggle the flashlight without touching the screen.` (76 characters).
- [ ] Full description, 4,000 characters or fewer, with the key facts in the first few sentences because many users read only those.
- [ ] Do not repeat the short description in the full description.
- [ ] Use primary terms naturally, a few times each, in sentences. Never use a list of keywords, as Google's own example of a violation shows.
- [ ] State requirements honestly: needs a camera flash and motion sensors, and monitoring is user-started and runs as a foreground service with a notification.
- [ ] State battery impact and background limits plainly.
- [ ] Describe the gesture accurately as a double chop motion of the phone, matching the tested behavior.
- [ ] Leave out unattributed testimonials, "best" or "#1" claims, awards, and price or discount text.
- [ ] Re-check every claim in the title, short description, and full description against recorded test results.

### 7.4 Category, tags, and graphics

- [ ] Choose the category that best matches the primary purpose. Tools is the likely fit; confirm in Play Console.
- [ ] Select up to five tags that are obviously relevant to the app. Do not add tags only because similar apps use them.
- [ ] Use accessibility tags only if you have actually tested the app against that need.
- [ ] Icon: simple, distinctive, no misleading symbols, and not similar to another product's icon.
- [ ] Feature graphic: minimal text, key elements centered, consistent with the icon and screenshots.
- [ ] Screenshots: real in-app UI from the shipping build, showing the gesture and the monitoring controls. Keep any caption text minimal and localized per language.
- [ ] Optional video: a short demo of the chop gesture toggling the torch, with no knife imagery.

### 7.5 Localization

- [ ] Choose launch languages and markets from real demand, not from the whole language list.
- [ ] Translate title, short description, full description, and any text in screenshots. Metadata policy applies to every translation.
- [ ] Re-run keyword research for each language instead of translating English terms word for word.
- [ ] Have a fluent reviewer check each translation for accuracy and unintended meaning.

### 7.6 In-app and off-store signals

- [ ] Make sure the app name, launcher label, and first-run text match the store listing.
- [ ] Publish the privacy policy and support contact referenced by the listing.
- [ ] Prompt for ratings only from users who have successfully used the feature, and only within Play's rules. Never offer rewards for reviews.
- [ ] Reply to reviews, and use crash, battery, and false-activation feedback to improve the app. Quality and retention feed ranking.

### 7.7 Measure and iterate

- [ ] After launch, open the Play Console store listing acquisition reports, including the search-term data if available for your account. Record which queries bring installs.
- [ ] Set a baseline for impressions, store listing visitors, install conversion, and 1-day and 30-day retention before changing copy.
- [ ] Run store listing experiments one asset at a time: icon, feature graphic, screenshots, or description. Choose a target metric such as unique user install clicks and wait for a clear result before applying.
- [ ] Change keywords in small batches and re-measure, so results can be attributed.
- [ ] Review the listing at each release and after any change in features, permissions, or behavior.

### 7.8 Website SEO (optional, separate from Play)

- [ ] Decide whether a landing page is needed. Skip it if the Play listing and privacy page are enough.
- [ ] If you build one, use one page per intent, a unique title and meta description, a real demo, a link to the Play listing, and the same truthful claims as the listing.
- [ ] Do not publish a separate site that uses Motorola or Chop Chop names to attract search traffic.

## Current known status

- [x] Development and shipping debug instrumentation builds compile.
- [x] Dev and shipping smoke tests passed on a moto g53 5G running Android 14 (SDK 34); each variant reported 2 passed, 0 failed, and 0 skipped.
- [ ] Invalid-serial, locked-device, and failure-injection checks are still pending.
- [ ] Release signing, the signed AAB, GitHub/Google publishing credentials, Play Console declarations, and production rollout have not been verified.
- [ ] Broader service, permission, torch, accessibility, performance, battery, recognition-quality, and device-matrix release gates need results or explicit release-owner acceptance.
