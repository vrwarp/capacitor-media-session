# Media Session Example App

Demo app for `@jofr/capacitor-media-session`. It plays a single audio track
with an `<audio>` element and wires it up to the Media Session plugin
(see [`src/js/media-session.js`](src/js/media-session.js)): playback, position
and metadata changes are reported to the plugin and handlers are registered
for all media actions (play, pause, seek, previous/next track, stop), so the
system media notification, lock screen and hardware media keys control the
audio element in the WebView.

## Run on the web

```bash
npm install
npm start
```

## Run on Android

```bash
npm install
npm run build:android   # builds web assets, syncs Capacitor and assembles the APK
npx cap run android     # or: open android/ in Android Studio and run from there
```

Start playback in the app and the media notification should appear; the
notification controls, a Bluetooth headset or the lock screen can then be used
to control playback. Setting the playback state to `none` (the stop button)
removes the notification again.

## Tests

Unit tests for the media session wiring (using a mocked plugin and a stubbed
`<audio>` element) run with [Vitest](https://vitest.dev):

```bash
npm test
```
