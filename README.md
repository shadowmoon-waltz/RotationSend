RotationSend
============

Shows current phone orientation (roll, pitch, and azimuth - see [Android Developers documentation](https://developer.android.com/guide/topics/sensors/sensors_position#sensors-pos-orient)) and a camera passthrough feed (when given camera permissions). Also optionally allows sending the orientation over UDP to a server (example provided in Python).

Permissions required include Internet/Full Network Access (to use UDP sockets) and Camera (optional, it doesn't request permission, but there is a shortcut to app settings when viewing settings).

License is GPLv3.

Build command is: gradle assembleRelease (requires Gradle and Android SDK installation).

<details>
<summary>Instructions for building with GitHub Actions</summary>

There is a workflow to build in release and create a GitHub release with compiled apk. These also sign your apks (use if you're comfortable with the build server signing them). To use these workflows yourself, start by forking this repository. Follow the
[Android developer instructions to generate a private key](https://developer.android.com/studio/build/building-cmdline#sign_cmdline) and then convert it to a base64 string with `openssl base64 < keystore.jks | tr -d '\n' | tee keystore.txt` in a Linux-like terminal and then put it in a GitHub Actions secret called "KEYSTORE_BASE64". Put the keystore password in a secret called "KEYSTORE_PASSWORD", the keystore key alias in "KEYSTORE_ALIAS", and
the keystore key alias password in "KEYSTORE_ALIAS_PASSWORD" ([more info on Github Action secrets](https://docs.github.com/en/actions/security-guides/encrypted-secrets)).
</details>
