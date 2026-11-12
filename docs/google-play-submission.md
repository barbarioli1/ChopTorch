# Google Play Submission Notes

Prepared from a source review of the production configuration and app code on November 11, 2026. These are evidence-based suggested answers, not a substitute for reviewing each Play Console question against the exact release artifact and current Play policies.

## Privacy policy

Use `https://barbarioli1.github.io/ChopTorch/privacy.html` after GitHub Pages is enabled on `main` with `/docs` selected and the published page has been tested. Portuguese (Brazil) version: `https://barbarioli1.github.io/ChopTorch/privacy-pt.html`.

The policy identifies Guilherme Barbarioli and gives `barbarioli.1+github@gmail.com` as privacy/support contact. It explains local sensor processing, flashlight access, local preference storage, deletion, and the production/development build distinction.

## Data safety

**Suggested answers for the production Play build, subject to checking the final AAB and all Play Console declarations:**
- Does the app collect or share user data? **No**, based on the production manifest and reviewed app code: no Internet permission, network client, analytics, ad, crash-reporting, or data-transfer SDK was found. Motion readings are processed locally and are not retained by the production build.
- Is data encrypted in transit? If no data is transmitted by the app, this question may be not applicable in the form. Answer according to the current Console flow.
- Can users request deletion? There is no server-side account or data. Explain local deletion in the privacy policy: clear app storage or uninstall.

Re-check the final production AAB and dependency list before submitting. Google Play itself and Android system services are outside the app's direct data transmission behavior.

## Ads and app access

- **Contains ads:** No. The production app has no ad SDK or ad content.
- **App access / login:** No login, account, subscription gate, or access code. All app functions can be reached without credentials.

## Target audience and content rating

- **Target audience:** Human decision required. The app is a general-purpose utility, not designed specifically for children. Select age groups based on the actual marketing, intended users, and Play's current Families requirements; do not select children simply because the utility is broadly usable.
- **Content rating:** Complete the IARC questionnaire based on the shipped build and store listing. Do not copy answers from another app. The app is a flashlight utility and contains no user-generated content, social features, or age-restricted media based on this code review.

## Store category and declarations

- **Category:** Tools.
- **Financial features:** No financial services, payments, lending, investment, or cryptocurrency features were found.
- **Health features:** No health, medical, fitness, or clinical function is presented or implemented.
- **Government app:** No; the app is not developed by or on behalf of a government entity.
- **Ads:** No, as above.

Verify each answer against the exact Console wording at submission time.

## Permissions and foreground service

Production manifest permissions and purpose:
- `CAMERA`: uses Android's Camera2 torch API to switch the flashlight; no photo/video capture implementation was found.
- `VIBRATE`: optional feedback when the flashlight state changes.
- `FOREGROUND_SERVICE` and `FOREGROUND_SERVICE_SPECIAL_USE`: user-started continuous motion monitoring while the app is not visible.
- `POST_NOTIFICATIONS`: ongoing monitoring notification on Android versions that require runtime notification permission.
- `WAKE_LOCK`: keep the CPU available to receive non-wake-up accelerometer/gyroscope events while the screen is off.

The manifest's special-use subtype is: “User started continuous accelerometer and gyroscope detection to toggle the flashlight with a double-chop gesture while the display is locked.” The detector needs foreground execution because users expect the gesture to work after they lock the display; the service uses a partial wake lock because these sensor readings do not themselves keep the CPU awake. This is power intensive and must be clearly disclosed.

The user starts monitoring from the visible app after setup. An ongoing notification appears with a Stop action; stopping releases the sensor listener and wake lock. Android, battery restrictions, or device-specific software may stop the service. The app does not secretly restart it (`START_NOT_STICKY`); monitoring may then be unavailable until the user starts it again.

For the Play foreground-service declaration, provide a concise use-case explanation and any requested demo. Do not claim uninterrupted operation or guaranteed detection.

## Screenshots and video to prepare

- Phone screenshots of the home screen and flashlight control.
- Screenshot of the setup/permission explanation (camera and notifications).
- Screenshot of the active monitoring notification with the Stop action.
- Screenshot of settings and the About/privacy entry.
- Store icon and feature graphic in the sizes required by the current Console.
- A short real-device video: open the app, start monitoring, show the persistent notification, lock the screen, demonstrate the double chop and torch toggle, then stop monitoring from the notification. Show actual device behavior and do not imply that Android cannot interrupt the service.
- If Play asks for a special-use foreground-service demonstration, submit a recording that clearly shows user initiation, the user-visible ongoing notification, and how to stop it.

## Release verification checklist

- Confirm the AAB application ID is `com.barbarioli1.choptorch`, target SDK is 36, and version code exceeds every code already uploaded to this Play app.
- Inspect the final merged/release manifest for permissions; compare it with the list above.
- Confirm the upload signature is the expected upload key and Play App Signing is enabled.
- Confirm no model examples, local secrets, or debug-only training features are present in the shipping artifact.
- Test the policy URL after Pages deployment; do not submit the expected URL before it is reachable without login.
