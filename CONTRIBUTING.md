# Contributing

This guide provides instructions for contributing to this Capacitor plugin.

## Developing

### Local Setup

1. Fork and clone the repo.
1. Install the dependencies.

    ```shell
    npm install
    ```

1. Android work needs a JDK and the Android SDK (`ANDROID_HOME` or `android/local.properties`). The plugin has no native iOS code: on iOS (and Web) it forwards to `navigator.mediaSession`.

### Scripts

#### `npm run build`

Build the plugin web assets and generate plugin API documentation using [`@capacitor/docgen`](https://github.com/ionic-team/capacitor-docgen).

It will compile the TypeScript code from `src/` into ESM JavaScript in `dist/esm/`. These files are used in apps with bundlers when your plugin is imported.

Then, Rollup will bundle the code into a single file at `dist/plugin.js`. This file is used in apps without bundlers by including it as a script in `index.html`.

#### `npm test`

Run the TypeScript unit tests (`src/web.ts` and the `src/index.ts` proxy) with [Vitest](https://vitest.dev/) in jsdom. Use `npm run test:watch` while developing and `npm run test:coverage` for a coverage report.

#### `npm run verify`

Build and validate the web and native projects: `verify:android` runs `./gradlew clean build test` in `android/` (the JVM unit tests in `android/src/test/`), and `verify:web` runs `npm run build` followed by `npm test`.

This is useful to run in CI to verify that the plugin builds for all platforms.

#### `npm run lint` / `npm run fmt`

Check formatting and code quality, autoformat/autofix if possible.

This template is integrated with ESLint and Prettier (TypeScript and Java). Using these tools is completely optional, but the [Capacitor Community](https://github.com/capacitor-community/) strives to have consistent code style and structure for easier cooperation. The `swiftlint` script is a leftover from the plugin template; it only matters if native iOS code is ever added.

## Publishing

There is a `prepublishOnly` hook in `package.json` which builds the plugin before publishing (it does not run the tests). Releases are published by the `npm-publish` GitHub workflow when a `v*` tag is pushed: it runs `npm run build` and `npm test` before `npm publish`. To publish by hand, run the tests first and then:

```shell
npm publish
```

> **Note**: The [`files`](https://docs.npmjs.com/cli/v7/configuring-npm/package-json#files) array in `package.json` specifies which files get published. If you rename files/directories or add files elsewhere, you may need to update it.
