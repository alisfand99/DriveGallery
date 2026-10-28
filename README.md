# Cloud Gallery (Drive Gallery)

An Android app that replaces the phone gallery. Every new photo and video is uploaded to Google Drive automatically, with the same folder structure as on the phone, and after a full verification (MD5 checksum) it can be removed from the phone.

## 1. Google access (already done)

The Google Cloud setup is finished: the Drive API is enabled, the `drive.file` scope is added, your account is registered as a test user, and an Android OAuth client exists for `app.drivegallery` with the SHA-1 of the bundled signing key. There is nothing to create or copy.

The project bundles a demo signing key (`app/drivegallery.jks`), public on purpose like Android's `debug.keystore`, so the SHA-1 always matches the OAuth client. It protects nothing. If you fork this, generate your own key and register its SHA-1 in your own Google Cloud OAuth client.

**Important:** the publishing status in Google Cloud is still Testing. In that state the access expires every 7 days and you have to tap Connect again. To fix it permanently: Google Cloud > Google Auth Platform > Audience > **Publish app**. The scope is not sensitive and needs no Google review.

## 2. Build and install

Every push to `main` builds the APK on GitHub Actions and publishes it as a release:

```
https://github.com/alisfand99/DriveGallery/releases/tag/latest
```

Download `DriveGallery.apk` on the phone (signed in to GitHub) and install it. Android will ask to allow installs from that source, and Play Protect may warn about an unknown app: choose Install anyway.

You can also open the project in Android Studio and press Run.

## 3. First run

1. Tap Connect and choose your Google account.
2. Set photo access to All, and grant photo location access too (without it Android strips GPS data from the file and the cloud copy would not be exact).
3. In Settings: allow notifications, **Media management** (for silent removal) and **Unrestricted battery**.
4. Keep the delete mode on Manual until the whole gallery is uploaded. Check the `PhoneGallery` folder in Drive. Then switch to Automatic or With a button if you want.

## 4. Delete modes (Settings)

| Mode | Behavior |
|---|---|
| Automatic | After upload and an MD5 match, the file is removed from the phone right away |
| Manual | The app never deletes anything, you delete on the phone yourself |
| With a button | The "Remove all uploaded files from the phone" button in Settings |

In every mode, a file is only removed if its Drive copy was verified. Without the Media management permission, Android shows a confirmation dialog for removals (one per 500 files).

## 5. Using it in WhatsApp, Telegram and other apps

- **Share from the app:** open a photo or select several, tap Share, and pick WhatsApp or Telegram.
- **From inside other apps:** any app that opens the standard Android picker lists Cloud Gallery as an option. In the Documents / Files picker there is also a storage location called Cloud Gallery.
- **Limit:** the Gallery tab inside WhatsApp and Telegram is their own built-in gallery, which reads straight from phone storage and cannot be replaced by another app. For those, use Document / File or the app's own Share.

## 6. Notes

- The app only sees files it created in Drive (the narrow `drive.file` scope). Files you add to Drive by hand do not appear in the app.
- Deleting in the app moves the file to the Drive trash (recoverable for 30 days).
- If you change phones or reinstall, signing in with the same account rebuilds the library from Drive and already uploaded files are not uploaded again.
- Changes made in Drive are synced to the app every 6 hours.
