module.exports = function (context) {
  const platform = require('cordova-android');
  const platformVersion = platform.version();
  const majorVersion = parseInt(platformVersion, 10);

  if (majorVersion >= 10) {
    console.log('[cordova-plugin-push::before-compile] skipping');
    return;
  }

  console.log('[cordova-plugin-push::before-compile] updating "build.gradle" kotlin version.');

  var path = require('path');
  var fs = require('fs');

  const platformPath = path.join(context.opts.projectRoot, 'platforms/android');
  const buildGradle = path.join(platformPath, '/build.gradle');

  if (!fs.existsSync(buildGradle)) {
    console.log('[cordova-plugin-push::before-compile] could not find "build.gradle" file.');
    return;
  }

  let buildGradleRaw = fs.readFileSync(buildGradle, 'utf8');

  buildGradleRaw = buildGradleRaw.replace(/ext.kotlin_version = ['"](.*)['"]/g, 'ext.kotlin_version = \'1.5.20\'');

  fs.writeFileSync(buildGradle, buildGradleRaw);

  console.log('[cordova-plugin-push::before-compile] finished updating "build.gradle" file.');
};
