import type { CapacitorConfig } from '@capacitor/cli';

import pkg from './package.json';

const config: CapacitorConfig = {
  "appId": "app.capgo.speechrecognition",
  "appName": "Speech Recognition Example",
  "webDir": "dist",
  "plugins": {
    "SplashScreen": {
      "launchAutoHide": false
    },
    "CapacitorUpdater": {
      "appId": "app.capgo.speechrecognition",
      "autoUpdate": true,
      "autoSplashscreen": true,
      "directUpdate": "always",
      "defaultChannel": "production",
      "version": pkg.version
    }
  }
};

export default config;
